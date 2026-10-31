package dev.perflens.core.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Keeps the K slowest exemplars. Check {@link #accepts} before paying to build one. */
public final class TopK {
    private final int capacity;
    private final List<Exemplar> items = new ArrayList<>();

    public TopK(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized boolean accepts(double durationMs) {
        return items.size() < capacity || durationMs > items.get(items.size() - 1).durationMs();
    }

    public synchronized void add(Exemplar e) {
        if (!accepts(e.durationMs())) return;
        items.add(e);
        items.sort(Comparator.comparingDouble(Exemplar::durationMs).reversed());
        if (items.size() > capacity) items.remove(items.size() - 1);
    }

    public synchronized void clear() {
        items.clear();
    }

    public synchronized List<Exemplar> snapshot() {
        return List.copyOf(items);
    }
}
