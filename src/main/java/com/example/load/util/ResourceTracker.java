package com.example.load.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates deterministic, repeatable identifiers for the load test, exactly like the original
 * project's ResourceTracker. The setup phase and the load phase both derive ids from the same
 * counter, so the load phase can reference data the setup phase created.
 *
 * <ul>
 *   <li>{@link #create()} / {@link #addNew()} mint sequential ids during setup.</li>
 *   <li>{@link #getNextInLoop()} cycles through created ids (for "this should return 200" calls).</li>
 *   <li>{@link #getUniqueUnusedItem()} returns an id that was never created (for "expect 404" calls).</li>
 * </ul>
 *
 * New ids cannot be added once {@link #startIterators()} has been called (iterating + mutating an
 * ArrayList is unsafe), which matches the original's contract.
 */
public abstract class ResourceTracker {

    private static final Logger log = LoggerFactory.getLogger(ResourceTracker.class);
    public static final long SAFE_UNUSED_MARGIN = 100_000;

    protected final ArrayList<String> items = new ArrayList<>();
    protected long firstItem;
    protected long currentItem;
    protected Iterator<String> loopIterator;
    protected boolean initialised = false;
    protected final AtomicInteger unusedItemCounter = new AtomicInteger(0);

    /** Translate a numeric id into its string form. Subclasses choose the format. */
    public abstract String toResource(long id);

    public int getSize() {
        return items.size();
    }

    public ArrayList<String> getItems() {
        return items;
    }

    public synchronized void startIterators() {
        if (!initialised) {
            if (items.isEmpty()) {
                log.warn("Starting iterators over an empty tracker");
            } else {
                log.info("Starting iterators from {} to {}", items.get(0), items.get(items.size() - 1));
            }
            loopIterator = items.iterator();
            initialised = true;
        }
    }

    public synchronized String addNew() {
        final String s = create();
        add(s);
        return s;
    }

    public synchronized void add(final String item) {
        if (initialised) {
            throw new IllegalStateException("Cannot add items after iterators were started");
        }
        items.add(item);
    }

    public synchronized String create() {
        if (initialised) {
            throw new IllegalStateException("Cannot create items after iterators were started");
        }
        final String s = toResource(currentItem);
        currentItem++;
        return s;
    }

    /** Circular iterator: cycles back to the first id after the last one is returned. */
    public synchronized String getNextInLoop() {
        if (loopIterator == null) {
            throw new IllegalStateException("Iterators are not initialized; call startIterators() first");
        }
        if (!loopIterator.hasNext()) {
            loopIterator = items.iterator();
        }
        return loopIterator.next();
    }

    public synchronized long getCurrentItem() {
        return currentItem;
    }

    public synchronized long getFirstItem() {
        return firstItem;
    }

    /** An id guaranteed never to have been created, so the server should answer 404. */
    public synchronized long getUniqueUnusedItem() {
        return getCurrentItem() + SAFE_UNUSED_MARGIN + unusedItemCounter.incrementAndGet();
    }

    public synchronized boolean isInitialised() {
        return initialised;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{size=" + items.size()
                + ", firstItem=" + firstItem
                + ", currentItem=" + currentItem
                + ", initialised=" + initialised + '}';
    }
}
