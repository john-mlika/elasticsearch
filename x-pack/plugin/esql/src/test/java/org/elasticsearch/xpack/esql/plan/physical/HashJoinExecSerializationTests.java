/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.physical;

import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.plan.logical.join.JoinType;
import org.elasticsearch.xpack.esql.plan.logical.join.JoinTypes;

import java.io.IOException;
import java.util.List;

public class HashJoinExecSerializationTests extends AbstractPhysicalPlanSerializationTests<HashJoinExec> {
    public static HashJoinExec randomHashJoinExec(int depth) {
        Source source = randomSource();
        PhysicalPlan child = randomChild(depth);
        LocalSourceExec joinData = LocalSourceExecSerializationTests.randomLocalSourceExec();
        List<Attribute> leftFields = randomFields();
        List<Attribute> rightFields = randomFields();
        List<Attribute> output = randomFields();
        // match ordinal (non-build added field) is coordinator-ephemeral; serialize build-side added fields only
        return new HashJoinExec(source, child, joinData, leftFields, rightFields, output, randomJoinType());
    }

    private static List<Attribute> randomFields() {
        return randomFieldAttributes(1, 5, false);
    }

    private static JoinType randomJoinType() {
        return randomFrom(JoinTypes.LEFT, JoinTypes.INNER);
    }

    @Override
    protected HashJoinExec createTestInstance() {
        return randomHashJoinExec(0);
    }

    @Override
    protected HashJoinExec mutateInstance(HashJoinExec instance) throws IOException {
        PhysicalPlan child = instance.left();
        PhysicalPlan joinData = instance.joinData();
        List<Attribute> leftFields = randomFieldAttributes(1, 5, false);
        List<Attribute> rightFields = randomFieldAttributes(1, 5, false);
        List<Attribute> output = randomFieldAttributes(1, 5, false);
        JoinType joinType = instance.joinType();
        switch (between(0, 5)) {
            case 0 -> child = randomValueOtherThan(child, () -> randomChild(0));
            case 1 -> joinData = randomValueOtherThan(joinData, LocalSourceExecSerializationTests::randomLocalSourceExec);
            case 2 -> leftFields = randomValueOtherThan(leftFields, HashJoinExecSerializationTests::randomFields);
            case 3 -> rightFields = randomValueOtherThan(rightFields, HashJoinExecSerializationTests::randomFields);
            case 4 -> output = randomValueOtherThan(output, HashJoinExecSerializationTests::randomFields);
            case 5 -> joinType = joinType == JoinTypes.LEFT ? JoinTypes.INNER : JoinTypes.LEFT;
            default -> throw new UnsupportedOperationException();
        }
        return new HashJoinExec(instance.source(), child, joinData, leftFields, rightFields, output, joinType);
    }

    @Override
    protected boolean alwaysEmptySource() {
        return true;
    }
}
