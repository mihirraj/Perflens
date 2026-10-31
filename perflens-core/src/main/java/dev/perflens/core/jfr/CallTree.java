package dev.perflens.core.jfr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded prefix tree of sampled stacks (root = thread entry, leaves = executing frame).
 * Written only by the single JFR consumer thread; readers call {@link #toJson} under the
 * profiler's lock. Plain longs, no atomics.
 */
final class CallTree {
    static final String TRUNCATED = "[node cap reached]";

    static final class Node {
        final String name;
        long total;
        long self;
        HashMap<String, Node> children;

        Node(String name) {
            this.name = name;
        }
    }

    private final int maxNodes;
    private Node root = new Node("all");
    private int nodes = 1;

    CallTree(int maxNodes) {
        this.maxNodes = maxNodes;
    }

    /** @param rootToLeaf frame names ordered from the thread's entry point down to the executing frame */
    void add(List<String> rootToLeaf, long weight) {
        Node node = root;
        node.total += weight;
        for (String name : rootToLeaf) {
            if (node.children == null) node.children = new HashMap<>(4);
            Node child = node.children.get(name);
            if (child == null) {
                if (nodes >= maxNodes) {
                    child = node.children.computeIfAbsent(TRUNCATED, Node::new);
                    child.total += weight;
                    child.self += weight;
                    return;
                }
                child = new Node(name);
                node.children.put(name, child);
                nodes++;
            }
            child.total += weight;
            node = child;
        }
        node.self += weight;
    }

    long total() {
        return root.total;
    }

    int nodeCount() {
        return nodes;
    }

    void reset() {
        root = new Node("all");
        nodes = 1;
    }

    /** d3-flame-graph compatible: {name, value, children}. Nodes below minValue are dropped. */
    Map<String, Object> toJson(long minValue) {
        return toJson(root, Math.max(1, minValue));
    }

    private Map<String, Object> toJson(Node n, long minValue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", n.name);
        m.put("value", n.total);
        if (n.self > 0) m.put("self", n.self);
        if (n.children != null) {
            List<Node> kids = new ArrayList<>(n.children.values());
            kids.sort(Comparator.comparingLong((Node k) -> k.total).reversed());
            List<Map<String, Object>> out = new ArrayList<>();
            for (Node k : kids) {
                if (k.total < minValue) break;
                out.add(toJson(k, minValue));
            }
            if (!out.isEmpty()) m.put("children", out);
        }
        return m;
    }
}
