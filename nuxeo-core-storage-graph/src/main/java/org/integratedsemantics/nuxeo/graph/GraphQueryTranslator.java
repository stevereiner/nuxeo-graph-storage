package org.integratedsemantics.nuxeo.graph;

import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_ANCESTOR_IDS;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_HAS_LEGAL_HOLD;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_ID;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_IS_CHECKED_IN;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_IS_FLEXIBLE_RECORD;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_IS_LATEST_MAJOR_VERSION;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_IS_LATEST_VERSION;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_IS_PROXY;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_IS_RECORD;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_IS_RETENTION_ACTIVE;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_IS_TRASHED;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_IS_VERSION;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_LIFECYCLE_POLICY;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_LIFECYCLE_STATE;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_MAJOR_VERSION;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_MINOR_VERSION;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_NAME;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_PARENT_ID;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_POS;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_PRIMARY_TYPE;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_PROXY_TARGET_ID;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_PROXY_VERSION_SERIES_ID;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_RETAIN_UNTIL;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_VERSION_CREATED;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_VERSION_DESCRIPTION;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_VERSION_LABEL;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_VERSION_SERIES_ID;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.nuxeo.ecm.core.query.QueryParseException;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.ecm.core.query.sql.model.BooleanLiteral;
import org.nuxeo.ecm.core.query.sql.model.DateLiteral;
import org.nuxeo.ecm.core.query.sql.model.DoubleLiteral;
import org.nuxeo.ecm.core.query.sql.model.Expression;
import org.nuxeo.ecm.core.query.sql.model.IntegerLiteral;
import org.nuxeo.ecm.core.query.sql.model.Literal;
import org.nuxeo.ecm.core.query.sql.model.LiteralList;
import org.nuxeo.ecm.core.query.sql.model.MultiExpression;
import org.nuxeo.ecm.core.query.sql.model.Operand;
import org.nuxeo.ecm.core.query.sql.model.Operator;
import org.nuxeo.ecm.core.query.sql.model.OrderByClause;
import org.nuxeo.ecm.core.query.sql.model.Reference;
import org.nuxeo.ecm.core.query.sql.model.StringLiteral;
import org.nuxeo.ecm.core.schema.SchemaManager;
import org.nuxeo.ecm.core.schema.types.ComplexType;
import org.nuxeo.ecm.core.schema.types.Field;
import org.nuxeo.ecm.core.schema.types.ListType;
import org.nuxeo.ecm.core.schema.types.SimpleType;
import org.nuxeo.ecm.core.schema.types.Type;
import org.nuxeo.ecm.core.schema.types.primitives.BooleanType;
import org.nuxeo.ecm.core.schema.types.primitives.DateType;
import org.nuxeo.ecm.core.schema.types.primitives.DoubleType;
import org.nuxeo.ecm.core.schema.types.primitives.IntegerType;
import org.nuxeo.ecm.core.schema.types.primitives.LongType;
import org.nuxeo.ecm.core.schema.types.primitives.StringType;
import org.nuxeo.ecm.core.storage.dbs.DBSExpressionEvaluator;
import org.nuxeo.ecm.core.storage.dbs.DBSSession;
import org.nuxeo.runtime.api.Framework;

/**
 * Translates a NXQL query into a backend condition {@code C}, independently of the backend query language.
 * <p>
 * The translation is a <em>superset</em> of the NXQL semantics: what cannot be translated is dropped from
 * conjunctions, and makes disjunctions and negations untranslatable. Each translated condition is also flagged as
 * <em>exact</em> when it matches exactly what the Nuxeo {@link DBSExpressionEvaluator} matches. When the whole query is
 * exact, the backend can apply ORDER BY, OFFSET, LIMIT and count itself instead of evaluating the documents in Java.
 * <p>
 * Exactness relies on the evaluator using SQL-like ternary logic ({@code null = x} is null, {@code NOT null} is null),
 * like Cypher and SQL do, with these exceptions handled here:
 * <ul>
 * <li>{@code ecm:} booleans are false when null,</li>
 * <li>the evaluator's AND/OR are not commutative with null, so a NOT is only translated over a single predicate,</li>
 * <li>a NOT over a wildcard path ({@code files/*}) has "exists an element that doesn't match" semantics, so it is not
 * translated.</li>
 * </ul>
 * Backends implement the condition constructors: {@link #and}, {@link #or}, {@link #not}, {@link #predicate},
 * {@link #mixin}, {@link #readAcl} and {@link #alwaysFalse}.
 *
 * @param <C> the backend condition type
 * @since 0.1.0
 */
