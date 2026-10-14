package com.premisave.wallet.commercial.dto;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.premisave.wallet.commercial.exception.CommercialException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Shared list response shape and paging input handling (section 1.5 of the endpoint reference). */
public final class PageViews {

    private static final int MAX_SIZE = 100;
    private static final Set<String> SORTABLE = Set.of("createdAt", "updatedAt", "amount");

    private PageViews() {}

    public static <T> Map<String, Object> of(Page<T> page, Function<T, Map<String, Object>> view) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> content = page.getContent().stream().map(view).toList();
        m.put("content", content);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("size", page.getSize());
        info.put("number", page.getNumber());
        info.put("totalElements", page.getTotalElements());
        info.put("totalPages", page.getTotalPages());
        m.put("page", info);
        return m;
    }

    /** page is zero based, size is capped at 100, sort looks like createdAt,desc and defaults to newest first. */
    public static Pageable pageable(Integer page, Integer size, String sort) {
        int p = page == null ? 0 : page;
        int s = size == null ? 20 : size;
        if (p < 0) throw CommercialException.validation("page", "must be zero or more");
        if (s < 1 || s > MAX_SIZE) throw CommercialException.validation("size", "must be between 1 and " + MAX_SIZE);

        String field = "createdAt";
        Sort.Direction dir = Sort.Direction.DESC;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            field = parts[0].trim();
            if (!SORTABLE.contains(field)) {
                throw CommercialException.validation("sort", "can be createdAt, updatedAt or amount");
            }
            if (parts.length > 1) {
                dir = parts[1].trim().equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
            }
        }
        String mapped = field.equals("amount") ? "amountCents" : field;
        return PageRequest.of(p, s, Sort.by(dir, mapped));
    }
}
