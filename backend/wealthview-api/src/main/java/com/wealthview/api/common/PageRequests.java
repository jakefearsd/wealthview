package com.wealthview.api.common;

public final class PageRequests {

    public static final int MAX_PAGE_SIZE = 200;

    private PageRequests() {
    }

    public static int clampSize(int requested) {
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }
}