public abstract class GraphQueryTranslator<C> {

    /** The kind of value stored for a key. */
    public enum Kind {
        STRING, LONG, DOUBLE, BOOLEAN, DATE
    }

    /**
     * One step of a property path.
     *
     * @param key the stored key
     * @param listElement whether the key holds a list of complex values, of which this step selects elements
     * @param index for a list element step, the position of the element, or {@code null} for any element
     */
    public record Step(String key, boolean listElement, Long index) {
    }

    /**
     * A resolved property: its path from the document (the last step is the scalar key), the kind of its values, and
     * whether it holds an array of scalars.
     */
    public record Target(List<Step> steps, Kind kind, boolean array) {

        /** Whether the property is directly on the document (not inside a complex value). */
        public boolean isTopLevel() {
            return steps.size() == 1;
        }

        /** The key of the scalar value (last step). */
        public String leafKey() {
            return steps.get(steps.size() - 1).key();
        }

        /** Whether the path goes through "any element" of a list. */
        public boolean hasWildcard() {
            return steps.stream().anyMatch(s -> s.listElement() && s.index() == null);
        }

        public static Target of(String key, Kind kind, boolean array) {
            return new Target(List.of(new Step(key, false, null)), kind, array);
        }
    }

    /** Predicate operators passed to {@link #predicate}. */
    public enum Op {
        /** {@code value} is a scalar. */
        EQ, NE, LT, GT, LTEQ, GTEQ,
        /** {@code value} is a list of scalars. */
        IN, NOT_IN,
        /** {@code value} is a Java regex (full match). */
        MATCHES,
        /** {@code value} is unused. */
        IS_NULL, IS_NOT_NULL,
        /** For {@code ecm:} booleans: true / false or null. {@code value} is unused. */
        IS_TRUE, IS_FALSE_OR_NULL,
        /**
         * For arrays: contains the scalar {@code value}. Never null: like the evaluator, a missing array is an empty
         * array (so its negation matches documents without the array).
         */
        CONTAINS,
        /** For arrays: contains any of the list of scalars {@code value}. Never null, like {@link #CONTAINS}. */
        CONTAINS_ANY
    }

    /**
     * An {@code ecm:mixinType} predicate: when {@code include}, the primary type is one of {@code primaryTypes} or the
     * instance mixins contain one of {@code instanceMixins}; otherwise, the primary type is one of
     * {@code primaryTypes} and the instance mixins contain none of {@code instanceMixins}. Never null.
     */
    public record MixinSpec(Set<String> primaryTypes, Set<String> instanceMixins, boolean include) {
    }

    /** A sort key on a top-level scalar property. */
    public record SortKey(String key, boolean descending) {
    }

    /**
     * The result of the translation.
     *
     * @param where the condition, {@code null} for no condition
     * @param exact whether {@code where} matches exactly the query (otherwise it's a superset)
     * @param sort the ORDER BY translation, empty for no order, {@code null} if not translatable
     */
    public record Translation<C>(C where, boolean exact, List<SortKey> sort) {
    }

    /** A translated condition. */
    protected record Cond<C>(C c, boolean exact, boolean negatable) {
    }

    protected static final Set<String> ECM_BOOLEAN_KEYS = Set.of(KEY_IS_PROXY, KEY_IS_VERSION, KEY_IS_CHECKED_IN,
            KEY_IS_LATEST_VERSION, KEY_IS_LATEST_MAJOR_VERSION, KEY_IS_TRASHED, KEY_IS_RECORD, KEY_IS_FLEXIBLE_RECORD,
            KEY_HAS_LEGAL_HOLD, KEY_IS_RETENTION_ACTIVE);

    protected static final Set<String> ECM_STRING_KEYS = Set.of(KEY_ID, KEY_NAME, KEY_PARENT_ID, KEY_PRIMARY_TYPE,
            KEY_LIFECYCLE_STATE, KEY_LIFECYCLE_POLICY, KEY_PROXY_TARGET_ID, KEY_PROXY_VERSION_SERIES_ID,
            KEY_VERSION_SERIES_ID, KEY_VERSION_LABEL, KEY_VERSION_DESCRIPTION);

    protected static final Set<String> ECM_LONG_KEYS = Set.of(KEY_POS, KEY_MAJOR_VERSION, KEY_MINOR_VERSION);

    protected static final Set<String> ECM_DATE_KEYS = Set.of(KEY_VERSION_CREATED, KEY_RETAIN_UNTIL);

