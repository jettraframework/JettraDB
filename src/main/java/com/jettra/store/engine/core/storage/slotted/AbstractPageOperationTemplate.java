package com.jettra.store.engine.core.storage.slotted;

import java.util.Objects;

/**
 * Abstract Template Method (Template Method Pattern) for executing operations on physical slotted pages.
 * Enforces strict transactional safety, pin/unpin lifecycle, checksum recomputation,
 * and dirty-flag propagation.
 *
 * @param <R> Return type of the page operation.
 */
public abstract class AbstractPageOperationTemplate<R> {

    private final boolean mutating;

    protected AbstractPageOperationTemplate(boolean mutating) {
        this.mutating = mutating;
    }

    /**
     * The Template Method governing page operation lifecycle.
     */
    public final R execute(Page page) {
        Objects.requireNonNull(page, "page cannot be null");
        beforeOperation(page);
        try {
            R result = performOperation(page);
            onSuccess(page, result);
            return result;
        } catch (Throwable t) {
            onError(page, t);
            if (t instanceof RuntimeException re) throw re;
            throw new RuntimeException("Page operation failed on page #" + page.getPageId(), t);
        } finally {
            afterOperation(page);
        }
    }

    /**
     * Hook called before operation execution.
     * Pins the page and validates page header integrity.
     */
    protected void beforeOperation(Page page) {
        page.pin();
        if (!page.getHeader().validateChecksum(page.getByteBuffer())) {
            throw new IllegalStateException("Integrity failure: Checksum mismatch on page #" + page.getPageId());
        }
    }

    /**
     * Primitive operation to be supplied by subclasses.
     */
    protected abstract R performOperation(Page page) throws Exception;

    /**
     * Hook called on successful operation completion.
     * Updates page LSN, computes new checksum, and marks dirty if mutating.
     */
    protected void onSuccess(Page page, R result) {
        if (mutating) {
            page.getHeader().setLsn(System.currentTimeMillis());
            page.updateChecksum();
            page.setDirty(true);
        }
    }

    /**
     * Hook called if operation throws an error.
     */
    protected void onError(Page page, Throwable error) {
        // Subclasses can implement compensation or diagnostic logging
    }

    /**
     * Hook called in finally block.
     * Unpins the page to restore eviction eligibility.
     */
    protected void afterOperation(Page page) {
        page.unpin();
    }

    public boolean isMutating() {
        return mutating;
    }
}
