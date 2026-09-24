package com.jettra.store.engine.core.storage.slotted;

/**
 * Strategy interface (Strategy Pattern) for internal page compaction and defragmentation.
 * Eliminates dead space from deleted or resized records to consolidate contiguous free space.
 */
public interface PageCompactionStrategy {

    /**
     * Compacts the specified page, defragmenting active records and restoring contiguous free space.
     *
     * @param page The slotted page to compact.
     * @return Number of fragmented bytes reclaimed.
     */
    int compact(Page page);
}
