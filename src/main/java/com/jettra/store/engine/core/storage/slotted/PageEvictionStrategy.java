package com.jettra.store.engine.core.storage.slotted;

import java.util.Map;

/**
 * Strategy interface (Strategy Pattern) for page eviction within {@link BufferPool}.
 * Determines which unpinned page should be written to disk and removed from memory when the pool is full.
 */
public interface PageEvictionStrategy {

    /**
     * Selects an unpinned victim page from the loaded pages map.
     *
     * @param pages Map of pageId to Page.
     * @return The victim Page, or null if all pages are currently pinned.
     */
    Page selectVictim(Map<Long, Page> pages);

    /**
     * Records a page access event for algorithms that track recency or frequency.
     */
    void recordAccess(long pageId);

    /**
     * Notifies eviction strategy of page removal.
     */
    void onPageRemoved(long pageId);
}
