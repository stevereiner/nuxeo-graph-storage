package org.integratedsemantics.nuxeo.graph.neo4j;

import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_MIXIN_TYPES;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_PRIMARY_TYPE;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_READ_ACL;
import static org.integratedsemantics.nuxeo.graph.neo4j.Neo4jRepository.REL_PROP;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.neo4j.cypherdsl.core.Condition;
import org.neo4j.cypherdsl.core.Cypher;
import org.neo4j.cypherdsl.core.Expression;
import org.neo4j.cypherdsl.core.Node;
import org.neo4j.cypherdsl.core.Parameter;
import org.neo4j.cypherdsl.core.PatternElement;
import org.neo4j.cypherdsl.core.RelationshipPattern;
import org.neo4j.cypherdsl.core.Where;
import org.nuxeo.ecm.core.storage.dbs.DBSExpressionEvaluator;
import org.integratedsemantics.nuxeo.graph.GraphQueryTranslator;

/**
 * Translates NXQL into Cypher conditions on the document node {@code d}, using Cypher-DSL.
 * <p>
 * Properties inside complex values are reached through {@code PROP} relationships, for instance
 * {@code files/*}{@code /file/name = 'a'} becomes
 * {@code EXISTS { (d)-[:PROP {key: 'files'}]->(c0)-[:PROP {key: 'file'}]->(c1) WHERE c1.name = $p0 }}.
 *
 * @since 0.1.0
 */
public class Neo4jQueryTranslator extends GraphQueryTranslator<Condition> {

    protected final Node d;

    protected final String complexLabel;

    protected int paramCount;

    protected int nodeCount;

    public Neo4jQueryTranslator(Neo4jRepository repository, DBSExpressionEvaluator evaluator) {
        super(evaluator);
        d = documentNode(repository);
        complexLabel = Neo4jRepository.COMPLEX_LABEL;
    }

    /** The document node {@code d} used by the translated conditions. */
    public static Node documentNode(Neo4jRepository repository) {
        return Cypher.node(Neo4jRepository.DOCUMENT_LABEL, repository.getRepositoryLabel()).named("d");
    }

    protected Parameter<Object> param(Object value) {
        return Cypher.parameter("p" + paramCount++, value);
    }

    @Override
    protected Object convertValue(Object value) {
        if (value instanceof ZonedDateTime zdt) {
            // dates are stored in UTC, Cypher datetime equality depends on the time zone
            return zdt.withZoneSameInstant(ZoneOffset.UTC);
        }
        return value;
    }

    @Override
    protected Condition and(List<Condition> conditions) {
        var result = conditions.get(0);
        for (int i = 1; i < conditions.size(); i++) {
            result = result.and(conditions.get(i));
        }
        return result;
    }

    @Override
    protected Condition or(List<Condition> conditions) {
        var result = conditions.get(0);
        for (int i = 1; i < conditions.size(); i++) {
            result = result.or(conditions.get(i));
        }
        return result;
    }

    @Override
    protected Condition not(Condition condition) {
        return condition.not();
    }

    @Override
    protected Condition alwaysFalse() {
        return Cypher.isFalse();
    }

    @Override
    protected Condition readAcl(Set<String> principals) {
        return Cypher.any("p")
                     .in(d.property(KEY_READ_ACL))
                     .where(Cypher.name("p").in(param(new ArrayList<>(principals))));
    }

    @Override
    protected Condition mixin(MixinSpec spec) {
        Condition types = d.property(KEY_PRIMARY_TYPE).in(param(new ArrayList<>(spec.primaryTypes())));
        if (spec.instanceMixins().isEmpty()) {
            return types;
        }
        Condition mixins = Cypher.any("m")
                                 .in(Cypher.coalesce(d.property(KEY_MIXIN_TYPES), Cypher.listOf()))
                                 .where(Cypher.name("m").in(param(new ArrayList<>(spec.instanceMixins()))));
        return spec.include() ? types.or(mixins) : types.and(mixins.not());
    }

    @Override
    protected Condition predicate(Target target, Op op, Object value) {
        if (target.isTopLevel()) {
            return leaf(d.property(target.leafKey()), op, value);
        }
        // navigate the complex nodes through PROP relationships
        var steps = target.steps();
        RelationshipPattern pattern = null;
        Node previous = d;
        Node last = null;
        for (int i = 0; i < steps.size() - 1; i++) {
            var step = steps.get(i);
            var next = Cypher.node(complexLabel).named("c" + nodeCount++);
            var properties = step.index() == null ? new Object[] { "key", Cypher.literalOf(step.key()) }
                    : new Object[] { "key", Cypher.literalOf(step.key()), "pos", Cypher.literalOf(step.index()) };
            if (pattern == null) {
                pattern = previous.relationshipTo(next, REL_PROP).withProperties(properties);
            } else if (pattern instanceof org.neo4j.cypherdsl.core.Relationship rel) {
                pattern = rel.relationshipTo(next, REL_PROP).properties(properties);
            } else {
                pattern = ((org.neo4j.cypherdsl.core.RelationshipChain) pattern).relationshipTo(next, REL_PROP)
                                                                                 .properties(properties);
            }
            previous = next;
            last = next;
        }
        var condition = leaf(last.property(target.leafKey()), op, value);
        return Cypher.exists(List.<PatternElement> of(pattern), Where.from(condition));
    }

    protected Condition leaf(Expression p, Op op, Object value) {
        return switch (op) {
            case EQ -> p.isEqualTo(param(value));
            case NE -> p.isNotEqualTo(param(value));
            case LT -> p.lt(param(value));
            case GT -> p.gt(param(value));
            case LTEQ -> p.lte(param(value));
            case GTEQ -> p.gte(param(value));
            case IN -> p.in(param(value));
            case NOT_IN -> p.in(param(value)).not();
            case MATCHES -> p.matches(param(value));
            case IS_NULL -> p.isNull();
            case IS_NOT_NULL -> p.isNotNull();
            case IS_TRUE -> p.isEqualTo(Cypher.literalTrue());
            case IS_FALSE_OR_NULL -> p.isNull().or(p.isEqualTo(Cypher.literalFalse()));
            // a missing array is an empty array: never null
            case CONTAINS -> Cypher.coalesce(param(value).in(p), Cypher.literalFalse()).asCondition();
            case CONTAINS_ANY -> Cypher.coalesce(Cypher.any("x").in(p).where(Cypher.name("x").in(param(value))),
                    Cypher.literalFalse()).asCondition();
        };
    }

}
