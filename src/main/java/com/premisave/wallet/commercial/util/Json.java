package com.premisave.wallet.commercial.util;

/** Just enough JSON writing for filter level error bodies, where no mapper is available. */
public final class Json {

    private Json() {}

    public static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /** The standard error envelope: success false, message, and data holding code and requestId. */
    public static String errorBody(String message, String code, String requestId) {
        return "{\"success\":false,\"message\":" + quote(message)
                + ",\"data\":{\"code\":" + quote(code) + ",\"requestId\":" + quote(requestId) + "}}";
    }
}
