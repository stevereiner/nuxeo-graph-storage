package org.integratedsemantics.nuxeo.graph.arcadedb;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.ecm.core.schema.SchemaManager;
import org.nuxeo.ecm.core.schema.types.ComplexType;
import org.nuxeo.ecm.core.schema.types.ListType;
import org.nuxeo.ecm.core.schema.types.SimpleType;
import org.nuxeo.ecm.core.schema.types.Type;
import org.nuxeo.ecm.core.schema.types.primitives.BooleanType;
import org.nuxeo.ecm.core.schema.types.primitives.DateType;
import org.nuxeo.ecm.core.schema.types.primitives.DoubleType;
import org.nuxeo.ecm.core.schema.types.primitives.IntegerType;
import org.nuxeo.ecm.core.schema.types.primitives.LongType;
import org.nuxeo.ecm.core.schema.types.primitives.StringType;
import org.nuxeo.ecm.core.storage.State;
import org.nuxeo.ecm.core.storage.dbs.DBSSession;
import org.nuxeo.runtime.api.Framework;

/**
 * Converts DBS {@link State} values to and from ArcadeDB values.
 * <p>
 * A document is a single vertex: complex values are embedded maps and lists of maps, like in MongoDB.
 * <p>
 * Through the remote (HTTP/JSON) API ArcadeDB doesn't preserve Java types: small longs come back as integers, dates
 * can't be sent as such, and values inside embedded maps can't be typed by an ArcadeDB schema. So:
 * <ul>
 * <li>dates are stored as epoch milliseconds,</li>
 * <li>on read, values are converted back using the Nuxeo schema types (and the DBS types of the {@code ecm:} keys),
 * falling back to generic rules (integers are longs, lists of scalars are arrays, maps are states).</li>
 * </ul>
 *
 * @since 0.1.0
 */
public class ArcadeDBConverter {

    /** Marker for "no type known" in the type cache. */
    protected static final Type UNKNOWN = StringType.INSTANCE;

    protected final Map<String, Type> topLevelTypes = new ConcurrentHashMap<>();

    // ----- write -----

    /**
     * Converts a {@link State} into a map to store as a vertex.
     */
    public Map<String, Object> stateToMap(State state) {
        var map = new LinkedHashMap<String, Object>();
        for (var en : state.entrySet()) {
            map.put(en.getKey(), toArcade(en.getValue()));
        }
        return map;
    }

    /**
     * Converts a {@link State} value into an ArcadeDB value.
     */
    public Object toArcade(Object value) {
        if (value == null) {
            return null;
        } else if (value instanceof State state) {
            return stateToMap(state);
        } else if (value instanceof List<?> list) {
            var result = new ArrayList<>(list.size());
            for (var element : list) {
                result.add(toArcade(element));
            }
            return result;
        } else if (value instanceof Object[] array) {
            if (array.length == 0) {
                return null;
            }
            var result = new ArrayList<>(array.length);
            for (var element : array) {
                result.add(toArcade(element));
            }
            return result;
        } else if (value instanceof Calendar cal) {
            return cal.getTimeInMillis();
        } else if (value instanceof Date date) {
            return date.getTime();
        } else if (value instanceof Integer i) {
            return i.longValue();
        } else if (value instanceof String || value instanceof Long || value instanceof Double
                || value instanceof Boolean) {
            return value;
        } else {
            throw new NuxeoException("Unsupported value type: " + value.getClass().getName() + ": " + value);
        }
    }

    // ----- read -----

    /**
     * Converts the properties of a vertex into a {@link State}.
     */
    public State mapToState(Map<String, Object> map) {
        var state = new State();
        for (var en : map.entrySet()) {
            var key = en.getKey();
            if (key.startsWith("@")) {
                continue; // @rid, @type, @cat
            }
            state.put(key, fromArcade(key, en.getValue(), topLevelType(key)));
        }
        return state;
    }

    /**
     * Gets the Nuxeo type of a top-level key, or {@code null} if unknown.
     */
    protected Type topLevelType(String key) {
        var type = topLevelTypes.computeIfAbsent(key, this::computeTopLevelType);
        return type == UNKNOWN ? null : type;
    }

    protected Type computeTopLevelType(String key) {
        Type type;
        if (key.startsWith(NXQL.ECM_PREFIX)) {
            type = DBSSession.getType(key);
        } else {
            var schemaManager = Framework.getService(SchemaManager.class);
            var field = schemaManager.getField(key);
            if (field == null && key.indexOf(':') < 0) {
                for (var schema : schemaManager.getSchemas()) {
                    if (StringUtils.isBlank(schema.getNamespace().prefix)) {
                        field = schema.getField(key);
                        if (field != null) {
                            break;
                        }
                    }
                }
            }
            type = field == null ? null : field.getType();
        }
        return type == null ? UNKNOWN : type;
    }