    protected static final Pattern INDEX = Pattern.compile("\\d+");

    protected final DBSExpressionEvaluator evaluator;

    protected final SchemaManager schemaManager;

    protected GraphQueryTranslator(DBSExpressionEvaluator evaluator) {
        this.evaluator = evaluator;
        schemaManager = Framework.getService(SchemaManager.class);
    }

    // ----- backend condition constructors -----

    /** Conjunction of at least one condition. */
    protected abstract C and(List<C> conditions);

    /** Disjunction of at least one condition. */
    protected abstract C or(List<C> conditions);

    /** Negation (ternary logic). */
    protected abstract C not(C condition);

    /** A condition that is always false. */
    protected abstract C alwaysFalse();

    /** A predicate on a property. */
    protected abstract C predicate(Target target, Op op, Object value);

    /** An {@code ecm:mixinType} predicate. */
    protected abstract C mixin(MixinSpec spec);

    /** The security check: the document's read ACL ({@code ecm:racl}) contains one of the principals. */
    protected abstract C readAcl(Set<String> principals);

    /**
     * Converts a query value to the stored representation (for instance dates). Called for all values passed to
     * {@link #predicate}.
     */
    protected Object convertValue(Object value) {
        return value;
    }

    // ----- translation -----

    /**
     * Translates the evaluator's query. The evaluator must have been parsed.
     *
     * @param orderByClause the ORDER BY to translate, or {@code null}
     */
    public Translation<C> translate(OrderByClause orderByClause) {
        C where = null;
        boolean exact = true;
        var expression = evaluator.getExpression();
        if (expression != null) {
            var cond = walk(expression);
            if (cond == null) {
                exact = false;
            } else {
                where = cond.c();
                exact = cond.exact();
            }
        }
        if (evaluator.principals != null) {
            var acl = readAcl(evaluator.principals);
            where = where == null ? acl : and(List.of(where, acl));
        }
        return new Translation<>(where, exact, translateOrderBy(orderByClause));
    }

    protected List<SortKey> translateOrderBy(OrderByClause orderByClause) {
        if (orderByClause == null) {
            return List.of();
        }
        var sort = new ArrayList<SortKey>();
        for (var ob : orderByClause.elements) {
            var ref = ob.reference;
            if (ref.cast != null || NXQL.ECM_PATH.equals(ref.name)) {
                return null;
            }
            var target = resolve(ref.name);
            if (target == null || !target.isTopLevel() || target.array() || target.kind() == Kind.BOOLEAN) {
                return null;
            }
            sort.add(new SortKey(target.leafKey(), ob.isDescending));
        }
        return sort;
    }

    protected Cond<C> walk(Expression expr) {
        if (expr instanceof MultiExpression multi) {
            var conds = new ArrayList<Cond<C>>();
            for (var predicate : multi.predicates) {
                conds.add(walk(predicate));
            }
            return combine(multi.operator, conds);
        }
        var op = expr.operator;
        if (op == Operator.AND || op == Operator.OR) {
            var conds = new ArrayList<Cond<C>>();
            conds.add(walkOperand(expr.lvalue));
            conds.add(walkOperand(expr.rvalue));
            return combine(op, conds);
        } else if (op == Operator.NOT) {
            var cond = walkOperand(expr.lvalue);
            if (cond == null || !cond.negatable()) {
                return null;
            }
            return new Cond<>(not(cond.c()), true, true);
        }
        return walkPredicate(expr);
    }

    protected Cond<C> walkOperand(Operand operand) {
        return operand instanceof Expression e ? walk(e) : null;
    }

    protected Cond<C> combine(Operator op, List<Cond<C>> conds) {
        if (op == Operator.AND) {
            var cs = new ArrayList<C>();
            boolean exact = true;
            for (var cond : conds) {
                if (cond == null) {
                    exact = false; // dropping a member of a conjunction keeps a superset
                } else {
                    cs.add(cond.c());
                    exact &= cond.exact();
                }
            }
            if (cs.isEmpty()) {
                return null;
            }
            return new Cond<>(cs.size() == 1 ? cs.get(0) : and(cs), exact, false);
        } else if (op == Operator.OR) {
            var cs = new ArrayList<C>();
            boolean exact = true;
            for (var cond : conds) {
                if (cond == null) {
                    return null;
                }
                cs.add(cond.c());
                exact &= cond.exact();
            }
            return new Cond<>(cs.size() == 1 ? cs.get(0) : or(cs), exact, false);
        }
        return null;
    }

