package com.jettra.store.engine.core.storage.slotted;

import static com.jettra.store.engine.core.storage.slotted.SlottedPageConstants.*;

/**
 * Immutable Java 25 Record representing a directory entry (slot) within a slotted page.
 * Points to the exact displacement and payload length of a physical record.
 */
public record Slot(
    int slotId,
    int offset,
    int length,
    short status,
    short flags,
    int version
) {
    public boolean isActive() {
        return status == SLOT_STATUS_ACTIVE;
    }

    public boolean isDeleted() {
        return status == SLOT_STATUS_DELETED;
    }

    public boolean isFree() {
        return status == SLOT_STATUS_FREE;
    }

    public Slot withStatus(short newStatus) {
        return new Slot(slotId, offset, length, newStatus, flags, version);
    }

    public Slot withOffsetAndLength(int newOffset, int newLength, int newVersion) {
        return new Slot(slotId, newOffset, newLength, status, flags, newVersion);
    }

    public Slot asDeleted() {
        return new Slot(slotId, offset, length, SLOT_STATUS_DELETED, flags, version);
    }
}
