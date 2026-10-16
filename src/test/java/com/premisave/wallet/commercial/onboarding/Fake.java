package com.premisave.wallet.commercial.onboarding;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.Function;

/** Tiny stand in for Spring Data repositories: only the methods a test names exist, anything else fails loudly. */
public final class Fake {

    private Fake() {}

    @SuppressWarnings("unchecked")
    public static <T> T of(Class<T> type, Map<String, Function<Object[], Object>> handlers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            Function<Object[], Object> h = handlers.get(method.getName());
            if (h != null) return h.apply(args == null ? new Object[0] : args);
            return switch (method.getName()) {
                case "toString" -> "Fake(" + type.getSimpleName() + ")";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(type.getSimpleName() + "." + method.getName());
            };
        });
    }
}
