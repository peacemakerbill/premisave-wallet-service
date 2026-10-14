package com.premisave.wallet.commercial.exception;

import com.premisave.wallet.commercial.enums.ErrorCode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single exception type for every expected failure in the commercial API.
 * The advice turns it into the standard ApiResponse envelope with the code
 * and any extra fields placed inside data.
 */
public class CommercialException extends RuntimeException {

    private static final long serialVersionUID = 1L;
	private final ErrorCode code;
    private final Map<String, Object> extra = new LinkedHashMap<>();
    private List<Map<String, String>> details;

    public CommercialException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() { return code; }

    public Map<String, Object> extra() { return extra; }

    public List<Map<String, String>> details() { return details; }

    public CommercialException with(String key, Object value) {
        extra.put(key, value);
        return this;
    }

    public CommercialException withDetails(List<Map<String, String>> details) {
        this.details = details;
        return this;
    }

    public static CommercialException validation(String field, String issue) {
        return new CommercialException(ErrorCode.VALIDATION_FAILED, field + ": " + issue)
                .withDetails(List.of(Map.of("field", field, "issue", issue)));
    }

    public static CommercialException notFound(String what) {
        return new CommercialException(ErrorCode.NOT_FOUND, what + " not found");
    }

    public static CommercialException invalidState(String message) {
        return new CommercialException(ErrorCode.INVALID_STATE, message);
    }
}