    protected Cond<C> exact(C c, boolean negatable) {
        return new Cond<>(c, true, negatable);
    }

    protected Cond<C> walkPredicate(Expression expr) {
        if (!(expr.lvalue instanceof Reference ref) || ref.cast != null) {
            return null;
        }
        var name = ref.name;
        var op = expr.operator;
        var rvalue = expr.rvalue;
        if (NXQL.ECM_PATH.equals(name)) {
            return walkEcmPath(op, rvalue);
        } else if (NXQL.ECM_ANCESTORID.equals(name)) {
            if (op == Operator.EQ && rvalue instanceof StringLiteral s) {
                return exact(predicate(Target.of(KEY_ANCESTOR_IDS, Kind.STRING, true), Op.CONTAINS, s.value), false);
            }
            return null;
        } else if (NXQL.ECM_MIXINTYPE.equals(name)) {
            return walkMixinType(op, rvalue);
        }
        var target = resolve(name);
        if (target == null) {
            return null;
        }
        boolean negatable = !target.hasWildcard();
        if (op == Operator.ISNULL || op == Operator.ISNOTNULL) {
            if (!target.isTopLevel() || target.kind() == Kind.BOOLEAN) {
                return null;
            }
            return exact(predicate(target, op == Operator.ISNULL ? Op.IS_NULL : Op.IS_NOT_NULL, null), true);
        }
        if (target.kind() == Kind.BOOLEAN) {
            return walkBoolean(target, op, rvalue);
        } else if (target.array()) {
            return walkArray(target, op, rvalue);
        }
        if (op == Operator.IN || op == Operator.NOTIN) {
            var values = literalValues(target.kind(), rvalue);
            if (values == null) {
                return null;
            }
            return exact(predicate(target, op == Operator.IN ? Op.IN : Op.NOT_IN, values), negatable);
        } else if (op == Operator.BETWEEN) {
            var values = literalValues(target.kind(), rvalue);
            if (values == null || values.size() != 2) {
                return null;
            }
            return exact(and(List.of(predicate(target, Op.GTEQ, values.get(0)),
                    predicate(target, Op.LTEQ, values.get(1)))), false);
        } else if (op == Operator.LIKE || op == Operator.NOTLIKE || op == Operator.ILIKE) {
            if (target.kind() != Kind.STRING || !(rvalue instanceof StringLiteral s) || s.value.contains("\\")) {
                return null;
            }
            var c = predicate(target, Op.MATCHES, likeToRegex(s.value, op == Operator.ILIKE));
            if (op == Operator.NOTLIKE) {
                return negatable ? exact(not(c), negatable) : null;
            }
            // ILIKE: Java regex case-insensitivity vs lowercase comparison may differ
            return new Cond<>(c, op == Operator.LIKE, negatable && op == Operator.LIKE);
        }
        Op scalarOp = null;
        if (op == Operator.EQ) {
            scalarOp = Op.EQ;
        } else if (op == Operator.NOTEQ) {
            scalarOp = Op.NE;
        } else if (op == Operator.LT) {
            scalarOp = Op.LT;
        } else if (op == Operator.GT) {
            scalarOp = Op.GT;
        } else if (op == Operator.LTEQ) {
            scalarOp = Op.LTEQ;
        } else if (op == Operator.GTEQ) {
            scalarOp = Op.GTEQ;
        }
        var value = literalValue(target.kind(), rvalue);
        if (scalarOp == null || value == null) {
            return null;
        }
        return exact(predicate(target, scalarOp, value), negatable);
    }

    protected Cond<C> walkEcmPath(Operator op, Operand rvalue) {
        if (!(rvalue instanceof StringLiteral s)) {
            return null;
        }
        var path = s.value;
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        var id = evaluator.pathResolver.getIdForPath(path);
        var idTarget = Target.of(KEY_ID, Kind.STRING, false);
        if (op == Operator.EQ) {
            return exact(id == null ? alwaysFalse() : predicate(idTarget, Op.EQ, id), true);
        } else if (op == Operator.NOTEQ) {
            return id == null ? null : exact(predicate(idTarget, Op.NE, id), true);
        } else if (op == Operator.STARTSWITH) {
            return exact(id == null ? alwaysFalse()
                    : predicate(Target.of(KEY_ANCESTOR_IDS, Kind.STRING, true), Op.CONTAINS, id), false);
        }
        return null;
    }

