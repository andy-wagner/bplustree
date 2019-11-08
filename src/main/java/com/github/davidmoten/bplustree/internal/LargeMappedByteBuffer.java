package com.github.davidmoten.bplustree.internal;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.TreeMap;

import com.github.davidmoten.bplustree.LargeByteBuffer;

public final class LargeMappedByteBuffer implements AutoCloseable, LargeByteBuffer {

    private final int segmentSizeBytes;

    private final TreeMap<Long, Segment> map = new TreeMap<>();
    private final File directory;
    private final String segmentNamePrefix;

    private byte[] temp2Bytes = new byte[2];
    private byte[] temp4Bytes = new byte[4];
    private byte[] temp8Bytes = new byte[8];

    public LargeMappedByteBuffer(File directory, int segmentSizeBytes, String segmentNamePrefix) {
        this.directory = directory;
        this.segmentSizeBytes = segmentSizeBytes;
        this.segmentNamePrefix = segmentNamePrefix;
    }

    private long position;

    private MappedByteBuffer bb(long position) {
        // TODO close segments when map gets too many entries
        long num = segmentNumber(position);
        Segment segment = map.get(num);
        if (segment == null) {
            segment = createSegment(num);
        }
        segment.bb.position((int) (position % segmentSizeBytes));
        return segment.bb;
    }

    private Segment createSegment(long num) {
        File file = new File(directory, segmentNamePrefix + num);
        Segment segment = map(file, segmentSizeBytes);
        map.put(num, segment);
        return segment;
    }

    private static Segment map(File file, int segmentSizeBytes) {
        try {
            if (file.exists()) {
                if (file.length() != segmentSizeBytes) {
                    throw new IllegalStateException("segment file " + file + " should be of size "
                            + segmentSizeBytes + " but was of size " + file.length());
                }
            }
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.setLength(segmentSizeBytes);
            }
            FileChannel channel = (FileChannel) Files //
                    .newByteChannel(//
                            file.toPath(), //
                            StandardOpenOption.CREATE, //
                            StandardOpenOption.READ, //
                            StandardOpenOption.WRITE);

            // map the whole file
            MappedByteBuffer bb = channel.map(MapMode.READ_WRITE, 0, segmentSizeBytes);
            return new Segment(channel, bb);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void position(long newPosition) {
        this.position = newPosition;
    }

    @Override
    public byte get() {
        return bb(position++).get();
    }

    @Override
    public void put(byte b) {
        bb(position++).put(b);
    }

    @Override
    public void get(byte[] dst) {
        long p = position;
        if (segmentNumber(p) == segmentNumber(p + dst.length)) {
            bb(p).get(dst);
        } else {
            int i = 0;
            while (true) {
                long p2 = Math.min(segmentPosition(segmentNumber(p) + 1), position + dst.length);
                int length = (int) (p2 - p);
                if (length == 0) {
                    break;
                }
                bb(p).get(dst, i, length);
                i += length;
                p = p2;
            }
        }
        position += dst.length;
    }

    @Override
    public void put(byte[] src) {
        long p = position;
        if (segmentNumber(p) == segmentNumber(p + src.length)) {
            bb(p).put(src);
        } else {
            int i = 0;
            while (true) {
                long p2 = Math.min(segmentPosition(segmentNumber(p) + 1), position + src.length);
                int length = (int) (p2 - p);
                if (length == 0) {
                    break;
                }
                bb(p).put(src, i, length);
                i += length;
                p = p2;
            }
        }
        position += src.length;
    }

    @Override
    public int getInt() {
        long p = position;
        if (segmentNumber(p) == segmentNumber(p + Integer.BYTES)) {
            position += Integer.BYTES;
            return bb(p).getInt();
        } else {
            get(temp4Bytes);
            return toInt(temp4Bytes);
        }
    }

    @Override
    public void putInt(int value) {
        long p = position;
        if (segmentNumber(p) == segmentNumber(p + Integer.BYTES)) {
            bb(p).putInt(value);
            position += Integer.BYTES;
        } else {
            put(toBytes(value));
        }
    }

    @Override
    public long getLong() {
        long p = position;
        if (segmentNumber(p) == segmentNumber(p + Long.BYTES)) {
            position += Long.BYTES;
            return bb(p).getLong();
        } else {
            get(temp8Bytes);
            return toLong(temp8Bytes);
        }
    }

    @Override
    public void putLong(long value) {
        long p = position;
        if (segmentNumber(p) == segmentNumber(p + Long.BYTES)) {
            position += Long.BYTES;
            bb(p).putLong(value);
        } else {
            put(toBytes(value));
        }
    }

    @Override
    public long position() {
        return position;
    }

    @Override
    public short getShort() {
        long p = position;
        if (segmentNumber(p) == segmentNumber(p + Short.BYTES)) {
            position += Short.BYTES;
            return bb(p).getShort();
        } else {
            get(temp2Bytes);
            return toShort(temp2Bytes);
        }
    }

    @Override
    public void putShort(short value) {
        long p = position;
        if (segmentNumber(p) == segmentNumber(p + Short.BYTES)) {
            bb(p).putShort(value);
            position += Short.BYTES;
        } else {
            put(toBytes(value));
        }
    }

    private long segmentNumber(long position) {
        return position / segmentSizeBytes;
    }

    private long segmentPosition(long segmentNumber) {
        return segmentSizeBytes * segmentNumber;
    }

    private static byte[] toBytes(short n) {
        return ByteBuffer.allocate(Short.BYTES).putShort(n).array();
    }

    private static byte[] toBytes(int n) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(n).array();
    }

    private static byte[] toBytes(long n) {
        return ByteBuffer.allocate(Long.BYTES).putLong(n).array();
    }

    private short toShort(byte[] bytes) {
        short ret = 0;
        for (int i = 0; i < 2; i++) {
            ret <<= 8;
            ret |= bytes[i] & 0xFF;
        }
        return ret;
    }

    private static int toInt(byte[] bytes) {
        int ret = 0;
        for (int i = 0; i < 4; i++) {
            ret <<= 8;
            ret |= bytes[i] & 0xFF;
        }
        return ret;
    }

    private static long toLong(byte[] b) {
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result <<= 8;
            result |= (b[i] & 0xFF);
        }
        return result;
    }

    @Override
    public void commit() {
        for (Segment segment : map.values()) {
            segment.bb.force();
        }
    }

    @Override
    public void close() throws IOException {
        for (Segment segment : map.values()) {
            segment.close();
        }
        map.clear();
    }

    private static final class Segment {
        private final FileChannel channel;
        final MappedByteBuffer bb;

        Segment(FileChannel channel, MappedByteBuffer bb) {
            this.channel = channel;
            this.bb = bb;
        }

        public void close() throws IOException {
            channel.close();
        }

    }

}
