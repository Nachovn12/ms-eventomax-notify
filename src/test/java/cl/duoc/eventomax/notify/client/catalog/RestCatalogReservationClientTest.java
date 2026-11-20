package cl.duoc.eventomax.notify.client.catalog;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RestCatalogReservationClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Retorna AVAILABLE con ítems mapeados cuando ms-eventomax-catalog responde reservas activas")
    void getReservationsForProduction_whenItemsExist_returnsAvailable() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/catalog/reservations/production/12", exchange -> {
            byte[] json = """
                    [
                      {"reservationId": 1, "equipmentId": 101, "equipmentName": "Consola DiGiCo Quantum", "quantity": 2, "status": "RESERVED"}
                    ]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json);
            }
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        RestCatalogReservationClient client = new RestCatalogReservationClient(baseUrl, true, 1500);

        CatalogReservationClient.CatalogReservationResult res = client.getReservationsForProduction(12L);

        assertEquals(CatalogReservationClient.CatalogAvailabilityState.AVAILABLE, res.state());
        assertEquals(1, res.items().size());
        assertEquals(101L, res.items().getFirst().equipmentId());
        assertEquals("Consola DiGiCo Quantum", res.items().getFirst().equipmentName());
        assertEquals(2, res.items().getFirst().quantity());
        assertEquals("RESERVED", res.items().getFirst().reservationStatus());
    }

    @Test
    @DisplayName("Retorna EMPTY cuando ms-eventomax-catalog responde arreglo vacío []")
    void getReservationsForProduction_whenEmptyArray_returnsEmpty() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/catalog/reservations/production/15", exchange -> {
            byte[] json = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json);
            }
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        RestCatalogReservationClient client = new RestCatalogReservationClient(baseUrl, true, 1500);

        CatalogReservationClient.CatalogReservationResult res = client.getReservationsForProduction(15L);

        assertEquals(CatalogReservationClient.CatalogAvailabilityState.EMPTY, res.state());
        assertTrue(res.items().isEmpty());
    }

    @Test
    @DisplayName("Retorna UNAVAILABLE sin propagar excepción cuando el servicio de catálogo no responde")
    void getReservationsForProduction_whenConnectionRefused_returnsUnavailable() {
        RestCatalogReservationClient client = new RestCatalogReservationClient("http://127.0.0.1:1", true, 300);

        CatalogReservationClient.CatalogReservationResult res = client.getReservationsForProduction(12L);

        assertEquals(CatalogReservationClient.CatalogAvailabilityState.UNAVAILABLE, res.state());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void unauthorizedIsDistinctFromEmpty(int status) throws IOException {
        var result = respond(status, "private upstream body");
        assertEquals(CatalogReservationClient.CatalogAvailabilityState.UNAUTHORIZED, result.state());
        assertEquals("HTTP_" + status + "_UNAUTHORIZED", result.message());
        assertTrue(result.items().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 204, 404, 429, 500, 503})
    void unexpectedHttpStatusDoesNotMeanNoReservations(int status) throws IOException {
        var result = respond(status, "");
        assertEquals(CatalogReservationClient.CatalogAvailabilityState.UNAVAILABLE, result.state());
        assertEquals("HTTP " + status, result.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "", "{}", "not-json", "[null]",
            "[{\"equipmentId\":101,\"quantity\":0}]"})
    void malformedSuccessDoesNotMeanEmpty(String json) throws IOException {
        var result = respond(200, json);
        assertEquals(CatalogReservationClient.CatalogAvailabilityState.UNAVAILABLE, result.state());
        assertTrue(result.items().isEmpty());
        assertFalse(result.message().contains(json.isEmpty() ? "private" : json));
    }

    @Test
    void preservesReleasedAndMissingStatusWithoutInventingActiveReservation() throws IOException {
        var result = respond(200, """
                [{"equipmentId":101,"quantity":1,"status":"RELEASED"},
                 {"equipmentId":102,"quantity":2}]
                """);
        assertEquals(CatalogReservationClient.CatalogAvailabilityState.AVAILABLE, result.state());
        assertEquals("RELEASED", result.items().get(0).reservationStatus());
        assertNull(result.items().get(1).reservationStatus());
        assertNull(result.items().get(1).equipmentName());
    }

    @Test
    void disabledAndInvalidIdDoNotConsultCatalog() {
        var disabled = new RestCatalogReservationClient("not-a-url", false, 300);
        assertEquals(CatalogReservationClient.CatalogAvailabilityState.NOT_PROVIDED,
                disabled.getReservationsForProduction(8L).state());
        var enabled = new RestCatalogReservationClient("not-a-url", true, 300);
        assertEquals(CatalogReservationClient.CatalogAvailabilityState.NOT_PROVIDED,
                enabled.getReservationsForProduction(0L).state());
    }

    @Test
    void propagatesTraceHeadersOnTheRealEndpoint() throws IOException {
        var trace = new java.util.concurrent.atomic.AtomicReference<String>();
        var correlation = new java.util.concurrent.atomic.AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/catalog/reservations/production/8", exchange -> {
            trace.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            correlation.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();
        MDC.put("traceId", "trace-fixture");
        MDC.put("correlationId", "correlation-fixture");
        var client = new RestCatalogReservationClient("http://127.0.0.1:" + server.getAddress().getPort() + "/", true, 1500);
        assertEquals(CatalogReservationClient.CatalogAvailabilityState.EMPTY, client.getReservationsForProduction(8L).state());
        assertEquals("trace-fixture", trace.get());
        assertEquals("correlation-fixture", correlation.get());
    }

    @Test
    void timeoutIsUnavailableAndHasNoRemoteDetails() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/api/catalog/reservations/production/8", exchange -> {
            try { release.await(2, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var client = new RestCatalogReservationClient("http://127.0.0.1:" + server.getAddress().getPort(), true, 200);
            var result = client.getReservationsForProduction(8L);
            assertEquals(CatalogReservationClient.CatalogAvailabilityState.UNAVAILABLE, result.state());
            assertEquals("TIMEOUT", result.message());
        } finally { release.countDown(); }
    }

    private CatalogReservationClient.CatalogReservationResult respond(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/catalog/reservations/production/8", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, status == 204 || bytes.length == 0 ? -1 : bytes.length);
            try (var os = exchange.getResponseBody()) {
                if (status != 204 && bytes.length > 0) os.write(bytes);
            }
        });
        server.start();
        return new RestCatalogReservationClient("http://127.0.0.1:" + server.getAddress().getPort(), true, 1500)
                .getReservationsForProduction(8L);
    }
}
