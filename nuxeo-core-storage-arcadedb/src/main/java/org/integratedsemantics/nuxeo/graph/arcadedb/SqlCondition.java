package org.integratedsemantics.nuxeo.graph.arcadedb;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An ArcadeDB SQL condition with its named parameters.
 *
 * @param sql the SQL condition
 * @param guard if not {@code null}, a condition that is false exactly when NXQL ternary logic would evaluate
 *            {@code sql} to null (ArcadeDB uses two-valued logic where a missing property makes {@code <>} and
 *            {@code NOT} true); used to negate the condition with NXQL semantics
 * @param params the named parameters
 * @since 0.1.0
 */
public record SqlCondition(String sql, String guard, Map<String, Object> params) {

    public static SqlCondition combine(String operator, List<SqlCondition> conditions) {
        var params = new HashMap<String, Object>();
        var sb = new StringBuilder("(");
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) {
                sb.append(' ').append(operator).append(' ');
            }
            sb.append(conditions.get(i).sql());
            params.putAll(conditions.get(i).params());
        }
        sb.append(')');
        return new SqlCondition(sb.toString(), null, params);
    }

    public SqlCondition not() {
        var negated = "NOT (" + sql + ")";
        return new SqlCondition(guard == null ? negated : "(" + guard + " AND " + negated + ")", null, params);
    }

    /** Returns this condition AND another one. */
    public SqlCondition and(String otherSql, Map<String, Object> otherParams) {
        var all = new HashMap<>(params);
        all.putAll(otherParams);
        return new SqlCondition("(" + sql + " AND " + otherSql + ")", null, all);
    }

}
