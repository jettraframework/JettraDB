package com.jettra.store.engine.core.storage.slotted;

import java.util.Collection;

/**
 * Strategy interface (Strategy Pattern) for selecting an optimal page to host a new or relocated record.
 */
public interface SpaceAllocationStrategy {

    /**
     * Selects an existing candidate page that can accommodate the required record size,
     * or returns null if a new page must be allocated.
     *
     * @param pages Currently active/available pages.
     * @param requiredBytes Total bytes required (payload length + slot entry overhead).
     * @return The best candidate Page, or null if no page has sufficient free space.
     */
    Page selectPage(Collection<Page> pages, int requiredBytes);
}
