package com.smms.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageRequestsTest {

    private static final Map<String, String> ALLOWED = Map.of("createdAt", "createdAt", "name", "fullName");
    private static final Sort DEFAULT = Sort.by(Sort.Direction.DESC, "createdAt");

    private static Pageable of(int page, int size, List<String> sort) {
        return PageRequests.of(page, size, sort, ALLOWED, DEFAULT);
    }

    @Test
    void negativePageIsRejected() {
        assertThatThrownBy(() -> of(-1, 20, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hugePageIsRejected() {
        assertThatThrownBy(() -> of(PageRequests.MAX_PAGE + 1, 20, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(of(PageRequests.MAX_PAGE, 20, null).getPageNumber()).isEqualTo(PageRequests.MAX_PAGE);
    }

    @Test
    void zeroOrNegativeSizeIsRejected() {
        assertThatThrownBy(() -> of(0, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(0, -5, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oversizedSizeIsClampedToMax() {
        assertThat(of(0, 500, null).getPageSize()).isEqualTo(100);
        assertThat(of(0, Integer.MAX_VALUE, null).getPageSize()).isEqualTo(100);
        assertThat(of(0, 100, null).getPageSize()).isEqualTo(100);
        assertThat(of(2, 7, null).getPageSize()).isEqualTo(7);
    }

    @Test
    void defaultSortWhenMissingOrBlank() {
        assertThat(of(0, 20, null).getSort()).isEqualTo(DEFAULT);
        assertThat(of(0, 20, List.of()).getSort()).isEqualTo(DEFAULT);
        assertThat(of(0, 20, List.of(" ")).getSort()).isEqualTo(DEFAULT);
    }

    @Test
    void whitelistedSortAscAndDescMapsToEntityPath() {
        Sort desc = of(0, 20, List.of("name", "desc")).getSort();
        assertThat(desc.getOrderFor("fullName").getDirection()).isEqualTo(Sort.Direction.DESC);
        Sort asc = of(0, 20, List.of("name,asc")).getSort();
        assertThat(asc.getOrderFor("fullName").getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(of(0, 20, List.of("createdAt")).getSort().getOrderFor("createdAt").isAscending()).isTrue();
    }

    @Test
    void unknownOrSensitiveSortFieldIsRejected() {
        assertThatThrownBy(() -> of(0, 20, List.of("password"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(0, 20, List.of("passwordHash,desc"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(0, 20, List.of("fullName"))).isInstanceOf(IllegalArgumentException.class); // entity name, not api name
        assertThatThrownBy(() -> of(0, 20, List.of("createdAt; drop table users"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tooManySortFieldsIsRejected() {
        assertThatThrownBy(() -> PageRequests.of(0, 20,
            List.of("a", "b", "c", "d"), Map.of("a", "a", "b", "b", "c", "c", "d", "d"), DEFAULT))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
