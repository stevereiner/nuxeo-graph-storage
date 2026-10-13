package org.integratedsemantics.nuxeo.graph.arcadedb;

import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_MIXIN_TYPES;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_PRIMARY_TYPE;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_READ_ACL;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.nuxeo.ecm.core.storage.dbs.DBSExpressionEvaluator;
import org.integratedsemantics.nuxeo.graph.GraphQueryTranslator;

/**
 * Translates NXQL into ArcadeDB SQL conditions on the document vertex.
 * <p>
 * Complex values are embedded maps: {@code file:content/name} is {@code content.name}, an element of a list is
 * {@code files[0].file.name}, and "any element" uses {@code files CONTAINS (file.name = :p0)}.
 * <p>
 * ArcadeDB uses two-valued logic where {@code x <> 'a'} and {@code NOT (x = 'a')} match a missing {@code x}, so
 * inequalities and negations are guarded by {@code x IS NOT NULL} to get the NXQL ternary semantics.
 *
 * @since 0.1.0
 */
public class ArcadeDBQueryTranslator extends GraphQueryTranslator<SqlCondition> {

    protected int paramCount;

    public ArcadeDBQueryTranslator(DBSExpressionEvaluator evaluator) {
        super(evaluator);
    }

    /** Escapes a property name: {@code `ecm:id`}. */
    public static String name(String key) {
        return "`" + key.replace("`", "\\`") + "`";
    }

    protected String param(Map<String, Object> params, Object value) {
        var name = "p" + paramCount++;
        params.put(name, value);
        return ":" + name;
    }

    @Override
    protected Object convertValue(Object value) {
        if (value instanceof ZonedDateTime zdt) {
            return zdt.toInstant().toEpochMilli(); // dates are stored as epoch millis
        }
        return value;
    }

    @Override
    protected SqlCondition and(List<SqlCondition> conditions) {
        return SqlCondition.combine("AND", conditions);
    }

    @Override
    protected SqlCondition or(List<SqlCondition> conditions) {
        return SqlCondition.combine("OR", conditions);
    }

    @Override
    protected SqlCondition not(SqlCondition condition) {
        return condition.not();
    }

    @Override
    protected SqlCondition alwaysFalse() {
        return new SqlCondition("(1 = 0)", null, Map.of());
    }

    @Override
    protected SqlCondition readAcl(Set<String> principals) {
        var params = new java.util.HashMap<String, Object>();
        var sql = name(KEY_READ_ACL) + " CONTAINSANY " + param(params, new ArrayList<>(principals));
        return new SqlCondition(sql, null, params);
    }

    @Override
    protected SqlCondition mixin(MixinSpec spec) {
        var params = new java.util.HashMap<String, Object>();
        var types = name(KEY_PRIMARY_TYPE) + " IN " + param(params, new ArrayList<>(spec.primaryTypes()));
        if (spec.instanceMixins().isEmpty()) {
            return new SqlCondition(types, null, params);
        }
        // a missing ecm:mixinTypes doesn't contain anything: two-valued logic is what we want here
        var mixins = name(KEY_MIXIN_TYPES) + " CONTAINSANY " + param(params, new ArrayList<>(spec.instanceMixins()));
        var sql = spec.include() ? "(" + types + " OR " + mixins + ")"
                : "(" + types + " AND NOT (" + mixins + "))";
        return new SqlCondition(sql, null, params);
    }

    @Override
    protected SqlCondition predicate(Target target, Op op, Object value) {
        var params = new java.util.HashMap<String, Object>();
        var steps = target.steps();
        // find the last "any element" step: the condition is evaluated inside CONTAINS (...) from there
        var sql = build(steps, 0, op, value, params);
        String guard = null;
        // ops that are never null (a missing array is an empty array) don't need a guard
        if (!target.hasWildcard() && op != Op.IS_NULL && op != Op.IS_NOT_NULL && op != Op.IS_TRUE
                && op != Op.IS_FALSE_OR_NULL && op != Op.CONTAINS && op != Op.CONTAINS_ANY) {
            guard = path(steps, 0, steps.size()) + " IS NOT NULL";
        }
        return new SqlCondition(sql, guard, params);
    }

    /** The dotted path of steps {@code [from, to)}, with list positions. */
    protected String path(List<Step> steps, int from, int to) {
        var sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            var step = steps.get(i);
            if (i > from) {
                sb.append('.');
            }
            sb.append(name(step.key()));
            if (step.listElement() && step.index() != null) {
                sb.append('[').append(step.index()).append(']');
            }
        }
        return sb.toString();
    }

    protected String build(List<Step> steps, int from, Op op, Object value, Map<String, Object> params) {
        for (int i = from; i < steps.size() - 1; i++) {
            var step = steps.get(i);
            if (step.listElement() && step.index() == null) {
                // any element: list CONTAINS (condition relative to the element)
                return path(steps, from, i + 1) + " CONTAINS (" + build(steps, i + 1, op, value, params) + ")";
            }
        }
        return leaf(path(steps, from, steps.size()), op, value, params);
    }

    protected String leaf(String p, Op op, Object value, Map<String, Object> params) {
        return switch (op) {
            case EQ -> p + " = " + param(params, value);
            case NE -> "(" + p + " IS NOT NULL AND " + p + " <> " + param(params, value) + ")";
            case LT -> p + " < " + param(params, value);
            case GT -> p + " > " + param(params, value);
            case LTEQ -> p + " <= " + param(params, value);
            case GTEQ -> p + " >= " + param(params, value);
            case IN -> p + " IN " + param(params, value);
            case NOT_IN -> "(" + p + " IS NOT NULL AND NOT (" + p + " IN " + param(params, value) + "))";
            case MATCHES -> p + " MATCHES " + param(params, value);
            case IS_NULL -> p + " IS NULL";
            case IS_NOT_NULL -> p + " IS NOT NULL";
            case IS_TRUE -> p + " = true";
            case IS_FALSE_OR_NULL -> "(" + p + " IS NULL OR " + p + " = false)";
            case CONTAINS -> p + " CONTAINS " + param(params, value);
            case CONTAINS_ANY -> p + " CONTAINSANY " + param(params, value);
        };
    }

}
