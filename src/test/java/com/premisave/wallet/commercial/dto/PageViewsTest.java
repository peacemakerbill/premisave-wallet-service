package com.premisave.wallet.commercial.dto;

import com.premisave.wallet.commercial.exception.CommercialException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PageViewsTest {

    @Test
    void defaults() {
        Pageable p = PageViews.pageable(null, null, null);
        assertEquals(0, p.getPageNumber());
        assertEquals(20, p.getPageSize());
    }

    @Test
    void acceptsRequestedValues() {
        Pageable p = PageViews.pageable(3, 100, "amount,asc");
        assertEquals(3, p.getPageNumber());
        assertEquals(100, p.getPageSize());
    }

    @Test
    void rejectsBadPagingAndSort() {
        assertThrows(CommercialException.class, () -> PageViews.pageable(-1, 10, null));
        assertThrows(CommercialException.class, () -> PageViews.pageable(0, 0, null));
        assertThrows(CommercialException.class, () -> PageViews.pageable(0, 101, null));
        assertThrows(CommercialException.class, () -> PageViews.pageable(0, 10, "password,asc"));
    }
}
