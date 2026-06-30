package com.example.load.util;

/**
 * Tracks numeric item ids (1, 2, 3, ...). Analogous to the original's PhoneNumberTracker /
 * SipDomainTracker, but for a simple REST resource keyed by an integer id.
 */
public class ItemIdTracker extends ResourceTracker {

    public static final long DEFAULT_FIRST_ID = 1;

    public ItemIdTracker() {
        this(DEFAULT_FIRST_ID);
    }

    public ItemIdTracker(final long firstId) {
        this.firstItem = this.currentItem = firstId;
    }

    @Override
    public String toResource(final long id) {
        return String.valueOf(id);
    }
}
