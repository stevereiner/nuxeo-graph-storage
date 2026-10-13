package org.integratedsemantics.nuxeo.graph;

import static org.nuxeo.ecm.core.storage.State.NOP;

import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.nuxeo.ecm.core.api.ConcurrentUpdateException;
import org.nuxeo.ecm.core.api.model.Delta;
import org.nuxeo.ecm.core.storage.State;
import org.nuxeo.ecm.core.storage.State.ListDiff;
import org.nuxeo.ecm.core.storage.State.StateDiff;
import org.nuxeo.ecm.core.storage.dbs.DBSTransactionState.ConditionalUpdates;
import org.nuxeo.ecm.core.storage.mem.MemConnection;

/**
 * The changes of a DBS document update, grouped by kind: new values for top-level keys, counter increments, and diffs
 * that can only be applied knowing the current value of their key.
 *
 * @since 0.1.0
 */
public class StateUpdate {

    protected final String id;

    /** New values of top-level keys (scalars, arrays or complex values), {@code null} to remove. */
    public final Map<String, Serializable> values = new LinkedHashMap<>();

    /** Counter increments. */
    public final Map<String, Delta> deltas = new HashMap<>();

    /** Diffs on complex values or arrays, see {@link #applyDiffs}. */
    public final StateDiff diffs = new StateDiff();

    public StateUpdate(String id, StateDiff diff, ConditionalUpdates conditionalUpdates) {
        this.id = id;
        for (var en : diff.entrySet()) {
            add(en.getKey(), en.getValue());
        }
        if (conditionalUpdates != null) {
            conditionalUpdates.getUpdates().forEach(this::add);
        }
    }

    protected void add(String key, Serializable value) {
        if (value instanceof StateDiff || value instanceof ListDiff) {
            diffs.put(key, value);
        } else if (value instanceof Delta delta) {
            deltas.put(key, delta);
        } else {
            values.put(key, value);
        }
    }

    /**
     * Applies the diffs onto the current values of their keys, and records the results as new values.
     *
     * @param current the current state, containing at least the keys of the diffs
     * @throws ConcurrentUpdateException if a diff refers to list elements that don't exist anymore
     */
    public void applyDiffs(State current) {
        if (diffs.isEmpty()) {
            return;
        }
        for (var en : diffs.entrySet()) {
            checkApplicable(current.get(en.getKey()), en.getValue());
        }
        MemConnection.applyDiff(current, diffs);
        for (var en : diffs.entrySet()) {
            values.put(en.getKey(), current.get(en.getKey()));
        }
        for (var key : List.copyOf(diffs.keySet())) {
            diffs.remove(key);
        }
    }

    /**
     * Checks that a diff only updates list elements that exist, otherwise a concurrent transaction changed the list.
     */
    protected void checkApplicable(Serializable current, Serializable diff) {
        if (diff instanceof StateDiff stateDiff) {
            if (current instanceof State state) {
                for (var en : stateDiff.entrySet()) {
                    checkApplicable(state.get(en.getKey()), en.getValue());
                }
            }
        } else if (diff instanceof ListDiff listDiff && listDiff.diff != null) {
            List<?> list;
            if (current instanceof List<?> l) {
                list = l;
            } else if (current instanceof Object[] array) {
                list = List.of(array);
            } else {
                list = List.of();
            }
            int i = 0;
            for (var element : listDiff.diff) {
                if (element != NOP) {
                    if (i >= list.size()) {
                        throw new ConcurrentUpdateException(id);
                    }
                    if (element instanceof StateDiff) {
                        checkApplicable((Serializable) list.get(i), (Serializable) element);
                    }
                }
                i++;
            }
        }
    }

}
