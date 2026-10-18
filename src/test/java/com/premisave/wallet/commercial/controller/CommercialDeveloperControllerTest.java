package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.config.CommercialProperties;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.service.DeveloperServiceClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommercialDeveloperControllerTest {

    private static final String PAGE = "{\"success\":true,\"message\":\"Developers retrieved\",\"data\":{\"content\":[{\"developerId\":\"d1\",\"email\":\"jane@acme.co.ke\"}],\"totalElements\":1}}";

    /** A local stand-in for the developer service that records what it was asked. */
    private static final class Upstream implements AutoCloseable {
        final HttpServer server;
        volatile String uri;
        volatile String key;
        volatile int status = 200;
        volatile String body = PAGE;
        volatile long delayMs;

        Upstream() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/admin/developers", ex -> {
                if (delayMs > 0) {
                    try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                uri = ex.getRequestURI().toString();
                key = ex.getRequestHeaders().getFirst("X-API-Key");
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                try {
                    ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
                    if (out.length > 0) ex.getResponseBody().write(out);
                } catch (IOException ignored) {
                    // the caller gave up waiting
                } finally {
                    ex.close();
                }
            });
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        CommercialDeveloperController controller(String apiKey) {
            return controllerFor("http://127.0.0.1:" + port() + "/", apiKey);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static CommercialDeveloperController controllerFor(String url, String apiKey) {
        return controllerFor(url, apiKey, 5);
    }

    private static CommercialDeveloperController controllerFor(String url, String apiKey, int timeoutSeconds) {
        return new CommercialDeveloperController(new DeveloperServiceClient(
                new CommercialProperties.Events(url, apiKey, null, null, null, timeoutSeconds, null)));
    }

    @Test
    void theDeveloperServiceAnswerIsReturnedUnchangedWithTheKey() throws Exception {
        try (Upstream up = new Upstream()) {
            ResponseEntity<String> r = up.controller("dev-key").search("jane@", "active", 0, 20);
            assertEquals(200, r.getStatusCode().value());
            assertEquals(PAGE, r.getBody());
            assertEquals("dev-key", up.key);
            assertEquals("/internal/admin/developers?q=jane%40&status=ACTIVE&page=0&size=20", up.uri);
        }
    }

    @Test
    void blankFiltersAreNotSent() throws Exception {
        try (Upstream up = new Upstream()) {
            up.controller("k").search("  ", " ", null, null);
            assertEquals("/internal/admin/developers", up.uri);
        }
    }

    @Test
    void textIsTrimmedAndEncoded() throws Exception {
        try (Upstream up = new Upstream()) {
            up.controller("k").search("  Acme & Sons  ", null, null, null);
            assertEquals("/internal/admin/developers?q=Acme+%26+Sons", up.uri);
        }
    }

    @Test
    void aBadRequestFromTheDeveloperServiceIsPassedOn() throws Exception {
        try (Upstream up = new Upstream()) {
            up.status = 400;
            up.body = "{\"success\":false,\"message\":\"size too large\"}";
            ResponseEntity<String> r = up.controller("k").search(null, null, null, null);
            assertEquals(400, r.getStatusCode().value());
            assertEquals(up.body, r.getBody());
        }
    }

    @Test
    void aRejectedKeyOrAServerErrorBecomesProviderUnavailableWithAFriendlyMessage() throws Exception {
        try (Upstream up = new Upstream()) {
            CommercialDeveloperController c = up.controller("secret-key");
            up.body = "{}";
            for (int status : new int[]{401, 403}) {
                up.status = status;
                CommercialException e = assertThrows(CommercialException.class, () -> c.search(null, null, null, null));
                assertUnavailable(e, DeveloperServiceClient.Reach.REJECTED, up);
            }
            for (int status : new int[]{404, 500, 502}) {
                up.status = status;
                CommercialException e = assertThrows(CommercialException.class, () -> c.search(null, null, null, null));
                assertUnavailable(e, DeveloperServiceClient.Reach.ERROR, up);
            }
        }
    }

    @Test
    void anEmptyAnswerBecomesProviderUnavailable() throws Exception {
        try (Upstream up = new Upstream()) {
            up.body = "";
            CommercialDeveloperController c = up.controller("k");
            CommercialException e = assertThrows(CommercialException.class, () -> c.search(null, null, null, null));
            assertUnavailable(e, DeveloperServiceClient.Reach.ERROR, up);
        }
    }

    @Test
    void notConfiguredIsSaidPlainly() {
        CommercialException none = assertThrows(CommercialException.class, () -> controllerFor("", null).search(null, null, null, null));
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, none.code());
        assertEquals("NOT_CONFIGURED", none.extra().get("state"));
        assertEquals(DeveloperServiceClient.Reach.NOT_CONFIGURED.message(), none.getMessage());
    }

    @Test
    void anOfflineDeveloperServiceIsSaidToBeOffline() throws Exception {
        int closed;
        try (Upstream up = new Upstream()) {
            closed = up.port();
        }
        CommercialException down = assertThrows(CommercialException.class,
                () -> controllerFor("http://127.0.0.1:" + closed, "secret-key").search(null, null, null, null));
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, down.code());
        assertEquals("OFFLINE", down.extra().get("state"));
        assertEquals(false, down.extra().get("online"));
        assertEquals("developer-service", down.extra().get("service"));
        assertEquals(DeveloperServiceClient.Reach.OFFLINE.message(), down.getMessage());
        assertNoLeak(down.getMessage(), closed);
    }

    @Test
    void aDeveloperServiceThatDoesNotAnswerInTimeIsSaidToBeSlow() throws Exception {
        try (Upstream up = new Upstream()) {
            up.delayMs = 2500;
            CommercialDeveloperController c = controllerFor("http://127.0.0.1:" + up.port(), "k", 1);
            CommercialException slow = assertThrows(CommercialException.class, () -> c.search(null, null, null, null));
            assertEquals("TIMEOUT", slow.extra().get("state"));
            assertEquals(DeveloperServiceClient.Reach.TIMEOUT.message(), slow.getMessage());
        }
    }

    @Test
    void statusReportsOnlineWhenTheServiceAnswersAndAcceptsTheKey() throws Exception {
        try (Upstream up = new Upstream()) {
            Map<String, Object> data = up.controller("k").status().getBody().getData();
            assertEquals(true, data.get("online"));
            assertEquals("ONLINE", data.get("state"));
            assertEquals("developer-service", data.get("service"));
            assertEquals(DeveloperServiceClient.Reach.ONLINE.message(), data.get("message"));
            assertEquals("/internal/admin/developers?page=0&size=1", up.uri);
        }
    }

    @Test
    void statusSeparatesOfflineFromARefusedKeyFromAFailureWithoutThrowing() throws Exception {
        Map<String, Object> notSet = controllerFor("", null).status().getBody().getData();
        assertEquals("NOT_CONFIGURED", notSet.get("state"));
        assertEquals(false, notSet.get("online"));

        int closed;
        try (Upstream up = new Upstream()) {
            closed = up.port();
            up.status = 401;
            up.body = "{}";
            Map<String, Object> refused = up.controller("wrong").status().getBody().getData();
            assertEquals("REJECTED", refused.get("state"));
            assertEquals(false, refused.get("online"));
            up.status = 500;
            assertEquals("ERROR", up.controller("k").status().getBody().getData().get("state"));
        }
        Map<String, Object> offline = controllerFor("http://127.0.0.1:" + closed, "k").status().getBody().getData();
        assertEquals("OFFLINE", offline.get("state"));
        assertEquals(DeveloperServiceClient.Reach.OFFLINE.message(), offline.get("message"));
    }

    private static void assertUnavailable(CommercialException e, DeveloperServiceClient.Reach reach, Upstream up) {
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, e.code());
        assertEquals(reach.name(), e.extra().get("state"));
        assertEquals(reach.message(), e.getMessage());
        assertNoLeak(e.getMessage(), up.port());
    }

    private static void assertNoLeak(String message, int port) {
        assertFalse(message.contains("127.0.0.1"));
        assertFalse(message.contains(String.valueOf(port)));
        assertFalse(message.contains("secret-key"));
        assertFalse(message.contains("Exception"));
    }

    @Test
    void noKeyIsSentWhenNoneIsConfigured() throws Exception {
        try (Upstream up = new Upstream()) {
            up.controller("").search(null, null, null, null);
            assertNull(up.key);
        }
    }

    @Test
    void badParametersAreRefusedBeforeAnyCall() throws Exception {
        try (Upstream up = new Upstream()) {
            CommercialDeveloperController c = up.controller("k");
            assertEquals("q", field(assertThrows(CommercialException.class, () -> c.search("x".repeat(101), null, null, null))));
            assertEquals("status", field(assertThrows(CommercialException.class, () -> c.search(null, "SLEEPING", null, null))));
            assertEquals("page", field(assertThrows(CommercialException.class, () -> c.search(null, null, -1, null))));
            assertEquals("size", field(assertThrows(CommercialException.class, () -> c.search(null, null, null, 0))));
            assertEquals("size", field(assertThrows(CommercialException.class, () -> c.search(null, null, null, 101))));
            assertNull(up.uri);
        }
    }

    private static String field(CommercialException e) {
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
        return e.details().get(0).get("field");
    }
}