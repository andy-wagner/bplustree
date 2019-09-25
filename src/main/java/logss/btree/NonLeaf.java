package logss.btree;

import java.io.PrintStream;

public final class NonLeaf<K, V> implements Node<K, V> {

    private final Options<K, V> options;
    final NonLeafStore<K, V> store;

    NonLeaf(Options<K, V> options) {
        this.options = options;
        this.store = options.storage.createNonLeafStore();
    }

    NonLeaf<K, V> setChild(int index, Node<K, V> node) {
        store.setChild(index, node);
        return this;
    }

    Node<K, V> child(int index) {
        return store.child(index);
    }

    NonLeaf<K, V> setNumKeys(int numKeys) {
        store.setNumKeys(numKeys);
        return this;
    }

    NonLeaf<K, V> setKey(int index, K key) {
        store.setKey(index, key);
        return this;
    }

    /**
     * Returns the position where 'key' should be inserted in an inner node that has
     * the given keys.
     */
    @Override
    public int getLocation(K key) {
        // Simple linear search. Faster for small values of N or M
        int numKeys = store.numKeys();
        for (int i = 0; i < numKeys; i++) {
            if (options.comparator.compare(store.key(i), key) > 0) {
                return i;
            }
        }
        return numKeys;
        // Binary search is faster when N or M is big,
    }

    @Override
    public Split<K, V> insert(K key, V value) {
        /*
         * Early split if node is full. This is not the canonical algorithm for B+
         * trees, but it is simpler and it does break the definition which might result
         * in immature split, which might not be desired in database because additional
         * split lead to tree's height increase by 1, thus the number of disk read so
         * first search to the leaf, and split from bottom up is the correct approach.
         */

        if (store.numKeys() == options.maxNonLeafKeys) { // Split
            int mid = (options.maxNonLeafKeys + 1) / 2;
            int len = store.numKeys() - mid;
            NonLeaf<K, V> sibling = new NonLeaf<K, V>(options);
            store.move(mid, sibling, len);

            // Set up the return variable
            Split<K, V> result = new Split<>(store.key(mid - 1), this, sibling);

            // Now insert in the appropriate sibling
            if (options.comparator.compare(key, result.key) < 0) {
                insertNonfull(key, value);
            } else {
                sibling.insertNonfull(key, value);
            }
            return result;

        } else {// No split
            insertNonfull(key, value);
            return null;
        }
    }

    private void insertNonfull(K key, V value) {
        // Simple linear search
        int idx = getLocation(key);
        Split<K, V> result = store.child(idx).insert(key, value);

        if (result != null) {
            if (idx == store.numKeys()) {
                // Insertion at the rightmost key
                store.setKey(idx, result.key);
                store.setChild(idx, result.left);
                store.setChild(idx + 1, result.right);
            } else {
                // Insertion not at the rightmost key
                // shift i>idx to the right
                store.insert(idx, result.key, result.left);
                store.setChild(idx + 1, result.right);
            }
            store.setNumKeys(store.numKeys() + 1);
        } // else the current node is not affected
    }

    /**
     * This one only dump integer key
     */
    @Override
    public void dump() {
        System.out.println("iNode h==?");
        int numKeys = store.numKeys();
        for (int i = 0; i < numKeys; i++) {
            store.child(i).dump();
            System.out.print('>');
            System.out.println(store.key(i));
        }
        store.child(numKeys).dump();
    }

}