    /**
     * Converts an ArcadeDB value into a {@link State} value.
     *
     * @param key the key of the value (used when the type is unknown)
     * @param value the value
     * @param type the Nuxeo type of the value, or {@code null} if unknown
     */
    @SuppressWarnings("unchecked")
    public Serializable fromArcade(String key, Object value, Type type) {
        if (value == null) {
            return null;
        }
        if (type == null) {
            // known DBS keys inside complex values (ACE begin/end/grant...)
            type = DBSSession.getType(key);
        }
        if (value instanceof Map<?, ?> map) {
            var complexType = type instanceof ComplexType ct ? ct : null;
            var state = new State();
            for (var en : ((Map<String, Object>) map).entrySet()) {
                Type fieldType = null;
                if (complexType != null) {
                    var field = complexType.getField(en.getKey());
                    fieldType = field == null ? null : field.getType();
                }
                state.put(en.getKey(), fromArcade(en.getKey(), en.getValue(), fieldType));
            }
            return state;
        } else if (value instanceof List<?> list) {
            var elementType = type instanceof ListType lt ? lt.getFieldType() : null;
            boolean complex = elementType != null ? elementType.isComplexType()
                    : list.stream().anyMatch(e -> e instanceof Map);
            if (complex) {
                var states = new ArrayList<State>(list.size());
                for (var element : list) {
                    states.add((State) fromArcade(key, element, elementType));
                }
                return states;
            }
            if (list.isEmpty()) {
                return null;
            }
            var values = new ArrayList<Serializable>(list.size());
            for (var element : list) {
                values.add(fromArcade(key, element, elementType));
            }
            var klass = elementType == null ? null : javaClass(elementType);
            if (klass == null) {
                klass = values.stream().filter(v -> v != null).findFirst().map(Object::getClass).orElse(null);
                if (klass != null && Calendar.class.isAssignableFrom(klass)) {
                    klass = Calendar.class;
                }
            }
            var array = (Object[]) Array.newInstance(klass == null ? Object.class : klass, values.size());
            return values.toArray(array);
        } else if (type != null && type.isSimpleType()) {
            return scalar(value, ((SimpleType) type).getPrimitiveType());
        } else {
            return genericScalar(value);
        }
    }

    protected static Class<?> javaClass(Type type) {
        if (!type.isSimpleType()) {
            return null;
        }
        var primitive = ((SimpleType) type).getPrimitiveType();
        if (primitive instanceof StringType) {
            return String.class;
        } else if (primitive instanceof LongType || primitive instanceof IntegerType) {
            return Long.class;
        } else if (primitive instanceof DoubleType) {
            return Double.class;
        } else if (primitive instanceof BooleanType) {
            return Boolean.class;
        } else if (primitive instanceof DateType) {
            return Calendar.class;
        }
        return null;
    }

    protected static Serializable scalar(Object value, Type primitive) {
        if (primitive instanceof DateType) {
            return toCalendar(value);
        } else if ((primitive instanceof LongType || primitive instanceof IntegerType) && value instanceof Number n) {
            return n.longValue();
        } else if (primitive instanceof DoubleType && value instanceof Number n) {
            return n.doubleValue();
        }
        return genericScalar(value);
    }

    protected static Serializable genericScalar(Object value) {
        if (value instanceof Integer i) {
            return i.longValue();
        } else if (value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        } else if (value instanceof Float f) {
            return f.doubleValue();
        } else if (value instanceof Date || value instanceof Temporal) {
            return toCalendar(value);
        } else if (value instanceof Serializable s) {
            return s;
        }
        throw new NuxeoException("Unsupported ArcadeDB value type: " + value.getClass().getName());
    }

    protected static Calendar toCalendar(Object value) {
        long millis;
        if (value instanceof Number n) {
            millis = n.longValue();
        } else if (value instanceof Date d) {
            millis = d.getTime();
        } else if (value instanceof LocalDateTime ldt) {
            millis = ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
        } else if (value instanceof Instant i) {
            millis = i.toEpochMilli();
        } else if (value instanceof Calendar c) {
            return c;
        } else {
            throw new NuxeoException("Not a date: " + value);
        }
        // like the MongoDB backend: a Calendar in the default time zone
        var cal = Calendar.getInstance();
        cal.setTimeInMillis(millis);
        return cal;
    }

}
