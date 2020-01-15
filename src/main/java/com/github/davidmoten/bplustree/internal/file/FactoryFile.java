package com.github.davidmoten.bplustree.internal.file;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.github.davidmoten.bplustree.Serializer;
import com.github.davidmoten.bplustree.internal.Factory;
import com.github.davidmoten.bplustree.internal.LargeMappedByteBuffer;
import com.github.davidmoten.bplustree.internal.Leaf;
import com.github.davidmoten.bplustree.internal.Node;
import com.github.davidmoten.bplustree.internal.NonLeaf;
import com.github.davidmoten.bplustree.internal.Options;
import com.github.davidmoten.guavamini.Lists;
import com.github.davidmoten.guavamini.Preconditions;

public final class FactoryFile<K, V> implements Factory<K, V> {

    private static final int NODE_TYPE_BYTES = 1;
    private static final int NUM_KEYS_BYTES = 1;
    private static final int NUM_NODES_BYTES = 4;
    private static final int POSITION_BYTES = 8;
    static final long POSITION_NOT_PRESENT = -1;
    private final Options<K, V> options;
    private final List<LeafFile<K, V>> leaves;
    private int leavesIndex = 0;

    // position where next node will be written, first 8 bytes are for the position
    // of the root node
    private long index = POSITION_BYTES;

    private long valuesIndex = 0; // position where next value will be written
    private final Serializer<K> keySerializer;
    private final Serializer<V> valueSerializer;
    private final LargeMappedByteBuffer bb;
    private final LargeMappedByteBuffer values;
    private final Runnable onClose;
    private final List<NonLeafFile<K, V>> nonLeaves;
    private int nonLeavesIndex = 0;

