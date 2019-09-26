package logss.btree;

import java.io.PrintStream;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class BPlusTree<K, V> {

    private final Options<K, V> options;

    /**
     * Pointer to the root node. It may be a leaf or an inner node, but it is never
     * null.
     */
    private Node<K, V> root;

    /** Create a new empty tree. */
    private BPlusTree(int maxLeafKeys, int maxInnerKeys, Comparator<? super K> comparator) {
        this.options = new Options<K, V>(maxLeafKeys, maxInnerKeys, comparator,
                new Storage<K, V>(maxInnerKeys, maxLeafKeys));
        this.root = new Leaf<K, V>(options);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private static final int NOT_SPECIFIED = -1;

        private static final int DEFAULT_NUM_KEYS = 4;

        private int maxLeafKeys = NOT_SPECIFIED;
        private int maxInnerKeys = NOT_SPECIFIED;

        Builder() {
            // prevent instantiation
        }

        public Builder maxLeafKeys(int maxLeafKeys) {
            this.maxLeafKeys = maxLeafKeys;
            return this;
        }

        public Builder maxNonLeafKeys(int maxInnerKeys) {
            this.maxInnerKeys = maxInnerKeys;
            return this;
        }

        public Builder maxKeys(int maxKeys) {
            maxLeafKeys(maxKeys);
            return maxNonLeafKeys(maxKeys);
        }

        public <K, V> BPlusTree<K, V> comparator(Comparator<? super K> comparator) {
            if (maxLeafKeys == NOT_SPECIFIED) {
                if (maxInnerKeys == NOT_SPECIFIED) {
                    maxLeafKeys = DEFAULT_NUM_KEYS;
                    maxInnerKeys = DEFAULT_NUM_KEYS;
                } else {
                    maxLeafKeys = maxInnerKeys;
                }
            } else if (maxInnerKeys == NOT_SPECIFIED) {
                maxInnerKeys = maxLeafKeys;
            }
            return new BPlusTree<K, V>(maxLeafKeys, maxInnerKeys, comparator);
        }

        public <K extends Comparable<K>, V> BPlusTree<K, V> naturalOrder() {
            return comparator(Comparator.naturalOrder());
        }
    }

    public void insert(K key, V value) {
        Split<K, V> result = root.insert(key, value);
        if (result != null) {
            // The root is split into two parts.
            // We create a new root pointing to them
            root = //
                    new NonLeaf<K, V>(options) //
                            .setNumKeys(1) //
                            .setKey(0, result.key) //
                            .setChild(0, result.left) //
                            .setChild(1, result.right);
        }
    }

    /**
     * Looks for the given key. If it is not found, it returns null. If it is found,
     * it returns the associated value.
     */
    public V find(K key) {
        Leaf<K, V> leaf = findFirstLeaf(key);
        int idx = leaf.getLocation(key);
        if (idx < leaf.numKeys() && leaf.key(idx).equals(key)) {
            return leaf.value(idx);
        } else {
            return null;
        }
    }

    private Leaf<K, V> findFirstLeaf(K key) {
        Node<K, V> node = root;
        while (node instanceof NonLeaf) { // need to traverse down to the leaf
            NonLeaf<K, V> inner = (NonLeaf<K, V>) node;
            int idx = inner.getLocation(key);
            node = inner.child(idx);
        }
        // We are @ leaf after while loop
        return (Leaf<K, V>) node;
    }

    /**
     * Returns an in-order sequence of values whose keys are >= start and < finish.
     * 
     * @param start
     *            inclusive end of search
     * @param finish
     *            exclusive end of search
     * @return
     */
    public Iterable<V> find(K start, K finish) {
        return find(start, finish, false);
    }

    public Iterable<V> find(K start, K finish, boolean finishInclusive) {
        return new Iterable<V>() {

            @Override
            public Iterator<V> iterator() {
                return new Iterator<V>() {
                    Leaf<K, V> leaf = findFirstLeaf(start);
                    int idx = leaf.getLocation(start);
                    V value;

                    @Override
                    public boolean hasNext() {
                        load();
                        return value != null;
                    }

                    @Override
                    public V next() {
                        load();
                        V v = value;
                        value = null;
                        if (v == null) {
                            throw new NoSuchElementException();
                        } else {
                            return v;
                        }
                    }

                    private void load() {
                        if (value != null) {
                            return;
                        }
                        while (true) {
                            if (leaf == null) {
                                return;
                            } else if (idx < leaf.numKeys()) {
                                int c = options.comparator.compare(leaf.key(idx), finish);
                                if (c < 0 || (c == 0 && finishInclusive)) {
                                    value = leaf.value(idx);
                                    idx++;
                                } else {
                                    // don't search further
                                    leaf = null;
                                }
                                return;
                            } else {
                                leaf = leaf.next();
                                idx = 0;
                            }
                        }
                    }

                };
            }

        };
    }

    public void print(PrintStream out) {
        print(root, 0, out);
    }

    public void print() {
        print(System.out);
    }

    private static <K, V> void print(Node<K, V> node, int level, PrintStream out) {
        if (node instanceof Leaf) {
            print((Leaf<K, V>) node, level, out);
        } else {
            print((NonLeaf<K, V>) node, level, out);
        }
    }

    private static <K, V> void print(Leaf<K, V> node, int level, PrintStream out) {
        out.print(indent(level));
        out.print("Leaf: ");
        int n = node.store.numKeys();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                out.print(", ");
            }
            out.print(node.store.key(i));
            out.print("->");
            out.print(node.store.value(i));
        }
        if (node.next() != null) {
            out.print("| -> " + node.next().keys());
        }
        out.println();
    }

    private static <K, V> void print(NonLeaf<K, V> node, int level, PrintStream out) {
        out.print(indent(level));
        out.println("NonLeaf");
        int n = node.store.numKeys();
        for (int i = 0; i < n; i++) {
            Node<K, V> nd = node.store.child(i);
            print(nd, level + 1, out);
            out.print(indent(level) + node.store.key(i));
            out.println();
        }
        if (node.store.child(n) != null) {
            print(node.store.child(n), level + 1, out);
        }
        out.println();
    }

    private static String indent(int level) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < level; i++) {
            b.append("  ");
        }
        return b.toString();
    }

    public Node<K, V> root() {
        return root;
    }

}