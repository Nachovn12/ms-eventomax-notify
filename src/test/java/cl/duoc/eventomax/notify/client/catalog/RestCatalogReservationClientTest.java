package cl.duoc.eventomax.notify.client.catalog;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RestCatalogReservationClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
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
}