    @SuppressWarnings("unchecked")
    public FactoryFile(Options<K, V> options, File directory, Serializer<K> keySerializer,
            Serializer<V> valueSerializer, int segmentSizeBytes, Runnable onClose) {
        this.options = options;
        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;
        this.onClose = onClose;
        this.bb = new LargeMappedByteBuffer(directory, segmentSizeBytes, "index-");
        this.values = new LargeMappedByteBuffer(directory, segmentSizeBytes, "value-");
        this.leaves = Lists.newArrayList(new LeafFile<K, V>(options, this, -1),
                new LeafFile<K, V>(options, this, -1));
        {
            // worst case is there are log(N) non leaves used concurrently
            List<NonLeafFile<K, V>> list = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                list.add(new NonLeafFile<K, V>(this, -1));
            }
            this.nonLeaves = list;
        }
    }

    //////////////////////////////////////////////////
    // Format of a Leaf
    // NODE_TYPE NUM_KEYS (KEY VALUE)* NEXT_LEAF_POSITION
    // where
    // NODE_TYPE is one byte (0 = Leaf, 1 = NonLeaf)
    // NUM_KEYS is one byte unsigned
    // KEY is a byte array of fixed size
    // VALUE is a byte array of fixed size
    // NEXT_LEAF_POSITION is 8 bytes signed long
    // Every Leaf has space allocated for maxLeafKeys key value pairs
    //////////////////////////////////////////////////

    @Override
    public Leaf<K, V> createLeaf() {
        return getLeaf(leafNextPosition());
    }

    private final int CACHE_MAX_SIZE = 10; // must be at least 3
    private final ExpiringCache<Long, LeafFileCached<K, V>> leavesCache = new ExpiringCache<>(
            CACHE_MAX_SIZE);

    public LeafFileCached<K, V> getLeaf(long position) {
        LeafFileCached<K, V> leaf = leavesCache.get(position);
        if (leaf == null) {
            leaf = nextFreeLeaf(position);
        }
        return leaf;
    }

    LeafFile<K, V> nextFreeLeafOld() {
        LeafFile<K, V> leaf = leaves.get(leavesIndex);
        leavesIndex = (leavesIndex + 1) % leaves.size();
        return leaf;
    }

    LeafFileCached<K, V> nextFreeLeaf(long position) {
        if (leavesCache.isAtMaxSize()) {
            LeafFileCached<K, V> expired = leavesCache.expireOne();
            if (expired.position() == position) {
                leavesCache.put(position, expired);
            } else {
                expired.commit();
                expired.position(position);
                leavesCache.put(position, expired);
            }
            return expired;
        } else {
            LeafFileCached<K, V> leaf = new LeafFileCached<K, V>(this, position);
            leavesCache.put(position, leaf);
            return leaf;
        }
    }

    public void leafSetKey(long position, int i, K k) {
        bb.position(position + relativeLeafKeyPosition(i));
        keySerializer.write(bb, k);
    }

    private int leafBytes() {
        return relativeLeafKeyPosition(options.maxLeafKeys()) //
                + POSITION_BYTES; // next leaf position
    }

    private long leafNextPosition() {
        long i = index;
        bb.position(index);
        bb.put((byte) Leaf.TYPE);
        bb.position(index + leafBytes() - POSITION_BYTES);
        bb.putLong(POSITION_NOT_PRESENT);
        // shift by max size of a leaf node: numKeys, keys, values, next leaf position
        // (b+tree pointer to next leaf node)
        index += leafBytes();
        return i;
    }

    private int relativeLeafKeyPosition(int i) {
        return NODE_TYPE_BYTES + NUM_KEYS_BYTES + i * (keySerializer.maxSize() + POSITION_BYTES);
    }

    public K leafKey(long position, int i) {
        long p = position + relativeLeafKeyPosition(i);
        bb.position(p);
        return keySerializer.read(bb);
    }

    public int leafNumKeys(long position) {
        bb.position(position + NODE_TYPE_BYTES);
        return bb.get() & 0xFF;
    }

    public void leafSetNumKeys(long position, int numKeys) {
        bb.position(position + NODE_TYPE_BYTES);
        bb.put((byte) numKeys);
    }

    public V leafValue(long position, int i) {
        long p = position + relativeLeafKeyPosition(i) + keySerializer.maxSize();
        bb.position(p);
        long valuePos = bb.getLong();
        values.position(valuePos);
        return valueSerializer.read(values);
    }

    public void leafSetValue(long position, int i, V value) {
        long p = position + relativeLeafKeyPosition(i) + keySerializer.maxSize();
        bb.position(p);
        bb.putLong(valuesIndex);
        values.position(valuesIndex);
        valueSerializer.write(values, value);
        valuesIndex = values.position();
    }

    public void leafInsert(long position, int i, K key, V value) {
        int relativeStart = relativeLeafKeyPosition(i);
        int relativeFinish = relativeLeafKeyPosition(leafNumKeys(position));

        bb.position(position + relativeStart);
        byte[] bytes = new byte[relativeFinish - relativeStart];
        bb.get(bytes);

        // copy bytes across one key
        bb.position(position + relativeLeafKeyPosition(i + 1));
        bb.put(bytes);

        // write inserted key and value
        long p = position + relativeStart;
        bb.position(p);
        keySerializer.write(bb, key);
        bb.position(p + keySerializer.maxSize());
        bb.putLong(valuesIndex);
        values.position(valuesIndex);
        valueSerializer.write(values, value);
        valuesIndex = values.position();
        // increment number of keys in leaf node
        leafSetNumKeys(position, leafNumKeys(position) + 1);
    }

    public void leafMove(long position, int start, int length, LeafFile<K, V> other) {
        int relativeStart = relativeLeafKeyPosition(start);
        int relativeEnd = relativeLeafKeyPosition(start + length);
        byte[] bytes = new byte[relativeEnd - relativeStart];
        bb.position(position + relativeStart);
        bb.get(bytes);
        long p = other.position() + relativeLeafKeyPosition(0);
        bb.position(p);
        bb.put(bytes);
        // set the number of keys in source node to be `start`
        leafSetNumKeys(position, start);
        leafSetNumKeys(other.position(), length);
    }

    public void leafSetNext(long position, LeafFile<K, V> sibling) {
        long pos;
        if (sibling == null) {
            pos = POSITION_NOT_PRESENT;
        } else {
            pos = sibling.position();
        }
        leafSetNext(position, pos);
    }

    public void leafSetNext(long position, long next) {
        Preconditions.checkArgument(position != next);
        long p = position + relativeLeafKeyPosition(options.maxLeafKeys());
        bb.position(p);
        bb.putLong(next);
    }

    public LeafFile<K, V> leafNext(long position) {
        long p = leafNextPosition(position);
        if (p == POSITION_NOT_PRESENT) {
            return null;
        } else {
            return new LeafFile<K, V>(options, this, p);
        }
    }

    public long leafNextPosition(long position) {
        bb.position(position + relativeLeafKeyPosition(options.maxLeafKeys()));
        return bb.getLong();
    }

    //////////////////////////////////////////////////
    // Format of a NonLeaf
    // NODE_TYPE NUM_KEYS (LEFT_CHILD_POSITION KEY)* RIGHT_CHILD_POSITION
    // where
    // NODE_TYPE is one byte (0 = Leaf, 1 = NonLeaf)
    // NUM_KEYS is 1 byte unsigned
    // LEFT_CHILD_POSITION is 8 bytes signed long
    // KEY is a fixed size byte array
    // RIGHT_CHILD_POSITION is 8 bytes signed long
    // Every NonLeaf has space allocated for maxNonLeafKeys keys
    //////////////////////////////////////////////////

    @Override
    public NonLeafFile<K, V> createNonLeaf() {
        NonLeafFile<K, V> nonLeaf = nonLeaves.get(nonLeavesIndex);
        nonLeavesIndex = (nonLeavesIndex + 1) % nonLeaves.size();
        nonLeaf.position(nextNonLeafPosition());
        return nonLeaf;
    }

    private int nonLeafBytes() {
        // every key has a child node to the left and the final key has a child node to
        // the right as well as the left
        return NODE_TYPE_BYTES + NUM_NODES_BYTES
                + options.maxNonLeafKeys() * (POSITION_BYTES + keySerializer.maxSize())
                + POSITION_BYTES;
    }

    private long nextNonLeafPosition() {
        long i = index;
        bb.position(index);
        bb.put((byte) NonLeaf.TYPE);
        index += nonLeafBytes();
        return i;
    }

    public void nonLeafSetNumKeys(long position, int numKeys) {
        bb.position(position + NODE_TYPE_BYTES);
        bb.put((byte) numKeys);
    }

    public int nonLeafNumKeys(long position) {
        bb.position(position + NODE_TYPE_BYTES);
        return bb.get() & 0xFF;
    }

    public void nonLeafSetChild(long position, int i, NodeFile node) {
        long p = position + relativePositionNonLeafEntry(i);
        bb.position(p);
        bb.putLong(node.position());
    }

    private int relativePositionNonLeafEntry(int i) {
        return NODE_TYPE_BYTES + NUM_KEYS_BYTES + i * (POSITION_BYTES + keySerializer.maxSize());
    }

    public Node<K, V> nonLeafChild(long position, int i) {
        bb.position(position + relativePositionNonLeafEntry(i));
        long pos = bb.getLong();
        return readNode(pos);
    }

    private Node<K, V> readNode(long pos) {
        bb.position(pos);
        int type = bb.get();
        if (type == Leaf.TYPE) {
            return getLeaf(pos);
        } else {
            return new NonLeafFile<>(this, pos);
        }
    }

    public K nonLeafKey(long position, int i) {
        bb.position(position + relativePositionNonLeafEntry(i) + POSITION_BYTES);
        return keySerializer.read(bb);
    }

    public void nonLeafSetKey(long position, int i, K key) {
        bb.position(position + relativePositionNonLeafEntry(i) + POSITION_BYTES);
        keySerializer.write(bb, key);
    }

    public void nonLeafMove(long position, int mid, int length, NonLeafFile<K, V> other) {
        // read array corresponding to latter half of source node and put at beginning
        // of other node
        int relativeStart = relativePositionNonLeafEntry(mid);
        int size = relativePositionNonLeafEntry(mid + length + 1) - relativeStart;
        bb.position(position + relativeStart);
        byte[] bytes = new byte[size];
        bb.get(bytes);
        long newPosition = other.position() + relativePositionNonLeafEntry(0);
        bb.position(newPosition);
        bb.put(bytes);
        nonLeafSetNumKeys(position, mid - 1);
        nonLeafSetNumKeys(other.position(), length);
    }

    public void nonLeafInsert(long position, int i, K key, NodeFile left) {
        int numKeys = nonLeafNumKeys(position);
        int relativeStart = relativePositionNonLeafEntry(i);
        int relativeEnd = relativePositionNonLeafEntry(numKeys) + POSITION_BYTES;
        bb.position(position + relativeStart);
        byte[] bytes = new byte[relativeEnd - relativeStart];
        bb.get(bytes);
        bb.position(position + relativePositionNonLeafEntry(i + 1));
        bb.put(bytes);
        bb.position(position + relativeStart);
        bb.putLong(left.position());
        keySerializer.write(bb, key);
        nonLeafSetNumKeys(position, numKeys + 1);
    }

    @Override
    public void close() throws Exception {
        bb.close();
        values.close();
        if (onClose != null) {
            onClose.run();
        }
    }

    @Override
    public void commit() {
        bb.commit();
        values.commit();
        leavesCache.forEach((k, v) -> v.commit());
    }

    @Override
    public void root(Node<K, V> node) {
        bb.position(0);
        bb.putLong(((NodeFile) node).position());
    }

    @Override
    public Node<K, V> loadOrCreateRoot() {
        bb.position(0);
        long rootPosition = bb.getLong();
        if (rootPosition == 0) {
            bb.position(0);
            bb.putLong(POSITION_BYTES);
            return createLeaf();
        } else {
            return readNode(rootPosition);
        }
    }

    public Options<K, V> options() {
        return options;
    }
}
