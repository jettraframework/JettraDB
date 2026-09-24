package com.jettra.store.engine.core.storage.slotted;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.jettra.store.engine.core.storage.slotted.SlottedPageConstants.SLOT_ENTRY_SIZE;

/**
 * High-performance In-Place implementation of {@link PageCompactionStrategy}.
 * Defragments a slotted page by moving all active record payloads to the bottom of the page,
 * consolidating fragmented dead space into a single contiguous block between the slot directory
 * and the record data area.
 */
public class InPlacePageCompactionStrategy implements PageCompactionStrategy {

    private record ActiveRecordInfo(int slotId, int oldOffset, int length, int version, short flags) {}

    @Override
    public int compact(Page page) {
        if (page == null) return 0;
        PageHeader header = page.getHeader();
        int fragmented = header.getFragmentedFreeSpace();
        if (fragmented <= 0 && header.getSlotCount() == header.getActiveRecordCount()) {
            return 0; // Already contiguous and fully packed
        }

        ByteBuffer buffer = page.getByteBuffer();
        int pageSize = header.getPageSize();
        int slotCount = header.getSlotCount();

        // 1. Collect active records
        List<ActiveRecordInfo> activeRecords = new ArrayList<>(header.getActiveRecordCount());
        for (int i = 0; i < slotCount; i++) {
            Slot slot = page.getSlot(i);
            if (slot.isActive() && slot.length() > 0) {
                activeRecords.add(new ActiveRecordInfo(slot.slotId(), slot.offset(), slot.length(), slot.version(), slot.flags()));
            }
        }

        // 2. Sort active records by their current offset descending (closest to end of page first)
        activeRecords.sort(Comparator.comparingInt(ActiveRecordInfo::oldOffset).reversed());

        // 3. Compact records towards the end of the page (pageSize downwards)
        // To avoid overlapping writes when moving downwards, we can read payloads or copy safely
        int writeCursor = pageSize;

        // Temporary off-heap copy or staged byte transfers
        // Since active records move down, we extract their bytes and rewrite them packed from pageSize downwards
        List<byte[]> payloads = new ArrayList<>(activeRecords.size());
        for (ActiveRecordInfo rec : activeRecords) {
            byte[] data = new byte[rec.length];
            int originalPos = buffer.position();
            buffer.position(rec.oldOffset);
            buffer.get(data);
            buffer.position(originalPos);
            payloads.add(data);
        }

        // Write packed payloads back starting from pageSize downwards
        for (int i = 0; i < activeRecords.size(); i++) {
            ActiveRecordInfo rec = activeRecords.get(i);
            byte[] data = payloads.get(i);
            writeCursor -= rec.length;
            int newOffset = writeCursor;

            // Write payload to newOffset
            int originalPos = buffer.position();
            buffer.position(newOffset);
            buffer.put(data);
            buffer.position(originalPos);

            // Update slot directory in buffer
            page.setSlot(rec.slotId, new Slot(rec.slotId, newOffset, rec.length, SlottedPageConstants.SLOT_STATUS_ACTIVE, rec.flags, rec.version));
        }

        // 4. Update header free space pointers
        int reclaimed = fragmented;
        header.setFreeSpaceEnd(writeCursor);
        header.setFragmentedFreeSpace(0);
        header.setFlags((short) (header.getFlags() | SlottedPageConstants.PAGE_FLAG_COMPACTED));
        page.setDirty(true);
        header.writeTo(buffer);

        return reclaimed;
    }
}
