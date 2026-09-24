package com.jettra.store.engine.core.storage.slotted;

import java.util.Collection;

/**
 * First-Fit Space Allocation Strategy (Strategy Pattern).
 * Selects the first encountered page in the pool that has sufficient free space
 * to accommodate the required record bytes, minimizing scan latency.
 */
public class FirstFitAllocationStrategy implements SpaceAllocationStrategy {

    @Override
    public Page selectPage(Collection<Page> pages, int requiredBytes) {
        if (pages == null || pages.isEmpty()) return null;

        for (Page page : pages) {
            if (page.canAccommodate(requiredBytes)) {
                return page;
            }
        }
        return null;
    }
}
