package logss.btree;

public interface NonLeafStore<K,V> {
    
    void setNumKeys(int numKeys);
    
    int numKeys();

    void setChild(int index, Node<K, V> node);
    
    Node<K,V> getChild(int index);
    
}
