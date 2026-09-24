package com.jettra.store.engine.core.storage.slotted;

import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.nio.charset.StandardCharsets;

import static io.jettra.test.core.JettraAssert.*;

/**
 * JettraTest suite validating in-place updates, tombstone deletion, and internal compaction.
 */
@NotRequiresRunningServer
public class SlottedPageInPlaceUpdateAndCompactionTest {

    @JettraTest
    @DisplayName("In-place update within existing slot displacement when new payload length <= old slot length")
    public void testInPlaceUpdateWithinExistingDisplacement() {
        Page page = SlottedPageBuilder.create()
            .withPageId(10L)
            .withPageSize(SlottedPageConstants.PAGE_SIZE_4KB)
            .withOffHeap(true)
            .build();

        byte[] originalPayload = "Medium sized payload for customer 101".getBytes(StandardCharsets.UTF_8);
        int slotId = page.insertRecord("cust_101", originalPayload, 1, 1000L);

        Slot slotV1 = page.getSlot(slotId);
        int originalOffset = slotV1.offset();
        int originalLength = slotV1.length();

        // Shorter payload (same key)
        byte[] shorterPayload = "Short update".getBytes(StandardCharsets.UTF_8);
        boolean updated = page.updateRecordInPlace(slotId, "cust_101", shorterPayload, 2, 2000L);

        assertTrue(updated, "In-place update must return true");
        Slot slotV2 = page.getSlot(slotId);

        // Verification: Must occupy the EXACT same offset in-place!
        assertEquals(originalOffset, slotV2.offset(), "Slot offset must remain identical for in-place overwrite");
        assertEquals(2, slotV2.version(), "Slot version must increment to 2");

        // Excess space is tracked as fragmented
        int expectedStoredLen = Page.calculateStoredRecordLength("cust_101", shorterPayload.length);
        assertEquals(originalLength - expectedStoredLen, page.getHeader().getFragmentedFreeSpace(), "Difference must be tracked as fragmented free space");

        // Read payload back
        byte[] readBack = page.readRecordPayload(slotId);
        assertNotNull(readBack);
        assertEquals("Short update", new String(readBack, StandardCharsets.UTF_8));
    }

    @JettraTest
    @DisplayName("In-place update expanding into contiguous free space when new payload > old slot length")
    public void testInPlaceUpdateExpandingToContiguousSpace() {
        Page page = SlottedPageBuilder.create()
            .withPageId(20L)
            .withPageSize(SlottedPageConstants.PAGE_SIZE_4KB)
            .withOffHeap(true)
            .build();

        byte[] smallPayload = "Tiny".getBytes(StandardCharsets.UTF_8);
        int slotId = page.insertRecord("rec_A", smallPayload, 1, 1000L);
        Slot slotV1 = page.getSlot(slotId);

        // Substantially larger payload
        byte[] largePayload = "A much larger expanded payload replacing the tiny initial record with rich JSON content".getBytes(StandardCharsets.UTF_8);
        boolean updated = page.updateRecordInPlace(slotId, "rec_A", largePayload, 2, 2000L);

        assertTrue(updated, "Update must succeed");
        Slot slotV2 = page.getSlot(slotId);

        // Offset moved to new free space location, old location marked as fragmented
        assertTrue(slotV2.offset() < slotV1.offset(), "Expanded record must be allocated downward in free space");
        assertEquals(slotV1.length(), page.getHeader().getFragmentedFreeSpace(), "Old slot bytes must become fragmented free space");
        assertEquals(2, slotV2.version());

        byte[] readBack = page.readRecordPayload(slotId);
        assertNotNull(readBack);
        assertEquals(new String(largePayload, StandardCharsets.UTF_8), new String(readBack, StandardCharsets.UTF_8));
    }

