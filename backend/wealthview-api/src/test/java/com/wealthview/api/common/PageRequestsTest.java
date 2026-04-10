package com.wealthview.api.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageRequestsTest {

    @Test
    void clampSize_withinLimit_returnsSameValue() {
        assertThat(PageRequests.clampSize(25)).isEqualTo(25);
        assertThat(PageRequests.clampSize(PageRequests.MAX_PAGE_SIZE))
                .isEqualTo(PageRequests.MAX_PAGE_SIZE);
    }

    @Test
    void clampSize_overLimit_returnsMax() {
        assertThat(PageRequests.clampSize(PageRequests.MAX_PAGE_SIZE + 1))
                .isEqualTo(PageRequests.MAX_PAGE_SIZE);
        assertThat(PageRequests.clampSize(1_000_000)).isEqualTo(PageRequests.MAX_PAGE_SIZE);
    }

    @Test
    void clampSize_zeroOrNegative_returnsOne() {
        assertThat(PageRequests.clampSize(0)).isEqualTo(1);
        assertThat(PageRequests.clampSize(-5)).isEqualTo(1);
    }

    @Test
    void maxPageSize_isSaneDefault() {
        assertThat(PageRequests.MAX_PAGE_SIZE).isEqualTo(200);
    }
}
