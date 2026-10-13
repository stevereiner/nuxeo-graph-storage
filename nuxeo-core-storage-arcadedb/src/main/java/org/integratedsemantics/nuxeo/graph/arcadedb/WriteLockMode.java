package org.integratedsemantics.nuxeo.graph.arcadedb;

import java.util.Locale;

/**
 * How the writing transactions deal with concurrent writers ({@code nuxeo.arcadedb.writeLock}). ArcadeDB has no
 * record locks: a transaction either runs optimistically (repeatable reads: ArcadeDB detects a concurrent change of a
 * record it read or wrote at commit, and the connection runs the transaction's writes again), or holds a type lock
 * ({@code LOCK TYPE}, taken at its first write) that serializes the writers.
 *
 * @since 0.1.0
 */
public enum WriteLockMode {

    /** Every writing transaction holds the type lock (the default). */
    ALWAYS,

    /** Optimistic, and a transaction still conflicting after a few replays is replayed holding the type lock. */
    FALLBACK,

    /** Optimistic only. */
    NEVER;

    /**
     * Parses {@code always} (or {@code true}), {@code fallback}, {@code never} (or {@code false}); null or blank: the
     * default.
     */
    public static WriteLockMode of(String value) {
        if (value == null || value.isBlank()) {
            return ALWAYS;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "always", "true" -> ALWAYS;
            case "fallback" -> FALLBACK;
            case "never", "false" -> NEVER;
            default -> throw new IllegalArgumentException(
                    "Invalid write lock mode: " + value + " (always, fallback or never)");
        };
    }

}
