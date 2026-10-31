package com.smms.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a bounded, whitelisted {@link Pageable} from raw request parameters (SEC-H).
 * Controllers keep plain page/size/sort parameters instead of Spring's Pageable resolver so
 * clients can never pick an unbounded size or an arbitrary sort property.
 *
 *  - page must be in [0, MAX_PAGE], otherwise 400 (IllegalArgumentException)
 *  - size below 1 is a 400; size above MAX_SIZE is clamped to MAX_SIZE
 *  - sort is "field" or "field,asc|desc"; field must be a key of the whitelist (api name to entity path)
 */
public final class PageRequests {

    public static final int MAX_SIZE = 100;
    public static final int MAX_PAGE = 10_000;
    public static final int MAX_SORT_FIELDS = 3;

    private PageRequests() {}

    public static Pageable of(int page, int size, List<String> sort,
                              Map<String, String> allowedSort, Sort defaultSort) {
        if (page < 0) throw new IllegalArgumentException("page must be >= 0");
        if (page > MAX_PAGE) throw new IllegalArgumentException("page must be <= " + MAX_PAGE);
        if (size < 1) throw new IllegalArgumentException("size must be >= 1");
        int effectiveSize = Math.min(size, MAX_SIZE);
        return PageRequest.of(page, effectiveSize, resolveSort(sort, allowedSort, defaultSort));
    }

    private static Sort resolveSort(List<String> raw, Map<String, String> allowed, Sort defaultSort) {
        if (raw == null) return defaultSort;
        // Spring splits "createdAt,desc" into two list items, so flatten everything to tokens
        List<String> tokens = new ArrayList<>();
        for (String r : raw) {
            if (r == null) continue;
            for (String t : r.split(",")) {
                if (!t.isBlank()) tokens.add(t.trim());
            }
        }
        if (tokens.isEmpty()) return defaultSort;

        List<Sort.Order> orders = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String field = tokens.get(i);
            String column = allowed.get(field);
            if (column == null) throw new IllegalArgumentException("Unsupported sort field");
            Sort.Direction dir = Sort.Direction.ASC;
            if (i + 1 < tokens.size()) {
                String next = tokens.get(i + 1).toLowerCase(Locale.ROOT);
                if (next.equals("asc") || next.equals("desc")) {
                    dir = next.equals("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
                    i++;
                }
            }
            if (orders.size() >= MAX_SORT_FIELDS) throw new IllegalArgumentException("Too many sort fields");
            orders.add(new Sort.Order(dir, column));
        }
        return Sort.by(orders);
    }
}