    protected Cond<C> walkMixinType(Operator op, Operand rvalue) {
        boolean include;
        List<Object> mixins;
        if (op == Operator.EQ || op == Operator.NOTEQ) {
            if (!(rvalue instanceof StringLiteral s)) {
                return null;
            }
            include = op == Operator.EQ;
            mixins = List.of(s.value);
        } else if (op == Operator.IN || op == Operator.NOTIN) {
            mixins = literalValues(Kind.STRING, rvalue);
            if (mixins == null) {
                return null;
            }
            include = op == Operator.IN;
        } else {
            return null;
        }
        // same computation as DBSExpressionEvaluator.walkMixinTypes
        Set<String> primaryTypes;
        if (include) {
            primaryTypes = new HashSet<>();
            for (var mixin : mixins) {
                primaryTypes.addAll(mixinDocumentTypes((String) mixin));
            }
        } else {
            primaryTypes = new HashSet<>();
            for (var docType : schemaManager.getDocumentTypes()) {
                primaryTypes.add(docType.getName());
            }
            for (var mixin : mixins) {
                primaryTypes.removeAll(mixinDocumentTypes((String) mixin));
            }
        }
        var instanceMixins = new HashSet<String>();
        var noPerInstance = schemaManager.getNoPerDocumentQueryFacets();
        for (var mixin : mixins) {
            if (!noPerInstance.contains(mixin)) {
                instanceMixins.add((String) mixin);
            }
        }
        return exact(mixin(new MixinSpec(primaryTypes, instanceMixins, include)), true);
    }

    protected Set<String> mixinDocumentTypes(String mixin) {
        var types = schemaManager.getDocumentTypeNamesForFacet(mixin);
        return types == null ? Set.of() : types;
    }

    protected Cond<C> walkBoolean(Target target, Operator op, Operand rvalue) {
        var value = literalValue(Kind.BOOLEAN, rvalue);
        if (value == null || (op != Operator.EQ && op != Operator.NOTEQ)) {
            return null;
        }
        boolean wantTrue = (Boolean) value == (op == Operator.EQ);
        if (target.isTopLevel() && target.leafKey().startsWith(NXQL.ECM_PREFIX)) {
            // ecm: booleans are false when null
            return exact(predicate(target, wantTrue ? Op.IS_TRUE : Op.IS_FALSE_OR_NULL, null), true);
        }
        return exact(predicate(target, op == Operator.EQ ? Op.EQ : Op.NE, value), !target.hasWildcard());
    }

    protected Cond<C> walkArray(Target target, Operator op, Operand rvalue) {
        if (!target.isTopLevel()) {
            return null;
        }
        if (op == Operator.EQ || op == Operator.NOTEQ) {
            var value = literalValue(target.kind(), rvalue);
            if (value == null) {
                return null;
            }
            var c = predicate(target, Op.CONTAINS, value);
            return exact(op == Operator.EQ ? c : not(c), true);
        } else if (op == Operator.IN || op == Operator.NOTIN) {
            var values = literalValues(target.kind(), rvalue);
            if (values == null) {
                return null;
            }
            var c = predicate(target, Op.CONTAINS_ANY, values);
            return exact(op == Operator.IN ? c : not(c), true);
        }
        return null;
    }

