package com.github.davidmoten.bplustree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import com.github.davidmoten.bplustree.internal.file.LeafFile;

public final class BPlusTreeFileTest {

    private static BPlusTree<Integer, Integer> create(int maxKeys) {

        return BPlusTree.file() //
                .directory(Testing.newDirectory()) //
                .maxKeys(maxKeys) //
                .segmentSizeMB(1) //
                .keySerializer(Serializer.INTEGER) //
                .valueSerializer(Serializer.INTEGER) //
                .naturalOrder();
    }

    @Test
    public void testInsertOne() {
        BPlusTree<Integer, Integer> tree = create(2);
        tree.insert(3, 10);
        tree.commit();
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
    public void testInsertMany() {
        for (int j = 0; j < 1; j++) {
            long t = System.currentTimeMillis();
            int n = 1000000;
            int numKeysPerNode = 32;
            {
                BPlusTree<Integer, Integer> tree = create(numKeysPerNode);
                for (int i = 1; i <= n; i++) {
                    int v = n - i + 1;
                    tree.insert(v, v);
                }
                System.out.println("insert rate desc order= "
                        + (n * 1000.0 / (System.currentTimeMillis() - t)) + " per second");
            }
            {
                BPlusTree<Integer, Integer> tree = create(numKeysPerNode);
                for (int i = 1; i <= n; i++) {
                    tree.insert(i, i);
                }
                System.out.println("insert rate asc order = "
                        + (n * 1000.0 / (System.currentTimeMillis() - t)) + " per second");
            }
        }
    }

    @Test
    public void testRegexSpeed() {
        String s = "2019-11-06 23:13:00.427 DEBUG com.zaxxer.hikari.pool.HikariPool [HikariPool-2 housekeeper] - HikariPool-2 - Before cleanup stats (total=5, active=3, idle=2, waiting=0)";
        Pattern p = Pattern.compile(
                "^.*com.zaxxer.hikari.pool.HikariPool.*Before cleanup stats.*, active=([0-9]+).*$");
        long t = System.currentTimeMillis();
        int n = 100000;
        for (int i = 0; i < n; i++) {
            Matcher m = p.matcher(s);
            if (m.find()) {
                if (m.group(1).equals("blah")) {
                    System.out.println("hello");
                }
            }
        }
        System.out.println("regex match rate = " + n * 1000.0 / (System.currentTimeMillis() - t)
                + " lines per second");
    }

    public static void main(String[] args) {
        BPlusTree<Long, Long> tree = BPlusTree.file() //
                .directory(Testing.newDirectory()) //
                .maxKeys(8).keySerializer(Serializer.LONG) //
                .valueSerializer(Serializer.LONG) //
                .naturalOrder();
        long i = 1;
        long t = System.currentTimeMillis();
        while (true) {
            tree.insert(i, i);
            if (i % 1000000 == 0) {
                long t2 = System.currentTimeMillis();
                System.out.println(i / 1000000 + "m, insertRate=" + 1000000 * 1000.0 / (t2 - t)
                        + " per second");
                t = t2;
            }
            i++;
        }
    }

}
