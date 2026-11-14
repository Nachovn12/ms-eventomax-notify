package cl.duoc.eventomax.notify.client.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Cliente HTTP resiliente hacia ms-eventomax-catalog para recuperar el equipamiento reservado
 * asociado a una producción al momento de emitir el Acta de Cierre.
 */
@Component
public class RestCatalogReservationClient implements CatalogReservationClient {

    private static final Logger log = LoggerFactory.getLogger(RestCatalogReservationClient.class);

    private final String baseUrl;
    private final boolean enabled;
    private final int timeoutMs;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public RestCatalogReservationClient(
            @Value("${eventomax.notify.catalog.base-url:${CATALOG_SERVICE_URL:http://localhost:8081}}") String baseUrl,
            @Value("${eventomax.notify.catalog.enabled:true}") boolean enabled,
            @Value("${eventomax.notify.catalog.timeout-ms:1500}") int timeoutMs) {
        this.baseUrl = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "http://localhost:8081";
        this.enabled = enabled;
        this.timeoutMs = Math.max(200, timeoutMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.timeoutMs))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public CatalogReservationResult getReservationsForProduction(Long productionId) {
        if (!enabled || productionId == null || productionId <= 0) {
            return CatalogReservationResult.notProvided();
        }
        String url = baseUrl + "/api/catalog/reservations/production/" + productionId;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                List<RemoteReservationDto> raw = objectMapper.readValue(
                        resp.body(), new TypeReference<List<RemoteReservationDto>>() {});
                if (raw == null || raw.isEmpty()) {
                    return CatalogReservationResult.empty();
                }
                List<CatalogReservationItem> mapped = new ArrayList<>();
                for (RemoteReservationDto r : raw) {
                    if (r == null) continue;
                    mapped.add(new CatalogReservationItem(
                            r.equipmentId(),
                            r.equipmentName() != null ? r.equipmentName() : "Equipamiento ID #" + r.equipmentId(),
                            r.quantity(),
                            r.status() != null ? r.status() : "RESERVED"
                    ));
                }
                return mapped.isEmpty() ? CatalogReservationResult.empty() : CatalogReservationResult.available(mapped);
            }
            if (resp.statusCode() == 404) {
                return CatalogReservationResult.empty();
            }
            log.warn("Consulta de reservas a Catálogo devolvió HTTP {} para producción {}", resp.statusCode(), productionId);
            return CatalogReservationResult.unavailable("HTTP " + resp.statusCode());
        } catch (Exception ex) {
            log.warn("Catálogo no accesible al consultar reservas de producción {}: {}", productionId, ex.getMessage());
            return CatalogReservationResult.unavailable(ex.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RemoteReservationDto(
            Long reservationId,
            Long equipmentId,
            String equipmentName,
            Integer quantity,
            String status
    ) {}
}
