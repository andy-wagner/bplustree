package com.github.davidmoten.bplustree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.github.davidmoten.bplustree.internal.file.LeafFile;

public final class BPlusTreeFileTest {

    private static BPlusTree<Integer, Integer> create(int maxKeys) {
        return BPlusTree.<Integer, Integer>builder() //
                .factoryProvider(FactoryProvider //
                        .file() //
                        .directory("target") //
                        .keySerializer(Serializer.INTEGER) //
                        .valueSerializer(Serializer.INTEGER)) //
                .maxKeys(maxKeys) //
                .naturalOrder();
    }

    @Test
    public void testInsertOne() {
        BPlusTree<Integer, Integer> tree = create(2);
        tree.insert(3, 10);
        LeafFile<Integer, Integer> leaf = (LeafFile<Integer, Integer>) tree.root();
        assertEquals(1, leaf.numKeys());
        assertEquals(3, (int) leaf.key(0));
        assertEquals(10, (int) leaf.value(0));
        NodeWrapper<Integer, Integer> t = NodeWrapper.root(tree);
        assertEquals(Arrays.asList(3), t.keys());
        assertEquals(10, (int) tree.findFirst(3));
        assertNull(tree.findFirst(4));
    }

    @Test
    public void testInsertTwo() {
        BPlusTree<Integer, Integer> tree = create(2);
        tree.insert(3, 10);
        tree.insert(5, 20);
        LeafFile<Integer, Integer> leaf = (LeafFile<Integer, Integer>) tree.root();
        assertEquals(2, leaf.numKeys());
        assertEquals(3, (int) leaf.key(0));
        assertEquals(10, (int) leaf.value(0));
        assertEquals(5, (int) leaf.key(1));
        assertEquals(20, (int) leaf.value(1));
        NodeWrapper<Integer, Integer> t = NodeWrapper.root(tree);
        assertEquals(Arrays.asList(3, 5), t.keys());
        assertEquals(10, (int) tree.findFirst(3));
        assertEquals(20, (int) tree.findFirst(5));
    }

    @Test
    public void testInsertTwoReverseOrderWhichTestsInsertMethodOnLeaf() {
        BPlusTree<Integer, Integer> tree = create(2);
        tree.insert(5, 20);
        tree.insert(3, 10);
        LeafFile<Integer, Integer> leaf = (LeafFile<Integer, Integer>) tree.root();
        assertEquals(2, leaf.numKeys());
        assertEquals(3, (int) leaf.key(0));
        assertEquals(10, (int) leaf.value(0));
        assertEquals(5, (int) leaf.key(1));
        assertEquals(20, (int) leaf.value(1));
        NodeWrapper<Integer, Integer> t = NodeWrapper.root(tree);
        assertEquals(Arrays.asList(3, 5), t.keys());
        assertEquals(10, (int) tree.findFirst(3));
        assertEquals(20, (int) tree.findFirst(5));
    }

    @Test
    public void testInsertThreeWithMaxLeafKeysTwo() {
        BPlusTree<Integer, Integer> tree = create(2);
        tree.insert(3, 10);
        tree.insert(5, 20);
        tree.insert(7, 30);
        NodeWrapper<Integer, Integer> t = NodeWrapper.root(tree);
        assertEquals(Arrays.asList(5), t.keys());
        List<NodeWrapper<Integer, Integer>> children = t.children();
        assertEquals(2, children.size());
        assertEquals(Arrays.asList(3), children.get(0).keys());
        assertEquals(Arrays.asList(5, 7), children.get(1).keys());
        assertEquals(10, (int) tree.findFirst(3));
        assertEquals(20, (int) tree.findFirst(5));
        assertEquals(30, (int) tree.findFirst(7));
    }

    @Test
    public void testIntegerKeyStringValue() throws Exception {
        try (BPlusTree<Long, String> t = BPlusTree.<Long, String>builder().maxKeys(4) //
                .factoryProvider( //
                        FactoryProvider //
                                .file() //
                                .directory("target") //
                                .segmentSizeMB(10) //
                                .keySerializer(Serializer.LONG) //
                                .valueSerializer(Serializer.utf8(0)))
                .naturalOrder()) {
            t.insert(1L, "hi");
            t.insert(3L, "ambulance");
            t.insert(2L, "under the stars");
            t.commit();
            assertEquals("hi", t.findFirst(1L));
            assertEquals("under the stars", t.findFirst(2L));
            assertEquals("ambulance", t.findFirst(3L));
        }
    }

    @Test
    public void testInsertMany() {
        long t = System.currentTimeMillis();
        BPlusTree<Integer, Integer> tree = create(4);
        int n = 1000000;
        for (int i = 1; i <= n; i++) {
            tree.insert(i, i);
        }
        System.out.println(
                "insert rate = " + (n * 1000.0 / (System.currentTimeMillis() - t)) + " per second");
    }

}
