package com.jettra.store.engine.highperformance.graph;

import com.jettra.store.engine.highperformance.paged.RecordId;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Fast binary object graph serializer inspired by Eclipse Store architecture.
 *
 * <p>Directly serializes Java native object graphs to compact byte representations,
 * eliminating the impedance mismatch, overhead, and GC pressure of traditional ORMs
 * or heavy JSON reflection serializers.
 */
public class ObjectGraphSerializer {

    // Type tags for primitive & standard structures
    public static final byte TYPE_NULL = 0;
    public static final byte TYPE_STRING = 1;
    public static final byte TYPE_INT = 2;
    public static final byte TYPE_LONG = 3;
    public static final byte TYPE_DOUBLE = 4;
    public static final byte TYPE_BOOLEAN = 5;
    public static final byte TYPE_BYTE_ARRAY = 6;
    public static final byte TYPE_LIST = 7;
    public static final byte TYPE_MAP = 8;
    public static final byte TYPE_RECORD_ID = 9;
    public static final byte TYPE_JAVA_OBJECT = 10;

    /**
     * Serializes any supported Java object directly to an optimized byte array.
     */
    public byte[] serialize(Object object) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            writeObject(out, object);
            out.flush();
        }
        return baos.toByteArray();
    }

    /**
     * Deserializes a Java object directly from an optimized byte array.
     */
    public Object deserialize(byte[] data) throws IOException, ClassNotFoundException {
        if (data == null || data.length == 0) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            return readObject(in);
        }
    }

    private void writeObject(DataOutputStream out, Object obj) throws IOException {
        if (obj == null) {
            out.writeByte(TYPE_NULL);
            return;
        }

        if (obj instanceof String str) {
            out.writeByte(TYPE_STRING);
            byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
            out.writeInt(bytes.length);
            out.write(bytes);
        } else if (obj instanceof Integer i) {
            out.writeByte(TYPE_INT);
            out.writeInt(i);
        } else if (obj instanceof Long l) {
            out.writeByte(TYPE_LONG);
            out.writeLong(l);
        } else if (obj instanceof Double d) {
            out.writeByte(TYPE_DOUBLE);
            out.writeDouble(d);
        } else if (obj instanceof Boolean b) {
            out.writeByte(TYPE_BOOLEAN);
            out.writeBoolean(b);
        } else if (obj instanceof byte[] bytes) {
            out.writeByte(TYPE_BYTE_ARRAY);
            out.writeInt(bytes.length);
            out.write(bytes);
        } else if (obj instanceof RecordId rid) {
            out.writeByte(TYPE_RECORD_ID);
            out.writeInt(rid.fileId());
            out.writeLong(rid.pageIndex());
            out.writeInt(rid.offset());
        } else if (obj instanceof List<?> list) {
            out.writeByte(TYPE_LIST);
            out.writeInt(list.size());
            for (Object item : list) {
                writeObject(out, item);
            }
        } else if (obj instanceof Map<?, ?> map) {
            out.writeByte(TYPE_MAP);
            out.writeInt(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                writeObject(out, entry.getKey());
                writeObject(out, entry.getValue());
            }
        } else if (obj instanceof Serializable) {
            out.writeByte(TYPE_JAVA_OBJECT);
            out.writeUTF(obj.getClass().getName());
            ByteArrayOutputStream objBaos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(objBaos)) {
                oos.writeObject(obj);
                oos.flush();
            }
            byte[] objBytes = objBaos.toByteArray();
            out.writeInt(objBytes.length);
            out.write(objBytes);
        } else {
            throw new IllegalArgumentException("Unsupported object graph type for direct persistence: " + obj.getClass());
        }
    }

    private Object readObject(DataInputStream in) throws IOException, ClassNotFoundException {
        byte type = in.readByte();
        return switch (type) {
            case TYPE_NULL -> null;
            case TYPE_STRING -> {
                int len = in.readInt();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                yield new String(bytes, StandardCharsets.UTF_8);
            }
            case TYPE_INT -> in.readInt();
            case TYPE_LONG -> in.readLong();
            case TYPE_DOUBLE -> in.readDouble();
            case TYPE_BOOLEAN -> in.readBoolean();
            case TYPE_BYTE_ARRAY -> {
                int len = in.readInt();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                yield bytes;
            }
            case TYPE_RECORD_ID -> {
                int fileId = in.readInt();
                long pageIndex = in.readLong();
                int offset = in.readInt();
                yield new RecordId(fileId, pageIndex, offset);
            }
            case TYPE_LIST -> {
                int size = in.readInt();
                List<Object> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(readObject(in));
                }
                yield list;
            }
            case TYPE_MAP -> {
                int size = in.readInt();
                Map<Object, Object> map = new LinkedHashMap<>(size);
                for (int i = 0; i < size; i++) {
                    Object key = readObject(in);
                    Object val = readObject(in);
                    map.put(key, val);
                }
                yield map;
            }
            case TYPE_JAVA_OBJECT -> {
                String className = in.readUTF();
                int len = in.readInt();
                byte[] objBytes = new byte[len];
                in.readFully(objBytes);
                try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(objBytes))) {
                    yield ois.readObject();
                }
            }
            default -> throw new IllegalStateException("Unknown object graph serialization tag: " + type);
        };
    }
}
