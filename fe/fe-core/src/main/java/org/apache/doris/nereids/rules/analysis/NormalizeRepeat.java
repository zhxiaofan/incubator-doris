// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.rules.analysis;

import org.apache.doris.nereids.exceptions.AnalysisException;
import org.apache.doris.nereids.rules.Rule;
import org.apache.doris.nereids.rules.RuleType;
import org.apache.doris.nereids.rules.rewrite.NormalizeToSlot.NormalizeToSlotContext;
import org.apache.doris.nereids.rules.rewrite.NormalizeToSlot.NormalizeToSlotTriplet;
import org.apache.doris.nereids.trees.expressions.Alias;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.NamedExpression;
import org.apache.doris.nereids.trees.expressions.OrderExpression;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.expressions.SlotReference;
import org.apache.doris.nereids.trees.expressions.VirtualSlotReference;
import org.apache.doris.nereids.trees.expressions.WindowExpression;
import org.apache.doris.nereids.trees.expressions.functions.agg.AggregateFunction;
import org.apache.doris.nereids.trees.expressions.functions.scalar.GroupingScalarFunction;
import org.apache.doris.nereids.trees.expressions.visitor.DefaultExpressionRewriter;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.algebra.Repeat;
import org.apache.doris.nereids.trees.plans.logical.LogicalAggregate;
import org.apache.doris.nereids.trees.plans.logical.LogicalProject;
import org.apache.doris.nereids.trees.plans.logical.LogicalRepeat;
import org.apache.doris.nereids.util.ExpressionUtils;
import org.apache.doris.nereids.util.PlanUtils.CollectNonWindowedAggFuncs;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** NormalizeRepeat
 * eg: select sum(b + 1), grouping(a+1) from t1 group by grouping sets ((b+1));
 * Original Plan:
 * LogicalRepeat ( groupingSets=[[(a#0 + 1)]],
 *                 outputExpressions=[sum((b#1 + 1)) AS `sum((b + 1))`#2,
 *                 Grouping((a#0 + 1)) AS `Grouping((a + 1))`#3] )
 *      +--LogicalOlapScan (t1)
 *
 * After:
 * LogicalAggregate[62] ( groupByExpr=[(a + 1)#4, GROUPING_ID#7, GROUPING_PREFIX_(a + 1)#6],
 *                        outputExpr=[sum((b + 1)#5) AS `sum((b + 1))`#2,
 *                                    GROUPING_PREFIX_(a + 1)#6 AS `GROUPING_PREFIX_(a + 1)`#3] )
 *    +--LogicalRepeat ( groupingSets=[[(a + 1)#4]],
 *                       outputExpressions=[(a + 1)#4,
 *                                          (b + 1)#5,
 *                                          GROUPING_ID#7,
 *                                          GROUPING_PREFIX_(a + 1)#6] )
 *      +--LogicalProject[60] ( projects=[(a#0 + 1) AS `(a + 1)`#4, (b#1 + 1) AS `(b + 1)`#5], excepts=[]
 *          +--LogicalOlapScan ( t1 )
 */
public class NormalizeRepeat extends OneAnalysisRuleFactory {
    @Override
    public Rule build() {
        return RuleType.NORMALIZE_REPEAT.build(
            logicalRepeat(any()).when(LogicalRepeat::canBindVirtualSlot).then(repeat -> {
                checkRepeatLegality(repeat);
                // add virtual slot, LogicalAggregate and LogicalProject for normalize
                LogicalAggregate<Plan> agg = normalizeRepeat(repeat);
                return dealSlotAppearBothInAggFuncAndGroupingSets(agg);
            })
        );
    }

    private void checkRepeatLegality(LogicalRepeat<Plan> repeat) {
        checkGroupingSetsSize(repeat);
    }

    private void checkGroupingSetsSize(LogicalRepeat<Plan> repeat) {
        Set<Expression> flattenGroupingSetExpr = ImmutableSet.copyOf(
                ExpressionUtils.flatExpressions(repeat.getGroupingSets()));
        if (flattenGroupingSetExpr.size() > LogicalRepeat.MAX_GROUPING_SETS_NUM) {
            throw new AnalysisException(
                    "Too many sets in GROUP BY clause, the max grouping sets item is "
                            + LogicalRepeat.MAX_GROUPING_SETS_NUM);
        }
    }

    private LogicalAggregate<Plan> normalizeRepeat(LogicalRepeat<Plan> repeat) {
        Set<Expression> needToSlots = collectNeedToSlotExpressions(repeat);
        NormalizeToSlotContext context = buildContext(repeat, needToSlots);

        // normalize grouping sets to List<List<Slot>>
        List<List<Slot>> normalizedGroupingSets = repeat.getGroupingSets()
                .stream()
                .map(groupingSet -> (List<Slot>) (List) context.normalizeToUseSlotRef(groupingSet))
                .collect(ImmutableList.toImmutableList());

        // replace the arguments of grouping scalar function to virtual slots
        // replace some complex expression to slot, e.g. `a + 1`
        List<NamedExpression> normalizedAggOutput = context.normalizeToUseSlotRef(
                        repeat.getOutputExpressions(), this::normalizeGroupingScalarFunction);

        Set<VirtualSlotReference> virtualSlotsInFunction =
                ExpressionUtils.collect(normalizedAggOutput, VirtualSlotReference.class::isInstance);

        List<VirtualSlotReference> allVirtualSlots = ImmutableList.<VirtualSlotReference>builder()
                // add the virtual grouping id slot
                .add(Repeat.generateVirtualGroupingIdSlot())
                // add other virtual slots in the grouping scalar functions
                .addAll(virtualSlotsInFunction)
                .build();

        Set<SlotReference> aggUsedNonVirtualSlots = ExpressionUtils.collect(
                normalizedAggOutput, expr -> expr.getClass().equals(SlotReference.class));

        Set<Slot> groupingSetsUsedSlot = ImmutableSet.copyOf(
                ExpressionUtils.flatExpressions(normalizedGroupingSets));

        SetView<SlotReference> aggUsedSlotInAggFunction
                = Sets.difference(aggUsedNonVirtualSlots, groupingSetsUsedSlot);

        List<Slot> normalizedRepeatOutput = ImmutableList.<Slot>builder()
                .addAll(groupingSetsUsedSlot)
                .addAll(aggUsedSlotInAggFunction)
                .addAll(allVirtualSlots)
                .build();

        Set<NamedExpression> pushedProject = context.pushDownToNamedExpression(needToSlots);
        Plan normalizedChild = pushDownProject(pushedProject, repeat.child());

        LogicalRepeat<Plan> normalizedRepeat = repeat.withNormalizedExpr(
                (List) normalizedGroupingSets, (List) normalizedRepeatOutput, normalizedChild);

        List<Expression> normalizedAggGroupBy = ImmutableList.<Expression>builder()
                .addAll(groupingSetsUsedSlot)
                .addAll(allVirtualSlots)
                .build();
        return new LogicalAggregate<>(normalizedAggGroupBy, (List) normalizedAggOutput,
                Optional.of(normalizedRepeat), normalizedRepeat);
    }

    private Set<Expression> collectNeedToSlotExpressions(LogicalRepeat<Plan> repeat) {
        // 3 parts need push down:
        // flattenGroupingSetExpr, argumentsOfGroupingScalarFunction, argumentsOfAggregateFunction

        Set<Expression> flattenGroupingSetExpr = ImmutableSet.copyOf(
                ExpressionUtils.flatExpressions(repeat.getGroupingSets()));

        Set<GroupingScalarFunction> groupingScalarFunctions = ExpressionUtils.collect(
                repeat.getOutputExpressions(), GroupingScalarFunction.class::isInstance);

        ImmutableSet<Expression> argumentsOfGroupingScalarFunction = groupingScalarFunctions.stream()
                .flatMap(function -> function.getArguments().stream())
                .collect(ImmutableSet.toImmutableSet());

        List<AggregateFunction> aggregateFunctions = CollectNonWindowedAggFuncs.collect(repeat.getOutputExpressions());

        ImmutableSet<Expression> argumentsOfAggregateFunction = aggregateFunctions.stream()
                .flatMap(function -> function.getArguments().stream().map(arg -> {
                    if (arg instanceof OrderExpression) {
                        return arg.child(0);
                    } else {
                        return arg;
                    }
                }))
                .collect(ImmutableSet.toImmutableSet());

        ImmutableSet<Expression> needPushDown = ImmutableSet.<Expression>builder()
                // grouping sets should be pushed down, e.g. grouping sets((k + 1)),
                // we should push down the `k + 1` to the bottom plan
                .addAll(flattenGroupingSetExpr)
                // e.g. grouping_id(k + 1), we should push down the `k + 1` to the bottom plan
                .addAll(argumentsOfGroupingScalarFunction)
                // e.g. sum(k + 1), we should push down the `k + 1` to the bottom plan
                .addAll(argumentsOfAggregateFunction)
                .build();
        return needPushDown;
    }

    private Plan pushDownProject(Set<NamedExpression> pushedExprs, Plan originBottomPlan) {
        if (!pushedExprs.equals(originBottomPlan.getOutputSet()) && !pushedExprs.isEmpty()) {
            return new LogicalProject<>(ImmutableList.copyOf(pushedExprs), originBottomPlan);
        }
        return originBottomPlan;
    }

    /** buildContext */
    public NormalizeToSlotContext buildContext(Repeat<? extends Plan> repeat,
            Set<? extends Expression> sourceExpressions) {
        Set<Alias> aliases = ExpressionUtils.collect(repeat.getOutputExpressions(), Alias.class::isInstance);
        Map<Expression, Alias> existsAliasMap = Maps.newLinkedHashMap();
        for (Alias existsAlias : aliases) {
            existsAliasMap.put(existsAlias.child(), existsAlias);
        }

        List<Expression> groupingSetExpressions = ExpressionUtils.flatExpressions(repeat.getGroupingSets());
        Map<Expression, NormalizeToSlotTriplet> normalizeToSlotMap = Maps.newLinkedHashMap();
        for (Expression expression : sourceExpressions) {
            Optional<NormalizeToSlotTriplet> pushDownTriplet;
            if (groupingSetExpressions.contains(expression)) {
                pushDownTriplet = toGroupingSetExpressionPushDownTriplet(expression, existsAliasMap.get(expression));
            } else {
                pushDownTriplet = Optional.of(
                        NormalizeToSlotTriplet.toTriplet(expression, existsAliasMap.get(expression)));
            }

            if (pushDownTriplet.isPresent()) {
                normalizeToSlotMap.put(expression, pushDownTriplet.get());
            }
        }
        return new NormalizeToSlotContext(normalizeToSlotMap);
    }

    private Optional<NormalizeToSlotTriplet> toGroupingSetExpressionPushDownTriplet(
            Expression expression, @Nullable Alias existsAlias) {
        NormalizeToSlotTriplet originTriplet = NormalizeToSlotTriplet.toTriplet(expression, existsAlias);
        SlotReference remainSlot = (SlotReference) originTriplet.remainExpr;
        Slot newSlot = remainSlot.withNullable(true);
        return Optional.of(new NormalizeToSlotTriplet(expression, newSlot, originTriplet.pushedExpr));
    }

    private Expression normalizeGroupingScalarFunction(NormalizeToSlotContext context, Expression expr) {
        if (expr instanceof GroupingScalarFunction) {
            GroupingScalarFunction function = (GroupingScalarFunction) expr;
            List<Expression> normalizedRealExpressions = context.normalizeToUseSlotRef(function.getArguments());
            function = function.withChildren(normalizedRealExpressions);
            // eliminate GroupingScalarFunction and replace to VirtualSlotReference
            return Repeat.generateVirtualSlotByFunction(function);
        } else {
            return expr;
        }
    }

    /*
     * compute slots that appear both in agg func and grouping sets,
     * copy the slots and output in the project below the repeat as new copied slots,
     * and refer the new copied slots in aggregate parameters.
     * eg: original plan after normalizedRepeat
     * LogicalAggregate (groupByExpr=[a#0, GROUPING_ID#1], outputExpr=[a#0, GROUPING_ID#1, sum(a#0) as `sum(a)`#2])
     *   +--LogicalRepeat (groupingSets=[[a#0]], outputExpr=[a#0, GROUPING_ID#1]
     *      +--LogicalProject (projects =[a#0])
     * After:
     * LogicalAggregate (groupByExpr=[a#0, GROUPING_ID#1], outputExpr=[a#0, GROUPING_ID#1, sum(a#3) as `sum(a)`#2])
     *   +--LogicalRepeat (groupingSets=[[a#0]], outputExpr=[a#0, a#3, GROUPING_ID#1]
     *      +--LogicalProject (projects =[a#0, a#0 as `a`#3])
     */
    private LogicalAggregate<Plan> dealSlotAppearBothInAggFuncAndGroupingSets(
            @NotNull LogicalAggregate<Plan> aggregate) {
        LogicalRepeat<Plan> repeat = (LogicalRepeat<Plan>) aggregate.child();

        List<AggregateFunction> aggregateFunctions =
                CollectNonWindowedAggFuncs.collect(aggregate.getOutputExpressions());
        Set<Slot> aggUsedSlots = aggregateFunctions.stream()
                .flatMap(e -> e.<Set<SlotReference>>collect(SlotReference.class::isInstance).stream())
                .collect(ImmutableSet.toImmutableSet());
        Set<Slot> groupingSetsUsedSlot = repeat.getGroupingSets().stream()
                .flatMap(Collection::stream)
                .flatMap(e -> e.<Set<SlotReference>>collect(SlotReference.class::isInstance).stream())
                .collect(Collectors.toSet());

        Set<Slot> resSet = new HashSet<>(aggUsedSlots);
        resSet.retainAll(groupingSetsUsedSlot);
        if (resSet.isEmpty()) {
            return aggregate;
        }
        Map<Slot, Alias> slotMapping = resSet.stream().collect(
                Collectors.toMap(key -> key, Alias::new)
        );
        Set<Alias> newAliases = new HashSet<>(slotMapping.values());
        List<Slot> newSlots = newAliases.stream()
                .map(Alias::toSlot)
                .collect(Collectors.toList());

        // modify repeat child to a new project with more projections
        List<Slot> originSlots = repeat.child().getOutput();
        ImmutableList<NamedExpression> immList =
                ImmutableList.<NamedExpression>builder().addAll(originSlots).addAll(newAliases).build();
        LogicalProject<Plan> newProject = new LogicalProject<>(immList, repeat.child());
        repeat = repeat.withChildren(ImmutableList.of(newProject));

        // modify repeat outputs
        List<Slot> originRepeatSlots = repeat.getOutput();
        repeat = repeat.withAggOutput(ImmutableList
                .<NamedExpression>builder()
                .addAll(originRepeatSlots.stream().filter(slot -> ! (slot instanceof VirtualSlotReference))
                        .collect(Collectors.toList()))
                .addAll(newSlots)
                .addAll(originRepeatSlots.stream().filter(slot -> (slot instanceof VirtualSlotReference))
                        .collect(Collectors.toList()))
                .build());
        aggregate = aggregate.withChildren(ImmutableList.of(repeat));

        List<NamedExpression> newOutputExpressions = aggregate.getOutputExpressions().stream()
                .map(e -> (NamedExpression) e.accept(RewriteAggFuncWithoutWindowAggFunc.INSTANCE,
                        slotMapping))
                .collect(Collectors.toList());
        return aggregate.withAggOutput(newOutputExpressions);
    }

    /**
     * This class use the map(slotMapping) to rewrite all slots in trival-agg.
     * The purpose of this class is to only rewrite the slots in trival-agg and not to rewrite the slots in window-agg.
     */
    private static class RewriteAggFuncWithoutWindowAggFunc
            extends DefaultExpressionRewriter<Map<Slot, Alias>> {

        private static final RewriteAggFuncWithoutWindowAggFunc
                INSTANCE = new RewriteAggFuncWithoutWindowAggFunc();

        private RewriteAggFuncWithoutWindowAggFunc() {}

        @Override
        public Expression visitAggregateFunction(AggregateFunction aggregateFunction, Map<Slot, Alias> slotMapping) {
            return aggregateFunction.rewriteDownShortCircuit(e -> {
                if (e instanceof Slot && slotMapping.containsKey(e)) {
                    return slotMapping.get(e).toSlot();
                }
                return e;
            });
        }

        @Override
        public Expression visitWindow(WindowExpression windowExpression, Map<Slot, Alias> slotMapping) {
            List<Expression> newChildren = new ArrayList<>();
            Expression function = windowExpression.getFunction();
            Expression oldFuncChild = function.child(0);
            boolean hasNewChildren = false;
            if (oldFuncChild != null) {
                Expression newFuncChild;
                newFuncChild = function.child(0).accept(this, slotMapping);
                hasNewChildren = (newFuncChild != oldFuncChild);
                newChildren.add(hasNewChildren
                        ? function.withChildren(ImmutableList.of(newFuncChild)) : function);
            } else {
                newChildren.add(function);
            }
            for (Expression partitionKey : windowExpression.getPartitionKeys()) {
                Expression newChild = partitionKey.accept(this, slotMapping);
                if (newChild != partitionKey) {
                    hasNewChildren = true;
                }
                newChildren.add(newChild);
            }
            for (Expression orderKey : windowExpression.getOrderKeys()) {
                Expression newChild = orderKey.accept(this, slotMapping);
                if (newChild != orderKey) {
                    hasNewChildren = true;
                }
                newChildren.add(newChild);
            }
            if (windowExpression.getWindowFrame().isPresent()) {
                newChildren.add(windowExpression.getWindowFrame().get());
            }
            return hasNewChildren ? windowExpression.withChildren(newChildren) : windowExpression;
        }
    }
}
