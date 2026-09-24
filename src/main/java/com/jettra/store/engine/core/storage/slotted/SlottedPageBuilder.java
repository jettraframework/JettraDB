package com.jettra.store.engine.core.storage.slotted;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import static com.jettra.store.engine.core.storage.slotted.SlottedPageConstants.DEFAULT_PAGE_SIZE;

/**
 * Fluent Builder (Builder Pattern) for constructing and restoring {@link Page} instances.
 * Supports configurable page sizes, Off-Heap direct memory allocation for zero GC impact,
 * and custom defragmentation strategies.
 */
public final class SlottedPageBuilder {

    private long pageId = 0L;
    private int pageSize = DEFAULT_PAGE_SIZE;
    private boolean directOffHeap = true; // Off-Heap by default for GC mitigation
    private PageCompactionStrategy compactionStrategy = new InPlacePageCompactionStrategy();
    private ByteBuffer existingBuffer = null;

    private SlottedPageBuilder() {}

    public static SlottedPageBuilder create() {
        return new SlottedPageBuilder();
    }

    public SlottedPageBuilder withPageId(long pageId) {
        this.pageId = pageId;
        return this;
    }

    public SlottedPageBuilder withPageSize(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    public SlottedPageBuilder withOffHeap(boolean directOffHeap) {
        this.directOffHeap = directOffHeap;
        return this;
    }

    public SlottedPageBuilder withCompactionStrategy(PageCompactionStrategy strategy) {
        if (strategy != null) {
            this.compactionStrategy = strategy;
        }
        return this;
    }

    public SlottedPageBuilder withExistingBuffer(ByteBuffer buffer) {
        this.existingBuffer = buffer;
        return this;
    }

    /**
     * Builds the Page instance.
     */
    public Page build() {
        if (existingBuffer != null) {
            return new Page(existingBuffer, compactionStrategy);
        }
        return new Page(pageId, pageSize, directOffHeap, compactionStrategy);
    }

    /**
     * Helper to read a page directly from a FileChannel.
     */
    public static Page fromChannel(FileChannel channel, long pageIndex, int pageSize, boolean directOffHeap) throws IOException {
        long fileOffset = pageIndex * pageSize;
        ByteBuffer buffer = directOffHeap ? ByteBuffer.allocateDirect(pageSize) : ByteBuffer.allocate(pageSize);
        buffer.position(0);
        long currentFilePos = fileOffset;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, currentFilePos);
            if (read < 0) {
                if (buffer.position() == 0) return null; // Clean EOF
                throw new IOException("Unexpected EOF while reading slotted page at offset " + fileOffset);
            }
            currentFilePos += read;
        }
        buffer.position(0);
        return new Page(buffer, new InPlacePageCompactionStrategy(), false);
    }

    /**
     * Maps a page directly into virtual memory using MappedByteBuffer for zero-copy I/O.
     */
    public static Page fromMappedChannel(FileChannel channel, FileChannel.MapMode mode, long pageIndex, int pageSize) throws IOException {
        long fileOffset = pageIndex * pageSize;
        java.nio.MappedByteBuffer mappedBuffer = channel.map(mode, fileOffset, pageSize);
        return new Page(mappedBuffer, new InPlacePageCompactionStrategy(), true);
    }
}
