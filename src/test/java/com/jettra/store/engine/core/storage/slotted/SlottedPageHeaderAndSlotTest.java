package com.jettra.store.engine.core.storage.slotted;

import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.nio.charset.StandardCharsets;

import static io.jettra.test.core.JettraAssert.*;

/**
 * JettraTest unit test suite for Slotted-Page Header, Checksum, and Slot Array layout.
 */
@NotRequiresRunningServer
public class SlottedPageHeaderAndSlotTest {

    @JettraTest
    @DisplayName("Verify PageHeader initialization, magic signature 0x4A545241, and 64-byte layout")
    public void testPageHeaderInitializationAndLayout() {
        int pageSize = SlottedPageConstants.PAGE_SIZE_8KB;
        Page page = SlottedPageBuilder.create()
            .withPageId(101L)
            .withPageSize(pageSize)
            .withOffHeap(true)
            .build();

        assertNotNull(page, "Page must not be null");
        assertEquals(101L, page.getPageId(), "Page ID must match 101");
        assertEquals(pageSize, page.getPageSize(), "Page size must be 8192");
        assertEquals(0, page.getSlotCount(), "Initial slot count must be 0");
        assertEquals(0, page.getActiveRecordCount(), "Initial active records must be 0");

        PageHeader header = page.getHeader();
        assertEquals(SlottedPageConstants.MAGIC_NUMBER, header.getMagicNumber(), "Magic number must match JTRA");
        assertEquals(SlottedPageConstants.FORMAT_VERSION, header.getFormatVersion(), "Format version must be 1");
        assertEquals(SlottedPageConstants.HEADER_SIZE, header.getFreeSpaceStart(), "Free space start must be 64");
        assertEquals(pageSize, header.getFreeSpaceEnd(), "Free space end must be 8192");
        assertEquals(pageSize - SlottedPageConstants.HEADER_SIZE, page.getContiguousFreeSpace(), "Contiguous free space must be 8192 - 64 = 8128");
        assertEquals(page.getContiguousFreeSpace(), page.getTotalFreeSpace(), "Contiguous and total free space must be identical on blank page");

        assertTrue(header.validateChecksum(page.getByteBuffer()), "Initial checksum must be valid");
    }

    @JettraTest
    @DisplayName("Verify slot array growth forward and data area growth backward from end of page")
    public void testSlotArrayAndDataAreaGrowth() {
        int pageSize = SlottedPageConstants.PAGE_SIZE_4KB; // 4096 bytes
        Page page = SlottedPageBuilder.create()
            .withPageId(1L)
            .withPageSize(pageSize)
            .withOffHeap(true)
            .build();

        byte[] payload1 = "{\"id\":\"doc1\",\"name\":\"Entity Alpha\"}".getBytes(StandardCharsets.UTF_8);
        byte[] payload2 = "{\"id\":\"doc2\",\"name\":\"Entity Beta\"}".getBytes(StandardCharsets.UTF_8);

        int slot0 = page.insertRecord("doc1", payload1, 1, 1000L);
        assertEquals(0, slot0, "First slot ID must be 0");
        assertEquals(1, page.getSlotCount(), "Slot count must be 1");
        assertEquals(1, page.getActiveRecordCount(), "Active count must be 1");

        // Slot directory entry is at offset 64, next slot will start at 64 + 16 = 80
        assertEquals(64 + SlottedPageConstants.SLOT_ENTRY_SIZE, page.getHeader().getFreeSpaceStart(), "FreeSpaceStart must advance forward by 16 bytes");
        // Data area grows downwards from 4096
        int storedLen1 = Page.calculateStoredRecordLength("doc1", payload1.length);
        assertEquals(pageSize - storedLen1, page.getHeader().getFreeSpaceEnd(), "FreeSpaceEnd must decrement backwards from pageSize");

        Slot s0 = page.getSlot(0);
        assertEquals(0, s0.slotId(), "Slot ID must be 0");
        assertEquals(pageSize - storedLen1, s0.offset(), "Slot 0 offset must point to start of record 1");
        assertEquals(storedLen1, s0.length(), "Slot 0 length must match stored record length");
        assertTrue(s0.isActive(), "Slot 0 must be active");
        assertEquals(1, s0.version(), "Slot 0 version must be 1");

        // Insert second record
        int slot1 = page.insertRecord("doc2", payload2, 2, 2000L);
        assertEquals(1, slot1, "Second slot ID must be 1");
        assertEquals(2, page.getSlotCount(), "Slot count must be 2");
        assertEquals(2, page.getActiveRecordCount(), "Active count must be 2");
        assertEquals(64 + (2 * SlottedPageConstants.SLOT_ENTRY_SIZE), page.getHeader().getFreeSpaceStart(), "FreeSpaceStart must advance to 96");

        // Read payloads back
        byte[] readP1 = page.readRecordPayload(slot0);
        byte[] readP2 = page.readRecordPayload(slot1);
        assertNotNull(readP1);
        assertNotNull(readP2);
        assertEquals(new String(payload1, StandardCharsets.UTF_8), new String(readP1, StandardCharsets.UTF_8));
        assertEquals(new String(payload2, StandardCharsets.UTF_8), new String(readP2, StandardCharsets.UTF_8));

        Page.RecordEntry entry1 = page.readRecordEntry(slot0);
        assertEquals("doc1", entry1.key());
        assertEquals(1, entry1.version());
        assertEquals(1000L, entry1.timestamp());

        Page.RecordEntry entry2 = page.readRecordEntry(slot1);
        assertEquals("doc2", entry2.key());
        assertEquals(2, entry2.version());
        assertEquals(2000L, entry2.timestamp());
    }

    @JettraTest
    @DisplayName("Verify CRC32 checksum detection and integrity validation")
    public void testChecksumIntegrityValidation() {
        Page page = SlottedPageBuilder.create()
            .withPageId(42L)
            .withPageSize(SlottedPageConstants.PAGE_SIZE_8KB)
            .withOffHeap(true)
            .build();

        page.insertRecord("keyA", "payloadData".getBytes(StandardCharsets.UTF_8), 1, System.currentTimeMillis());
        assertTrue(page.validateChecksum(), "Checksum must validate successfully after insert");

        // Manually tamper with byte inside payload area
        int testOffset = page.getHeader().getFreeSpaceEnd() + 4;
        byte originalByte = page.getByteBuffer().get(testOffset);
        page.getByteBuffer().put(testOffset, (byte) (originalByte ^ 0xFF));

        assertFalse(page.validateChecksum(), "Corrupted page byte must fail CRC32 checksum validation");

        // Restore byte
        page.getByteBuffer().put(testOffset, originalByte);
        assertTrue(page.validateChecksum(), "Restoring byte must revalidate CRC32 checksum");
    }
}
