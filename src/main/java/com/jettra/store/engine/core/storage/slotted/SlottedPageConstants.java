package com.jettra.store.engine.core.storage.slotted;

/**
 * Constants for the Slotted-Page Storage Subsystem in JettraDB.
 * Defines binary page layout boundaries, header size, slot entry size,
 * magic signatures, and the mandatory .jetrra file extension.
 */
public final class SlottedPageConstants {

    private SlottedPageConstants() {}

    /**
     * Magic signature 0x4A545241 ('J', 'T', 'R', 'A') identifying valid Jettra slotted pages.
     */
    public static final int MAGIC_NUMBER = 0x4A545241;

    /**
     * Current binary storage format version.
     */
    public static final short FORMAT_VERSION = 1;

    /**
     * Standard fixed page sizes supported by the storage engine.
     */
    public static final int PAGE_SIZE_4KB = 4 * 1024;    // 4,096 bytes
    public static final int PAGE_SIZE_8KB = 8 * 1024;    // 8,192 bytes (Default)
    public static final int PAGE_SIZE_16KB = 16 * 1024;  // 16,384 bytes
    public static final int PAGE_SIZE_64KB = 64 * 1024;  // 65,536 bytes

    /**
     * Default fixed page size across database partitions.
     */
    public static final int DEFAULT_PAGE_SIZE = PAGE_SIZE_8KB;

    /**
     * Fixed size of the page header (64 bytes, 64-bit aligned).
     */
    public static final int HEADER_SIZE = 64;

    /**
     * Size of each slot entry in the slot array directory (16 bytes).
     * Layout:
     * - offset (int, 4 bytes)
     * - length (int, 4 bytes)
     * - status (short, 2 bytes)
     * - flags  (short, 2 bytes)
     * - version (int, 4 bytes)
     */
    public static final int SLOT_ENTRY_SIZE = 16;

    /**
     * Minimum allowed free space in a page before marking it as full.
     */
    public static final int MIN_FREE_SPACE = SLOT_ENTRY_SIZE + 4;

    /**
     * Mandatory storage file extension for JettraDB slotted-page files.
     */
    public static final String FILE_EXTENSION = ".jetrra";

    /**
     * Alternative storage file extension for JettraDB slotted-page files.
     */
    public static final String ALT_FILE_EXTENSION = ".jettra";

    /**
     * Validates whether a file name has a supported slotted page extension (.jetrra or .jettra).
     */
    public static boolean isSlottedFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(FILE_EXTENSION) || lower.endsWith(ALT_FILE_EXTENSION);
    }

    // Slot status codes
    public static final short SLOT_STATUS_FREE = 0;
    public static final short SLOT_STATUS_ACTIVE = 1;
    public static final short SLOT_STATUS_DELETED = 2; // Tombstone / available for reuse
    public static final short SLOT_STATUS_FORWARDED = 3;

    // Page types
    public static final short PAGE_TYPE_DATA = 1;
    public static final short PAGE_TYPE_INDEX = 2;
    public static final short PAGE_TYPE_METADATA = 3;

    // Page flags
    public static final short PAGE_FLAG_CLEAN = 0;
    public static final short PAGE_FLAG_DIRTY = 1;
    public static final short PAGE_FLAG_COMPACTED = 2;
}
