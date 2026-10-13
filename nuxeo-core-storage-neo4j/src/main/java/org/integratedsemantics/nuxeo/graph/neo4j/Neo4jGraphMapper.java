package org.integratedsemantics.nuxeo.graph.neo4j;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.storage.State;

/**
 * Maps DBS {@link State}s to and from the Neo4j property graph.
 * <p>
 * The mapping is a "hybrid" graph:
 * <ul>
 * <li>a document is a node, its scalars and arrays of scalars are native node properties,</li>
 * <li>a complex value ({@link State}) is a child node linked by a {@code PROP} relationship carrying the key,</li>
 * <li>a list of complex values ({@code List<State>}) is a set of child nodes linked by {@code PROP} relationships
 * carrying the key and the position in the list.</li>
 * </ul>
 * This applies recursively, so for instance an ACP is {@code doc -PROP{ecm:acp,0}-> acl -PROP{acl,i}-> ace}.
 * <p>
 * Neo4j specifics:
 * <ul>
 * <li>lists can't contain {@code null}: the positions of the null elements of an array {@code key} are stored in an
 * extra property {@code __nulls__key}, and a null element of a complex list is a node with a {@code __null__}
 * property,</li>
 * <li>dates are stored as UTC datetimes (Cypher datetime equality depends on the time zone).</li>
 * </ul>
 *
 * @since 0.1.0
 */
public final class Neo4jGraphMapper {

    /** Prefix of the property holding the positions of the null elements of an array. */
    public static final String NULLS_PREFIX = "__nulls__";

    /** Property marking a null element of a list of complex values. */
    public static final String NULL_ELEMENT = "__null__";

    /** A node to write: its native properties and its complex children. */
    public record GraphNode(Map<String, Object> props, List<GraphChild> children) {
    }

    /** A complex child of a node: the key it lives under, its position if part of a list, and its content. */
    public record GraphChild(String key, Long pos, GraphNode node) {
    }

    /** A complex node read from the database, see {@link #fromGraph}. */
    public record ComplexRow(String parent, String key, Long pos, String eid, Map<String, Object> props) {
    }

    private Neo4jGraphMapper() {
        // utility class
    }

    /**
     * Checks if a {@link State} value is complex, i.e. stored as child nodes and not as a node property.
     */
    public static boolean isComplex(Serializable value) {
        return value instanceof State || value instanceof List;
    }

    /**
     * Converts a {@link State} into the graph nodes to write.
     */
    public static GraphNode toGraph(State state) {
        var props = new HashMap<String, Object>();
        var children = new ArrayList<GraphChild>();
        for (var en : state.entrySet()) {
            var key = en.getKey();
            var value = en.getValue();
            if (value == null) {
                continue;
            }
            if (isComplex(value)) {
                children.addAll(toGraphChildren(key, value));
            } else {
                putSimple(props, key, value);
            }
        }
        return new GraphNode(props, children);
    }

    /**
     * Puts a scalar or array value into a map of node properties, including its null positions property. A
     * {@code null} value (or empty array) puts {@code null}s, which remove the properties in a {@code SET n += map}.
     */
    public static void putSimple(Map<String, Object> props, String key, Serializable value) {
        Object nulls = null;
        Object v;
        if (value instanceof Object[] array) {
            var list = new ArrayList<>(array.length);
            var nullPositions = new ArrayList<Long>();
            for (int i = 0; i < array.length; i++) {
                if (array[i] == null) {
                    nullPositions.add((long) i);
                } else {
                    list.add(toNeo4j(array[i]));
                }
            }
            v = list.isEmpty() ? null : list;
            nulls = nullPositions.isEmpty() ? null : nullPositions;
        } else {
            v = toNeo4j(value);
        }
        props.put(key, v);
        props.put(NULLS_PREFIX + key, nulls);
    }

    /**
     * Converts a complex value ({@link State} or {@code List<State>}) into the children to write under a given key.
     */
    public static List<GraphChild> toGraphChildren(String key, Serializable value) {
        if (value instanceof State state) {
            return List.of(new GraphChild(key, null, toGraph(state)));
        } else if (value instanceof List<?> list) {
            var children = new ArrayList<GraphChild>(list.size());
            long pos = 0;
            for (var element : list) {
                GraphNode node;
                if (element == null) {
                    node = new GraphNode(Map.of(NULL_ELEMENT, true), List.of());
                } else if (element instanceof State state) {
                    node = toGraph(state);
                } else {
                    throw new NuxeoException("Unsupported list element for key: " + key + ": " + element);
                }
                children.add(new GraphChild(key, pos++, node));
            }
            return children;
        } else {
            throw new NuxeoException("Not a complex value for key: " + key + ": " + value);
        }
    }