    /**
     * Resolves a NXQL property name to its stored path and value kind, or {@code null} if not translatable.
     */
    protected Target resolve(String name) {
        if (name.startsWith(NXQL.ECM_PREFIX) || name.startsWith("major_version") || name.startsWith("uid:")) {
            String key;
            try {
                key = DBSSession.convToInternal(name);
            } catch (QueryParseException | UnsupportedOperationException e) {
                key = null;
            }
            if (key != null) {
                if (ECM_BOOLEAN_KEYS.contains(key)) {
                    return Target.of(key, Kind.BOOLEAN, false);
                } else if (ECM_STRING_KEYS.contains(key)) {
                    return Target.of(key, Kind.STRING, false);
                } else if (ECM_LONG_KEYS.contains(key)) {
                    return Target.of(key, Kind.LONG, false);
                } else if (ECM_DATE_KEYS.contains(key)) {
                    return Target.of(key, Kind.DATE, false);
                }
                // other system properties (ACLs, fulltext, locks, blob keys...) have special semantics
                return null;
            }
            if (name.startsWith(NXQL.ECM_PREFIX)) {
                return null;
            }
        }
        var segments = name.split("/");
        var field = getField(segments[0]);
        if (field == null) {
            return null;
        }
        var steps = new ArrayList<Step>();
        var key = field.getName().getPrefixedName();
        var type = field.getType();
        int i = 1;
        for (;;) {
            if (type.isListType()) {
                var elementType = ((ListType) type).getFieldType();
                if (i == segments.length) {
                    if (!elementType.isSimpleType()) {
                        return null; // whole complex list
                    }
                    steps.add(new Step(key, false, null));
                    var kind = kindOf(elementType);
                    return kind == null ? null : new Target(steps, kind, true);
                }
                var segment = segments[i++];
                Long index;
                if ("*".equals(segment)) {
                    index = null;
                } else if (INDEX.matcher(segment).matches()) {
                    index = Long.valueOf(segment);
                } else {
                    return null; // correlated wildcard *1, or invalid
                }
                if (!elementType.isComplexType() || i == segments.length) {
                    return null; // element of a scalar array, or whole complex element
                }
                steps.add(new Step(key, true, index));
                var sub = ((ComplexType) elementType).getField(segments[i++]);
                if (sub == null) {
                    return null;
                }
                key = sub.getName().getPrefixedName();
                type = sub.getType();
            } else if (type.isComplexType()) {
                if (i == segments.length) {
                    return null; // whole complex value
                }
                steps.add(new Step(key, false, null));
                var sub = ((ComplexType) type).getField(segments[i++]);
                if (sub == null) {
                    return null;
                }
                key = sub.getName().getPrefixedName();
                type = sub.getType();
            } else {
                if (i != segments.length) {
                    return null;
                }
                steps.add(new Step(key, false, null));
                var kind = kindOf(type);
                return kind == null ? null : new Target(steps, kind, false);
            }
        }
    }

    /** Same lookup as the evaluator: prefixed name, or unprefixed name in a schema without prefix. */
    protected Field getField(String name) {
        var field = schemaManager.getField(name);
        if (field != null || name.indexOf(':') > -1) {
            return field;
        }
        for (var schema : schemaManager.getSchemas()) {
            if (StringUtils.isBlank(schema.getNamespace().prefix)) {
                field = schema.getField(name);
                if (field != null) {
                    return field;
                }
            }
        }
        return null;
    }

    protected static Kind kindOf(Type type) {
        if (!type.isSimpleType()) {
            return null;
        }
        var primitive = ((SimpleType) type).getPrimitiveType();
        if (primitive instanceof StringType) {
            return Kind.STRING;
        } else if (primitive instanceof LongType || primitive instanceof IntegerType) {
            return Kind.LONG;
        } else if (primitive instanceof DoubleType) {
            return Kind.DOUBLE;
        } else if (primitive instanceof BooleanType) {
            return Kind.BOOLEAN;
        } else if (primitive instanceof DateType) {
            return Kind.DATE;
        }
        return null;
    }

    /**
     * Converts a literal to the stored representation for the given kind, or {@code null} if incompatible.
     */
    protected Object literalValue(Kind kind, Operand operand) {
        Object value = switch (kind) {
            case STRING -> operand instanceof StringLiteral s ? s.value : null;
            case LONG -> operand instanceof IntegerLiteral i ? (Object) i.value : null;
            case DOUBLE -> operand instanceof IntegerLiteral i ? (Object) (double) i.value
                    : operand instanceof DoubleLiteral d ? (Object) d.value : null;
            case BOOLEAN -> operand instanceof IntegerLiteral i ? (Object) (i.value != 0)
                    : operand instanceof BooleanLiteral b ? (Object) b.value : null;
            case DATE -> operand instanceof DateLiteral d && !d.onlyDate ? d.value : null;
        };
        return value == null ? null : convertValue(value);
    }

    protected List<Object> literalValues(Kind kind, Operand operand) {
        if (!(operand instanceof LiteralList list)) {
            return null;
        }
        var values = new ArrayList<>(list.size());
        for (Literal literal : list) {
            var value = literalValue(kind, literal);
            if (value == null) {
                return null;
            }
            values.add(value);
        }
        return values;
    }

    /**
     * Converts a NXQL LIKE pattern (without escapes) into a Java regex (full match).
     */
    public static String likeToRegex(String like, boolean caseInsensitive) {
        var sb = new StringBuilder(caseInsensitive ? "(?is)" : "(?s)");
        var literal = new StringBuilder();
        for (char c : like.toCharArray()) {
            if (c == '%' || c == '_') {
                if (!literal.isEmpty()) {
                    sb.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                sb.append(c == '%' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            sb.append(Pattern.quote(literal.toString()));
        }
        return sb.toString();
    }

}
