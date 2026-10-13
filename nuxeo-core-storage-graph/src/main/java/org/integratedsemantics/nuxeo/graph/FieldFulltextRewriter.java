package org.integratedsemantics.nuxeo.graph;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.ecm.core.query.sql.model.Expression;
import org.nuxeo.ecm.core.query.sql.model.MultiExpression;
import org.nuxeo.ecm.core.query.sql.model.Operand;
import org.nuxeo.ecm.core.query.sql.model.Operator;
import org.nuxeo.ecm.core.query.sql.model.Predicate;
import org.nuxeo.ecm.core.query.sql.model.Reference;
import org.nuxeo.ecm.core.query.sql.model.StringLiteral;
import org.nuxeo.ecm.core.storage.dbs.DBSExpressionEvaluator;

/**
 * Rewrites the fulltext conditions on one field ({@code ecm:fulltext.dc:title LIKE 'hello wor*'}) into word matches
 * on the field, case-insensitive: here, words starting with {@code hello} and {@code wor}.
 * <p>
 * Nuxeo's DBS stores (MongoDB, memory) evaluate such a condition as one case-insensitive {@code LIKE} on the field,
 * with {@code %} for spaces, where the {@code *} of a prefix is a literal: the Web UI's suggestion box (top right),
 * which sends {@code ecm:fulltext.dc:title LIKE '<input>*'}, finds nothing without OpenSearch. Here each term matches
 * the start of a word, with or without {@code *} (like OpenSearch's {@code match_phrase_prefix} that the suggestion
 * box uses), {@code -term} excludes it, and the terms must all match. A word starts the field or follows a space or
 * punctuation ({@link #SEPARATORS}), so the words of a file name ({@code company-ontology-test.txt}) are found.
 * <p>
 * The rewritten conditions are plain {@code ILIKE} conditions: translated for the database like any other, and
 * evaluated the same way in Java.
 *
 * @since 0.1.0
 */
public final class FieldFulltextRewriter {

    protected static final String PREFIX = NXQL.ECM_FULLTEXT + ".";

    /** The characters before a word, besides the start of the field: titles are often file names. */
    protected static final List<String> SEPARATORS = List.of(" ", "-", "_", ".", "/", "(", "[", ",", "'");

    private static final Field EXPRESSION_FIELD;

    static {
        try {
            EXPRESSION_FIELD = DBSExpressionEvaluator.class.getDeclaredField("expression");
            EXPRESSION_FIELD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private FieldFulltextRewriter() {
        // utility class
    }

    /**
     * Rewrites the query of an evaluator (before it is parsed), if it has fulltext conditions on fields.
     */
    public static void rewrite(DBSExpressionEvaluator evaluator) {
        var expression = evaluator.getExpression();
        if (expression == null) {
            return;
        }
        var rewritten = rewrite(expression);
        if (rewritten != expression) {
            try {
                // the evaluator is created by DBSSession: replace its expression
                EXPRESSION_FIELD.set(evaluator, rewritten);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** The rewritten expression, or the same instance if nothing to rewrite. */
    public static Expression rewrite(Expression expression) {
        if (expression instanceof MultiExpression multi) {
            var predicates = new ArrayList<Predicate>(multi.predicates.size());
            boolean changed = false;
            for (var predicate : multi.predicates) {
                var rewritten = (Predicate) rewrite(predicate);
                changed |= rewritten != predicate;
                predicates.add(rewritten);
            }
            return changed ? new MultiExpression(multi.operator, predicates) : multi;
        }
        var op = expression.operator;
        if (expression.lvalue instanceof Reference ref && ref.name.startsWith(PREFIX)
                && (op == Operator.LIKE || op == Operator.EQ) && expression.rvalue instanceof StringLiteral query) {
            return wordMatch(ref.name.substring(PREFIX.length()), query.value);
        }
        if (op == Operator.AND || op == Operator.OR || op == Operator.NOT) {
            var lvalue = rewriteOperand(expression.lvalue);
            var rvalue = rewriteOperand(expression.rvalue);
            if (lvalue != expression.lvalue || rvalue != expression.rvalue) {
                return new Predicate(lvalue, op, rvalue);
            }
        }
        return expression;
    }

    protected static Operand rewriteOperand(Operand operand) {
        return operand instanceof Expression e ? rewrite(e) : operand;
    }

    /** All the terms of a fulltext query must match words of the field. */
    protected static Predicate wordMatch(String field, String query) {
        var conditions = new ArrayList<Predicate>();
        for (var term : query.trim().split("\\s+")) {
            term = term.replace("\"", "").replace("(", "").replace(")", "");
            boolean exclude = term.startsWith("-");
            if (exclude) {
                term = term.substring(1);
            }
            term = term.replace("*", "");
            if (term.isEmpty() || term.equalsIgnoreCase("OR")) {
                continue;
            }
            var match = termMatch(field, escapeLike(term));
            conditions.add(exclude ? new Predicate(match, Operator.NOT, null) : match);
        }
        if (conditions.isEmpty()) {
            // an empty fulltext query matches nothing
            return new Predicate(new Reference(NXQL.ECM_UUID), Operator.EQ, new StringLiteral(""));
        }
        return conditions.size() == 1 ? conditions.get(0) : new MultiExpression(Operator.AND, conditions);
    }

    /** The field has a word starting with the term: at the start of the field, or after a separator. */
    protected static Predicate termMatch(String field, String term) {
        var alternatives = new ArrayList<Predicate>();
        alternatives.add(ilike(field, term + "%"));
        for (var separator : SEPARATORS) {
            alternatives.add(ilike(field, "%" + escapeLike(separator) + term + "%"));
        }
        return new MultiExpression(Operator.OR, alternatives);
    }

    protected static Predicate ilike(String field, String pattern) {
        return new Predicate(new Reference(field), Operator.ILIKE, new StringLiteral(pattern));
    }

    /** Escapes the LIKE wildcards of a term (NXQL's escape character is the backslash). */
    protected static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

}