    @JettraTest
    @DisplayName("Internal page compaction defragments dead space and packs active records")
    public void testInternalPageCompactionDefragmentation() {
        Page page = SlottedPageBuilder.create()
            .withPageId(30L)
            .withPageSize(SlottedPageConstants.PAGE_SIZE_4KB)
            .withOffHeap(true)
            .withCompactionStrategy(new InPlacePageCompactionStrategy())
            .build();

        // Insert 3 records
        byte[] p1 = "Record One Payload".getBytes(StandardCharsets.UTF_8);
        byte[] p2 = "Record Two Payload (to be deleted)".getBytes(StandardCharsets.UTF_8);
        byte[] p3 = "Record Three Payload".getBytes(StandardCharsets.UTF_8);

        int s0 = page.insertRecord("k1", p1, 1, 100L);
        int s1 = page.insertRecord("k2", p2, 1, 200L);
        int s2 = page.insertRecord("k3", p3, 1, 300L);

        assertEquals(3, page.getActiveRecordCount());
        assertEquals(0, page.getHeader().getFragmentedFreeSpace());

        // Delete record 2 (creating a hole in the middle)
        boolean deleted = page.deleteRecord(s1);
        assertTrue(deleted, "Delete must succeed");
        assertEquals(2, page.getActiveRecordCount(), "Active record count must be 2");
        assertTrue(page.getHeader().getFragmentedFreeSpace() > 0, "Fragmented space must be positive");

        int fragmentedBefore = page.getHeader().getFragmentedFreeSpace();

        // Compact page in-place
        int reclaimed = page.compact();
        assertEquals(fragmentedBefore, reclaimed, "Reclaimed bytes must equal fragmented space before compaction");
        assertEquals(0, page.getHeader().getFragmentedFreeSpace(), "Fragmented space must be 0 after compaction");
        assertEquals(2, page.getActiveRecordCount(), "Active record count must remain 2");

        // Verify remaining active records are intact and readable
        byte[] read1 = page.readRecordPayload(s0);
        byte[] read3 = page.readRecordPayload(s2);
        assertNotNull(read1);
        assertNotNull(read3);
        assertEquals("Record One Payload", new String(read1, StandardCharsets.UTF_8));
        assertEquals("Record Three Payload", new String(read3, StandardCharsets.UTF_8));

        // Deleted slot still returns null
        byte[] read2 = page.readRecordPayload(s1);
        assertNull(read2, "Deleted record must return null");
    }

    @JettraTest
    @DisplayName("Reuse deleted slot directory entries on subsequent record insertions")
    public void testSlotDirectoryEntryReuse() {
        Page page = SlottedPageBuilder.create()
            .withPageId(40L)
            .withPageSize(SlottedPageConstants.PAGE_SIZE_4KB)
            .withOffHeap(true)
            .build();

        int s0 = page.insertRecord("item0", "Payload 0".getBytes(StandardCharsets.UTF_8), 1, 10L);
        int s1 = page.insertRecord("item1", "Payload 1".getBytes(StandardCharsets.UTF_8), 1, 20L);
        int s2 = page.insertRecord("item2", "Payload 2".getBytes(StandardCharsets.UTF_8), 1, 30L);

        assertEquals(3, page.getSlotCount());
        assertEquals(3, page.getActiveRecordCount());

        // Delete slot 1
        page.deleteRecord(s1);
        assertEquals(3, page.getSlotCount(), "Slot count remains 3 after tombstone deletion");
        assertEquals(2, page.getActiveRecordCount(), "Active records drops to 2");

        // Insert new record - should reuse slot 1 without advancing slot directory forward!
        int sReused = page.insertRecord("itemNew", "Payload New".getBytes(StandardCharsets.UTF_8), 1, 40L);
        assertEquals(s1, sReused, "New insert must reuse slot index 1");
        assertEquals(3, page.getSlotCount(), "Slot directory count must NOT increase when reusing slot");
        assertEquals(3, page.getActiveRecordCount(), "Active record count restored to 3");

        byte[] payloadNew = page.readRecordPayload(sReused);
        assertNotNull(payloadNew);
        assertEquals("Payload New", new String(payloadNew, StandardCharsets.UTF_8));
    }
}
