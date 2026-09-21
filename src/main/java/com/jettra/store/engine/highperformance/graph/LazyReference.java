package com.jettra.store.engine.highperformance.graph;

import com.jettra.store.engine.highperformance.paged.RecordId;

import java.util.Objects;
import java.util.function.Function;

/**
 * Lazy Reference container inspired by Eclipse Store's {@code Lazy<T>} pattern.
 *
 * <p>Enables loading massive object graphs on-demand without exhausting JVM heap memory.
 * Retains an immutable {@link RecordId} pointer and lazily resolves the object instance
 * on the first invocation of {@link #get()}.
 *
 * @param <T> Type of the referenced object entity.
 */
public class LazyReference<T> {

    private final RecordId recordId;
    private final Function<RecordId, T> resolver;
    private volatile T cachedValue;
    private volatile boolean loaded;

    private LazyReference(RecordId recordId, Function<RecordId, T> resolver, T initialValue) {
        this.recordId = Objects.requireNonNull(recordId, "recordId cannot be null");
        this.resolver = resolver;
        this.cachedValue = initialValue;
        this.loaded = (initialValue != null);
    }

    /**
     * Creates an unresolved lazy reference pointing to a physical {@link RecordId}.
     */
    public static <T> LazyReference<T> of(RecordId recordId, Function<RecordId, T> resolver) {
        return new LazyReference<>(recordId, Objects.requireNonNull(resolver, "resolver cannot be null"), null);
    }

    /**
     * Creates a pre-resolved lazy reference with an established {@link RecordId}.
     */
    public static <T> LazyReference<T> ofResolved(RecordId recordId, T value, Function<RecordId, T> resolver) {
        return new LazyReference<>(recordId, resolver, Objects.requireNonNull(value, "value cannot be null"));
    }

    /**
     * Retrieves the referenced object, lazily fetching and deserializing it if not currently loaded.
     */
    public T get() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    if (resolver == null) {
                        throw new IllegalStateException("No resolver configured for LazyReference " + recordId);
                    }
                    cachedValue = resolver.apply(recordId);
                    loaded = true;
                }
            }
        }
        return cachedValue;
    }

    /**
     * Returns true if the object is currently held in memory.
     */
    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Evicts the cached reference from JVM heap memory to reduce memory pressure.
     * The physical {@link RecordId} pointer remains intact and can be reloaded on demand.
     */
    public synchronized void evict() {
        if (resolver != null) {
            this.cachedValue = null;
            this.loaded = false;
        }
    }

    /**
     * Returns the physical RecordId pointer.
     */
    public RecordId getRecordId() {
        return recordId;
    }

    @Override
    public String toString() {
        return "LazyReference{" + "rid=" + recordId + ", loaded=" + loaded + '}';
    }
}