    /**
     * Converts a {@link State} scalar value into a Neo4j property value.
     */
    public static Object toNeo4j(Object value) {
        if (value == null) {
            return null;
        } else if (value instanceof Calendar cal) {
            return ZonedDateTime.ofInstant(cal.toInstant(), ZoneOffset.UTC);
        } else if (value instanceof Integer i) {
            return i.longValue();
        } else if (value instanceof Object[] array) {
            var list = new ArrayList<>(array.length);
            for (var element : array) {
                list.add(toNeo4j(element));
            }
            return list;
        } else if (value instanceof String || value instanceof Long || value instanceof Double
                || value instanceof Boolean) {
            return value;
        } else {
            throw new NuxeoException("Unsupported value type: " + value.getClass().getName() + ": " + value);
        }
    }

    /**
     * Converts a Neo4j property value into a {@link State} scalar or array value.
     */
    public static Serializable fromNeo4j(Object value) {
        if (value == null) {
            return null;
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return null;
            }
            var first = fromNeo4j(list.get(0));
            var klass = first instanceof Calendar ? Calendar.class : first.getClass();
            var array = (Object[]) Array.newInstance(klass, list.size());
            for (int i = 0; i < array.length; i++) {
                array[i] = fromNeo4j(list.get(i));
            }
            return array;
        } else if (value instanceof Temporal temporal) {
            return toCalendar(temporal);
        } else if (value instanceof Integer i) {
            return i.longValue();
        } else if (value instanceof Serializable s) {
            return s;
        } else {
            throw new NuxeoException("Unsupported Neo4j value type: " + value.getClass().getName());
        }
    }

    private static Calendar toCalendar(Temporal temporal) {
        Instant instant;
        if (temporal instanceof ZonedDateTime z) {
            instant = z.toInstant();
        } else if (temporal instanceof OffsetDateTime o) {
            instant = o.toInstant();
        } else if (temporal instanceof LocalDateTime l) {
            instant = l.toInstant(ZoneOffset.UTC);
        } else if (temporal instanceof Instant i) {
            instant = i;
        } else {
            throw new NuxeoException("Unsupported temporal type: " + temporal.getClass().getName());
        }
        // like the MongoDB backend: a Calendar in the default time zone
        var cal = Calendar.getInstance();
        cal.setTimeInMillis(instant.toEpochMilli());
        return cal;
    }

    /**
     * Converts the properties of a node into {@link State} values, restoring the null elements of arrays.
     */
    public static void putProperties(State state, Map<String, Object> props) {
        Map<String, Object> nulls = null;
        for (var en : props.entrySet()) {
            var key = en.getKey();
            if (key.startsWith(NULLS_PREFIX)) {
                if (nulls == null) {
                    nulls = new HashMap<>();
                }
                nulls.put(key.substring(NULLS_PREFIX.length()), en.getValue());
            } else if (!key.equals(NULL_ELEMENT)) {
                state.put(key, fromNeo4j(en.getValue()));
            }
        }
        if (nulls != null) {
            nulls.forEach((key, positions) -> state.put(key, withNulls((Object[]) state.get(key), (List<?>) positions)));
        }
    }

    private static Object[] withNulls(Object[] values, List<?> nullPositions) {
        int size = (values == null ? 0 : values.length) + nullPositions.size();
        var klass = values == null ? Object.class : values.getClass().getComponentType();
        var array = (Object[]) Array.newInstance(klass, size);
        var positions = new java.util.HashSet<Long>();
        nullPositions.forEach(p -> positions.add(((Number) p).longValue()));
        int v = 0;
        for (int i = 0; i < size; i++) {
            array[i] = positions.contains((long) i) ? null : values[v++];
        }
        return array;
    }

    /**
     * Rebuilds a {@link State} from a node's properties and the rows describing its complex descendants.
     *
     * @param rootEid the element id of the root node
     * @param rootProps the native properties of the root node
     * @param rows all the complex descendants (any order)
     */
    public static State fromGraph(String rootEid, Map<String, Object> rootProps, List<ComplexRow> rows) {
        var byParent = new HashMap<String, List<ComplexRow>>();
        for (var row : rows) {
            byParent.computeIfAbsent(row.parent(), k -> new ArrayList<>()).add(row);
        }
        return buildState(rootEid, rootProps, byParent);
    }

    private static State buildState(String eid, Map<String, Object> props, Map<String, List<ComplexRow>> byParent) {
        var state = new State();
        putProperties(state, props);
        var children = byParent.get(eid);
        if (children == null) {
            return state;
        }
        var byKey = new LinkedHashMap<String, List<ComplexRow>>();
        for (var row : children) {
            byKey.computeIfAbsent(row.key(), k -> new ArrayList<>()).add(row);
        }
        for (var en : byKey.entrySet()) {
            var rowsForKey = en.getValue();
            if (rowsForKey.get(0).pos() == null) {
                var row = rowsForKey.get(0);
                state.put(en.getKey(), buildState(row.eid(), row.props(), byParent));
            } else {
                rowsForKey.sort(Comparator.comparing(ComplexRow::pos));
                var list = new ArrayList<State>(rowsForKey.size());
                for (var row : rowsForKey) {
                    list.add(row.props().containsKey(NULL_ELEMENT) ? null
                            : buildState(row.eid(), row.props(), byParent));
                }
                state.put(en.getKey(), list);
            }
        }
        return state;
    }

}
