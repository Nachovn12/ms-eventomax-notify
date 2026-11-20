package cl.duoc.eventomax.notify.document;

import cl.duoc.eventomax.notify.client.catalog.CatalogReservationClient;
import cl.duoc.eventomax.notify.client.catalog.RestCatalogReservationClient;
import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.quote.QuoteCommandPayload;
import cl.duoc.eventomax.notify.service.QuoteNotificationService;
import cl.duoc.eventomax.notify.storage.LocalFileSystemDocumentStorageService;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogClosureDocumentIntegrationTest {
    private MessageEnvelope<QuoteCommandPayload> command() {
        return new MessageEnvelope<>("GenerateClosingQuote", "fixture-close-8", "2026-11-20T20:00:00Z",
                "fixture-trace", "fixture-correlation",
                new QuoteCommandPayload(8L, "org-fixture", "Evento de prueba", "CERRADO",
                        "2026-11-21T18:00:00", "Recinto de prueba", "2026-11-20T20:00:00Z"));
    }

    @Test
    void knownReservationTravelsFromHttpContractToStoredPdf(@TempDir Path temp) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/catalog/reservations/production/8", exchange -> {
            byte[] json = """
                    [{"reservationId":4,"equipmentId":28,"equipmentName":"Subwoofer Activo JBL VTX G28",
                      "quantity":1,"status":"RELEASED"}]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            try (var os = exchange.getResponseBody()) { os.write(json); }
        });
        server.start();
        try {
            var client = new RestCatalogReservationClient("http://127.0.0.1:" + server.getAddress().getPort(), true, 1500);
            var builder = new ProductionClosureDocumentBuilder(client);
            var dto = builder.build(command());
            assertThat(dto.equipment()).hasSize(1);
            assertThat(dto.equipment().getFirst().reservedQuantity()).isEqualTo(1);
            assertThat(dto.equipment().getFirst().reservationStatus()).isEqualTo("RELEASED");
            var storage = new LocalFileSystemDocumentStorageService(temp.resolve("storage").toString());
            var service = new QuoteNotificationService(builder, new OpenPdfClosureDocumentGenerator(), null,
                    storage, temp.resolve("output").toString());
            Path file = service.processQuoteGeneration(command());
            byte[] stored = storage.retrieveDocument("quotes", file.getFileName().toString());
            assertThat(stored).isEqualTo(Files.readAllBytes(file));
            assertThat(text(stored)).contains("Subwoofer Activo JBL VTX G28", "RELEASED", "EQ-028")
                    .doesNotContain("RESERVED", "no realizada", "No fue posible verificar");
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @CsvSource({
            "EMPTY, Sin reservas de equipamiento informadas",
            "NOT_PROVIDED, consulta de Catálogo no realizada",
            "UNAVAILABLE, No fue posible verificar el inventario",
            "UNAUTHORIZED, Catálogo no autorizó la consulta"
    })
    void missingInventoryRendersPreciseStateWithoutInventedEquipment(String state, String expected) throws Exception {
        var result = new CatalogReservationClient.CatalogReservationResult(
                CatalogReservationClient.CatalogAvailabilityState.valueOf(state), List.of(),
                "private diagnostic must never appear");
        var dto = new ProductionClosureDocumentBuilder(id -> result).build(command());
        assertThat(dto.catalogAvailability().name()).isEqualTo(state);
        String pdf = text(new OpenPdfClosureDocumentGenerator().generatePdfBytes(dto, false));
        assertThat(pdf).contains(expected).doesNotContain("private diagnostic", "Subwoofer", "RESERVED");
        if (state.equals("UNAVAILABLE") || state.equals("UNAUTHORIZED")) {
            assertThat(pdf).contains("Esto no confirma ausencia de reservas.");
        }
    }

    private String text(byte[] bytes) throws Exception {
        try (var pdf = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
        }
    }
}
