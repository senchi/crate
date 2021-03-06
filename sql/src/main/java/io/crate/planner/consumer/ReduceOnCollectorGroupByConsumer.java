/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.planner.consumer;

import io.crate.Constants;
import io.crate.analyze.*;
import io.crate.analyze.relations.AnalyzedRelation;
import io.crate.analyze.relations.AnalyzedRelationVisitor;
import io.crate.analyze.relations.TableRelation;
import io.crate.analyze.where.WhereClauseAnalyzer;
import io.crate.exceptions.VersionInvalidException;
import io.crate.metadata.table.TableInfo;
import io.crate.operation.projectors.TopN;
import io.crate.planner.PlanNodeBuilder;
import io.crate.planner.PlannerContextBuilder;
import io.crate.planner.RowGranularity;
import io.crate.planner.node.NoopPlannedAnalyzedRelation;
import io.crate.planner.node.dql.CollectNode;
import io.crate.planner.node.dql.GroupByConsumer;
import io.crate.planner.node.dql.MergeNode;
import io.crate.planner.node.dql.NonDistributedGroupBy;
import io.crate.planner.projection.ColumnIndexWriterProjection;
import io.crate.planner.projection.FilterProjection;
import io.crate.planner.projection.GroupProjection;
import io.crate.planner.projection.TopNProjection;
import io.crate.planner.symbol.Symbol;

import java.util.List;

import static com.google.common.base.MoreObjects.firstNonNull;

public class ReduceOnCollectorGroupByConsumer implements Consumer {

    private final Visitor visitor;

    public ReduceOnCollectorGroupByConsumer(AnalysisMetaData analysisMetaData) {
        visitor = new Visitor(analysisMetaData);
    }

    @Override
    public boolean consume(AnalyzedRelation rootRelation, ConsumerContext context) {
        Context ctx = new Context(context);
        context.rootRelation(visitor.process(context.rootRelation(), ctx));
        return ctx.result;
    }

    private static class Context {
        ConsumerContext consumerContext;
        boolean result = false;

        public Context(ConsumerContext context) {
            this.consumerContext = context;
        }
    }

    private static class Visitor extends AnalyzedRelationVisitor<Context, AnalyzedRelation> {

        private final AnalysisMetaData analysisMetaData;

        public Visitor(AnalysisMetaData analysisMetaData) {
            this.analysisMetaData = analysisMetaData;
        }

        @Override
        public AnalyzedRelation visitQueriedTable(QueriedTable table, Context context) {
            if (context.consumerContext.rootRelation() != table) {
                return table;
            }
            if (table.querySpec().groupBy() == null) {
                return table;
            }
            if (!GroupByConsumer.groupedByClusteredColumnOrPrimaryKeys(table.tableRelation(), table.querySpec().groupBy())) {
                return table;
            }

            WhereClauseAnalyzer whereClauseAnalyzer = new WhereClauseAnalyzer(analysisMetaData, table.tableRelation());
            WhereClause whereClause = whereClauseAnalyzer.analyze(table.querySpec().where());
            if (whereClause.version().isPresent()) {
                context.consumerContext.validationException(new VersionInvalidException());
                return table;
            }
            context.result = true;
            return ReduceOnCollectorGroupByConsumer.optimizedReduceOnCollectorGroupBy(table, table.tableRelation(),
                    whereClause, null);
        }


        @Override
        protected AnalyzedRelation visitAnalyzedRelation(AnalyzedRelation relation, Context context) {
            return relation;
        }


    }

