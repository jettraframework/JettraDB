package com.jettra.store.engine.core.storage.slotted;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clock (Second-Chance) Page Eviction Strategy.
 * Efficient circular buffer algorithm mitigating overhead of full LRU timestamps.
 */
public class ClockPageEvictionStrategy implements PageEvictionStrategy {

    private final Map<Long, Boolean> referenceBits = new ConcurrentHashMap<>();
    private int hand = 0;

    @Override
    public synchronized Page selectVictim(Map<Long, Page> pages) {
        if (pages == null || pages.isEmpty()) return null;

        List<Long> keys = new ArrayList<>(pages.keySet());
        int size = keys.size();
        if (size == 0) return null;

        // Up to two full revolutions to find an unpinned victim
        for (int i = 0; i < size * 2; i++) {
            hand = (hand + 1) % size;
            Long pageId = keys.get(hand);
            Page page = pages.get(pageId);
            if (page == null) continue;

            if (page.getPinCount() == 0) {
                boolean referenced = referenceBits.getOrDefault(pageId, false);
                if (referenced) {
                    referenceBits.put(pageId, false); // Give second chance
                } else {
                    return page; // Selected victim
                }
            }
        }

        // Fallback: pick any unpinned page if available
        for (Page page : pages.values()) {
            if (page.getPinCount() == 0) {
                return page;
            }
        }
        return null;
    }

    @Override
    public void recordAccess(long pageId) {
        referenceBits.put(pageId, true);
    }

    @Override
    public void onPageRemoved(long pageId) {
        referenceBits.remove(pageId);
    }
}
