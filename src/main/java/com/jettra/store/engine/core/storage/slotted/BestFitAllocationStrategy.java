package com.jettra.store.engine.core.storage.slotted;

import java.util.Collection;

/**
 * Best-Fit Space Allocation Strategy (Strategy Pattern).
 * Chooses the page with the smallest available free space that is still large enough
 * to accommodate the record, maximizing space utilization and minimizing fragmentation.
 */
public class BestFitAllocationStrategy implements SpaceAllocationStrategy {

    @Override
    public Page selectPage(Collection<Page> pages, int requiredBytes) {
        if (pages == null || pages.isEmpty()) return null;

        Page bestPage = null;
        int minWaste = Integer.MAX_VALUE;

        for (Page page : pages) {
            if (page.canAccommodate(requiredBytes)) {
                int free = page.getTotalFreeSpace();
                int waste = free - requiredBytes;
                if (waste >= 0 && waste < minWaste) {
                    minWaste = waste;
                    bestPage = page;
                }
            }
        }
        return bestPage;
    }
}
