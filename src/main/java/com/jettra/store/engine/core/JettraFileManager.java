package com.jettra.store.engine.core;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages reading and writing to .jettra storage files.
 * Uses FileChannel for optimized I/O operations.
 */
public class JettraFileManager {
    private final Path filePath;
    private final FileChannel fileChannel;

    public JettraFileManager(Path filePath) throws IOException {
        this.filePath = filePath;
        if (!Files.exists(filePath.getParent())) {
            Files.createDirectories(filePath.getParent());
        }
        // Open file for read/write. 'rw' mode.
        @SuppressWarnings("resource")
        RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw");
        this.fileChannel = raf.getChannel();
    }

    public synchronized long append(byte[] data) throws IOException {
        return append(data, false);
    }

    /**
     * Appends a block of data to the end of the file.
     * @param data The byte array to append.
     * @param forceSync If true, immediately invokes fileChannel.force(false)
     * @return The offset at which the data was written.
     */
    public synchronized long append(byte[] data, boolean forceSync) throws IOException {
        long offset = fileChannel.size();
        fileChannel.position(offset);
        
        // Write size header (4 bytes) followed by data
        ByteBuffer buffer = ByteBuffer.allocate(4 + data.length);
        buffer.putInt(data.length);
        buffer.put(data);
        buffer.flip();
        
        while (buffer.hasRemaining()) {
            fileChannel.write(buffer);
        }
        
        if (forceSync) {
            fileChannel.force(false);
        }
        return offset;
    }

    /**
     * Forces any updates to this file's channel to be written to the storage device.
     */
    public synchronized void force() throws IOException {
        if (fileChannel != null && fileChannel.isOpen()) {
            fileChannel.force(false);
        }
    }

    /**
     * Reads a block of data from a specific offset.
     * @param offset The file offset where the data begins.
     * @return The byte array read from the file.
     */
    public byte[] read(long offset) throws IOException {
        ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
        fileChannel.read(lengthBuffer, offset);
        lengthBuffer.flip();
        
        int length = lengthBuffer.getInt();
        ByteBuffer dataBuffer = ByteBuffer.allocate(length);
        fileChannel.read(dataBuffer, offset + 4);
        dataBuffer.flip();
        
        byte[] data = new byte[length];
        dataBuffer.get(data);
        return data;
    }

    public void close() throws IOException {
        if (fileChannel != null && fileChannel.isOpen()) {
            fileChannel.close();
        }
    }
}
