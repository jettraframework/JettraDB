package com.jettra.store.engine.core.storage.slotted;

import java.util.Objects;

/**
 * Immutable Java 25 Record representing the exact physical address of a record (RID)
 * within the slotted page storage subsystem.
 */
public record RecordSlotAddress(
    long pageId,
    int slotId
) {
    public RecordSlotAddress {
        if (slotId < 0) {
            throw new IllegalArgumentException("slotId cannot be negative: " + slotId);
        }
    }

    /**
     * Compact 64-bit address encoding (48 bits for pageId, 16 bits for slotId).
     */
    public long toEncodedLong() {
        return (pageId << 16) | (slotId & 0xFFFFL);
    }

    public static RecordSlotAddress fromEncodedLong(long encoded) {
        long pId = encoded >>> 16;
        int sId = (int) (encoded & 0xFFFFL);
        return new RecordSlotAddress(pId, sId);
    }

    @Override
    public String toString() {
        return "#" + pageId + ":" + slotId;
    }

    public static RecordSlotAddress parse(String str) {
        Objects.requireNonNull(str, "str cannot be null");
        String s = str.startsWith("#") ? str.substring(1) : str;
        String[] parts = s.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid RecordSlotAddress format: " + str);
        }
        return new RecordSlotAddress(Long.parseLong(parts[0]), Integer.parseInt(parts[1]));
    }
}
