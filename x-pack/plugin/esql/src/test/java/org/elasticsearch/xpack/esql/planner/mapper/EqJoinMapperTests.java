/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.planner.mapper;

import org.elasticsearch.TransportVersion;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.test.TestBlockFactory;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.plan.logical.join.EqJoin;
import org.elasticsearch.xpack.esql.plan.logical.join.JoinTypes;
import org.elasticsearch.xpack.esql.plan.logical.local.LocalRelation;
import org.elasticsearch.xpack.esql.plan.logical.local.LocalSupplier;
import org.elasticsearch.xpack.esql.plan.physical.DistinctByExec;
import org.elasticsearch.xpack.esql.plan.physical.HashJoinExec;
import org.elasticsearch.xpack.esql.plan.physical.LocalSourceExec;
import org.elasticsearch.xpack.esql.plan.physical.PhysicalPlan;
import org.elasticsearch.xpack.esql.plan.physical.ProjectExec;
import org.elasticsearch.xpack.esql.session.Versioned;

import java.util.List;

import static org.elasticsearch.xpack.esql.EsqlTestUtils.as;
import static org.elasticsearch.xpack.esql.planner.mapper.Mapper.INTERN_JOIN_ON_LOOKUP_ORDINAL_PREFIX;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;

public class EqJoinMapperTests extends ESTestCase {

    public void testMapsUniqueEqJoinToDistinctByOverHashJoin() {
        EqJoin eqJoin = eqJoin(true);
        PhysicalPlan physical = new Mapper().map(new Versioned<>(eqJoin, TransportVersion.current()));

        ProjectExec project = as(physical, ProjectExec.class);
        assertThat(project.projections(), equalTo(eqJoin.output()));

        DistinctByExec distinctBy = as(project.child(), DistinctByExec.class);
        assertThat(distinctBy.failOnDuplicate(), equalTo(true));
        assertThat(distinctBy.key().name(), startsWith(Attribute.SYNTHETIC_ATTRIBUTE_NAME_PREFIX + INTERN_JOIN_ON_LOOKUP_ORDINAL_PREFIX));

        HashJoinExec join = as(distinctBy.child(), HashJoinExec.class);
        assertThat(join.joinType(), equalTo(JoinTypes.INNER));
        assertThat(join.addedFields(), hasItem(sameInstance(distinctBy.key())));
        assertThat(join.joinData(), instanceOf(LocalSourceExec.class));
    }

    public void testMapsNonUniqueEqJoinWithoutDistinctBy() {
        EqJoin eqJoin = eqJoin(false);
        PhysicalPlan physical = new Mapper().map(new Versioned<>(eqJoin, TransportVersion.current()));

        ProjectExec project = as(physical, ProjectExec.class);
        assertThat(project.projections(), equalTo(eqJoin.output()));

        HashJoinExec join = as(project.child(), HashJoinExec.class);
        assertThat(join.joinType(), equalTo(JoinTypes.INNER));
        assertThat(nonBuildAddedFields(join), hasItem(instanceOf(ReferenceAttribute.class)));
        assertThat(join.joinData(), instanceOf(LocalSourceExec.class));
    }

    private static List<Attribute> nonBuildAddedFields(HashJoinExec join) {
        var buildOutput = join.joinData().outputSet();
        return join.addedFields().stream().filter(f -> buildOutput.contains(f) == false).toList();
    }

    private static EqJoin eqJoin(boolean unique) {
        var blockFactory = TestBlockFactory.getNonBreakingInstance();
        ReferenceAttribute probeKey = new ReferenceAttribute(Source.EMPTY, "k", DataType.LONG);
        LocalRelation probe = new LocalRelation(
            Source.EMPTY,
            List.of(probeKey),
            LocalSupplier.of(new Page(blockFactory.newLongArrayVector(new long[] { 10, 20 }, 2).asBlock()))
        );
        ReferenceAttribute buildKey = new ReferenceAttribute(Source.EMPTY, "k", DataType.LONG);
        ReferenceAttribute buildValue = new ReferenceAttribute(Source.EMPTY, "bval", DataType.LONG);
        LocalRelation build = new LocalRelation(
            Source.EMPTY,
            List.of(buildKey, buildValue),
            LocalSupplier.of(
                new Page(
                    blockFactory.newLongArrayVector(new long[] { 10, 20 }, 2).asBlock(),
                    blockFactory.newLongArrayVector(new long[] { 100, 200 }, 2).asBlock()
                )
            )
        );
        return new EqJoin(Source.EMPTY, probe, build, List.of(probeKey), List.of(buildKey), List.of(buildValue), unique);
    }
}
