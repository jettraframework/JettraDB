package com.jettra.store.engine.core.storage.slotted;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Least Recently Used (LRU) Page Eviction Strategy.
 * Selects the unpinned page that has not been accessed for the longest period of time.
 */
public class LruPageEvictionStrategy implements PageEvictionStrategy {

    private final Map<Long, Long> accessTimestamps = new ConcurrentHashMap<>();
    private final AtomicLong accessCounter = new AtomicLong(0);

    @Override
    public Page selectVictim(Map<Long, Page> pages) {
        if (pages == null || pages.isEmpty()) return null;

        Page victim = null;
        long oldestAccess = Long.MAX_VALUE;

        for (Map.Entry<Long, Page> entry : pages.entrySet()) {
            Page page = entry.getValue();
            if (page.getPinCount() == 0) { // Only unpinned pages can be evicted
                long lastAccess = accessTimestamps.getOrDefault(entry.getKey(), 0L);
                if (lastAccess < oldestAccess) {
                    oldestAccess = lastAccess;
                    victim = page;
                }
            }
        }
        return victim;
    }

    @Override
    public void recordAccess(long pageId) {
        accessTimestamps.put(pageId, accessCounter.incrementAndGet());
    }

    @Override
    public void onPageRemoved(long pageId) {
        accessTimestamps.remove(pageId);
    }
}
