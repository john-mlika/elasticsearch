/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.physical;

import org.elasticsearch.TransportVersion;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.AttributeSet;
import org.elasticsearch.xpack.esql.core.expression.Expressions;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.io.stream.PlanStreamInput;
import org.elasticsearch.xpack.esql.plan.logical.join.JoinType;
import org.elasticsearch.xpack.esql.plan.logical.join.JoinTypes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.elasticsearch.xpack.esql.expression.NamedExpressions.mergeOutputAttributes;

/**
 * Broadcast hash join over a materialized build side. {@link #joinType()} selects match semantics
 * ({@link JoinTypes#LEFT} null-fills on miss; {@link JoinTypes#INNER} drops unmatched probe rows).
 * {@link #addedFields()} are columns added to the join output.
 */
public class HashJoinExec extends BinaryExec implements EstimatesRowSize {
    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(
        PhysicalPlan.class,
        "HashJoinExec",
        HashJoinExec::new
    );
    private static final TransportVersion ESQL_LOOKUP_JOIN_ON_EXPRESSION = TransportVersion.fromName("esql_lookup_join_on_expression");
    private static final TransportVersion ESQL_HASH_JOIN_INNER_UNIQUE = TransportVersion.fromName("esql_hash_join_inner_unique");

    private final List<Attribute> leftFields;
    private final List<Attribute> rightFields;
    private final List<Attribute> addedFields;
    private final JoinType joinType;
    private List<Attribute> lazyOutput;
    private AttributeSet lazyAddedFields;

    /**
     * LEFT outer hash join.
     */
    public HashJoinExec(
        Source source,
        PhysicalPlan left,
        PhysicalPlan hashData,
        List<Attribute> leftFields,
        List<Attribute> rightFields,
        List<Attribute> addedFields
    ) {
        this(source, left, hashData, leftFields, rightFields, addedFields, JoinTypes.LEFT);
    }

    public HashJoinExec(
        Source source,
        PhysicalPlan left,
        PhysicalPlan hashData,
        List<Attribute> leftFields,
        List<Attribute> rightFields,
        List<Attribute> addedFields,
        JoinType joinType
    ) {
        super(source, left, hashData);
        this.leftFields = leftFields;
        this.rightFields = rightFields;
        this.addedFields = addedFields;
        this.joinType = joinType;
    }

    private HashJoinExec(StreamInput in) throws IOException {
        super(Source.readFrom((PlanStreamInput) in), in.readNamedWriteable(PhysicalPlan.class), in.readNamedWriteable(PhysicalPlan.class));
        if (in.getTransportVersion().supports(ESQL_LOOKUP_JOIN_ON_EXPRESSION) == false) {
            in.readNamedWriteableCollectionAsList(Attribute.class);
        }
        this.leftFields = in.readNamedWriteableCollectionAsList(Attribute.class);
        this.rightFields = in.readNamedWriteableCollectionAsList(Attribute.class);
        this.addedFields = in.readNamedWriteableCollectionAsList(Attribute.class);
        if (in.getTransportVersion().supports(ESQL_HASH_JOIN_INNER_UNIQUE)) {
            this.joinType = JoinTypes.readFrom(in);
        } else {
            this.joinType = JoinTypes.LEFT;
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        if (out.getTransportVersion().supports(ESQL_LOOKUP_JOIN_ON_EXPRESSION) == false) {
            out.writeNamedWriteableCollection(leftFields);
        }
        out.writeNamedWriteableCollection(leftFields);
        out.writeNamedWriteableCollection(rightFields);
        out.writeNamedWriteableCollection(addedFields);
        if (out.getTransportVersion().supports(ESQL_HASH_JOIN_INNER_UNIQUE)) {
            joinType.writeTo(out);
        }
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    public PhysicalPlan joinData() {
        return right();
    }

    public List<Attribute> leftFields() {
        return leftFields;
    }

    public List<Attribute> rightFields() {
        return rightFields;
    }

    public Set<Attribute> addedFields() {
        if (lazyAddedFields == null) {
            lazyAddedFields = AttributeSet.of(addedFields);
        }
        return lazyAddedFields;
    }

    public JoinType joinType() {
        return joinType;
    }

    @Override
    public PhysicalPlan estimateRowSize(State state) {
        state.add(false, addedFields);
        return this;
    }

    @Override
    public List<Attribute> output() {
        if (lazyOutput == null) {
            List<Attribute> leftOutputWithoutKeys = left().output().stream().filter(attr -> leftFields.contains(attr) == false).toList();
            List<Attribute> rightWithAppendedKeys = new ArrayList<>(right().output());
            rightWithAppendedKeys.removeAll(rightFields);
            rightWithAppendedKeys.addAll(leftFields);

            lazyOutput = new ArrayList<>(mergeOutputAttributes(rightWithAppendedKeys, leftOutputWithoutKeys));
            for (Attribute f : addedFields) {
                if (lazyOutput.contains(f) == false) {
                    lazyOutput.add(f);
                }
            }
        }
        return lazyOutput;
    }

    @Override
    public AttributeSet inputSet() {
        // TODO: this is a hack until qualifiers land since the right side is always materialized
        return left().outputSet();
    }

    @Override
    protected AttributeSet computeReferences() {
        return Expressions.references(leftFields);
    }

    @Override
    public AttributeSet leftReferences() {
        return Expressions.references(leftFields);
    }

    @Override
    public AttributeSet rightReferences() {
        return Expressions.references(rightFields);
    }

    @Override
    public HashJoinExec replaceChildren(PhysicalPlan left, PhysicalPlan right) {
        return new HashJoinExec(source(), left, right, leftFields, rightFields, addedFields, joinType);
    }

    @Override
    protected NodeInfo<? extends PhysicalPlan> info() {
        return NodeInfo.create(this, HashJoinExec::new, left(), right(), leftFields, rightFields, addedFields, joinType);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (super.equals(o) == false) {
            return false;
        }
        HashJoinExec hash = (HashJoinExec) o;
        return joinType.equals(hash.joinType)
            && leftFields.equals(hash.leftFields)
            && rightFields.equals(hash.rightFields)
            && addedFields.equals(hash.addedFields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), leftFields, rightFields, addedFields, joinType);
    }
}