    /**
     * grouping on doc tables by clustered column or primary keys, no distribution needed
     * only one aggregation step as the mappers (shards) have row-authority
     *
     * produces:
     *
     * SELECT:
     * CollectNode ( GroupProjection, [FilterProjection], [TopN] )
     * LocalMergeNode ( TopN )
     *
     * INSERT FROM QUERY:
     * CollectNode ( GroupProjection, [FilterProjection], [TopN] )
     * LocalMergeNode ( [TopN], IndexWriterProjection )
     */
    public static AnalyzedRelation optimizedReduceOnCollectorGroupBy(QueriedTable table, TableRelation tableRelation, WhereClause whereClause, ColumnIndexWriterProjection indexWriterProjection) {
        assert GroupByConsumer.groupedByClusteredColumnOrPrimaryKeys(tableRelation, table.querySpec().groupBy()) : "not grouped by clustered column or primary keys";
        TableInfo tableInfo = tableRelation.tableInfo();
        GroupByConsumer.validateGroupBySymbols(tableRelation, table.querySpec().groupBy());
        List<Symbol> groupBy = tableRelation.resolve(table.querySpec().groupBy());
        boolean ignoreSorting = indexWriterProjection != null
                && table.querySpec().limit() == null
                && table.querySpec().offset() == TopN.NO_OFFSET;
        int numAggregationSteps = 1;
        PlannerContextBuilder contextBuilder =
                new PlannerContextBuilder(numAggregationSteps, groupBy, ignoreSorting)
                        .output(tableRelation.resolve(table.querySpec().outputs()))
                        .orderBy(tableRelation.resolveAndValidateOrderBy(table.querySpec().orderBy()));

        HavingClause havingClause = table.querySpec().having();
        Symbol havingQuery = null;
        if(havingClause != null){
            if (havingClause.noMatch()) {
                return new NoopPlannedAnalyzedRelation(table);
            } else if (havingClause.hasQuery()){
                havingQuery = contextBuilder.having(havingClause.query());
            }
        }
        // mapper / collect
        List<Symbol> toCollect = contextBuilder.toCollect();

        // grouping
        GroupProjection groupProjection =
                new GroupProjection(contextBuilder.groupBy(), contextBuilder.aggregations());
        groupProjection.setRequiredGranularity(RowGranularity.SHARD);
        contextBuilder.addProjection(groupProjection);

        // optional having
        if (havingQuery != null) {
            FilterProjection fp = new FilterProjection(havingQuery);
            fp.outputs(contextBuilder.genInputColumns(groupProjection.outputs(), groupProjection.outputs().size()));
            fp.requiredGranularity(RowGranularity.SHARD); // running on every shard
            contextBuilder.addProjection(fp);
        }

        // use topN on collector if needed
        TopNProjection topNReducer = getTopNForReducer(
                table.querySpec(),
                contextBuilder,
                contextBuilder.outputs());
        if (topNReducer != null) {
            contextBuilder.addProjection(topNReducer);
        }

        CollectNode collectNode = PlanNodeBuilder.collect(
                tableInfo,
                whereClause,
                toCollect,
                contextBuilder.getAndClearProjections()
        );
        // handler

        if (!ignoreSorting) {
            List<Symbol> orderBySymbols;
            List<Symbol> outputs;
            if (topNReducer == null) {
                orderBySymbols = contextBuilder.orderBy();
                outputs = contextBuilder.outputs();
            } else {
                orderBySymbols = contextBuilder.passThroughOrderBy();
                outputs = contextBuilder.passThroughOutputs();
            }
            TopNProjection topN;
            int limit = firstNonNull(table.querySpec().limit(), Constants.DEFAULT_SELECT_LIMIT);
            OrderBy orderBy = table.querySpec().orderBy();
            if (orderBy == null){
                topN = new TopNProjection(limit, table.querySpec().offset());
            } else {
                topN = new TopNProjection(limit, table.querySpec().offset(),
                        orderBySymbols,
                        orderBy.reverseFlags(),
                        orderBy.nullsFirst()
                );
            }
            topN.outputs(outputs);
            contextBuilder.addProjection(topN);
        }
        if (indexWriterProjection != null) {
            contextBuilder.addProjection(indexWriterProjection);
        }
        MergeNode localMergeNode = PlanNodeBuilder.localMerge(contextBuilder.getAndClearProjections(), collectNode);
        return new NonDistributedGroupBy(collectNode, localMergeNode);
    }

    /**
     * returns a topNProjection intended for the reducer in a group by query.
     *
     * result will be null if topN on reducer is not needed or possible.
     *
     * the limit given to the topN projection will be limit + offset because there will be another
     *
     * @param outputs list of outputs to add to the topNProjection if applicable.
     */
    @javax.annotation.Nullable
    private static TopNProjection getTopNForReducer(QuerySpec querySpec,
                                                    PlannerContextBuilder contextBuilder,
                                                    List<Symbol> outputs) {
        if (requireLimitOnReducer(querySpec, contextBuilder.aggregationsWrappedInScalar)) {
            OrderBy orderBy = querySpec.orderBy();
            TopNProjection topN;
            int limit = firstNonNull(querySpec.limit(), Constants.DEFAULT_SELECT_LIMIT) + querySpec.offset();
            if (orderBy == null) {
                topN = new TopNProjection(limit, 0);
            } else {
                topN = new TopNProjection(limit, 0,
                        contextBuilder.orderBy(),
                        orderBy.reverseFlags(),
                        orderBy.nullsFirst()
                );
            }
            topN.outputs(outputs);
            return topN;
        }
        return null;
    }

    private static boolean requireLimitOnReducer(QuerySpec querySpec, boolean aggregationsWrappedInScalar) {
        return (querySpec.limit() != null
                || querySpec.offset() > 0
                || aggregationsWrappedInScalar);
    }
}
