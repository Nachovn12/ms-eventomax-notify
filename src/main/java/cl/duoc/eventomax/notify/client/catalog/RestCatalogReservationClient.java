package cl.duoc.eventomax.notify.client.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.io.IOException;
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
            @Value("${eventomax.notify.catalog.base-url:${CATALOG_BASE_URL:${CATALOG_SERVICE_URL:http://localhost:8081}}}") String baseUrl,
            @Value("${eventomax.notify.catalog.enabled:true}") boolean enabled,
            @Value("${eventomax.notify.catalog.timeout-ms:1500}") int timeoutMs) {
        this.baseUrl = baseUrl != null ? baseUrl.trim().replaceAll("/+$", "") : "http://localhost:8081";
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
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Accept", "application/json")
                    .GET();
            for (String key : List.of("traceId", "correlationId")) {
                String value = MDC.get(key);
                if (value != null && !value.isBlank()) {
                    builder.header("traceId".equals(key) ? "X-Trace-Id" : "X-Correlation-Id", value);
                }
            }
            HttpRequest req = builder.build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                List<RemoteReservationDto> raw = objectMapper.readValue(
                        resp.body(), new TypeReference<List<RemoteReservationDto>>() {});
                if (raw == null) {
                    return CatalogReservationResult.unavailable("INVALID_RESPONSE");
                }
                if (raw.isEmpty()) {
                    return CatalogReservationResult.empty();
                }
                List<CatalogReservationItem> mapped = new ArrayList<>();
                for (RemoteReservationDto r : raw) {
                    if (r == null || r.equipmentId() == null || r.equipmentId() <= 0
                            || r.quantity() == null || r.quantity() <= 0) {
                        return CatalogReservationResult.unavailable("INVALID_RESERVATION");
                    }
                    mapped.add(new CatalogReservationItem(
                            r.equipmentId(),
                            r.equipmentName(),
                            r.quantity(),
                            r.status(),
                            r.reservationId()
                    ));
                }
                return mapped.isEmpty() ? CatalogReservationResult.empty() : CatalogReservationResult.available(mapped);
            }
            if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                log.warn("Consulta de reservas no autorizada: HTTP {}, producción {}", resp.statusCode(), productionId);
                return CatalogReservationResult.unauthorized(resp.statusCode());
            }
            log.warn("Consulta de reservas a Catálogo devolvió HTTP {} para producción {}", resp.statusCode(), productionId);
            return CatalogReservationResult.unavailable("HTTP " + resp.statusCode());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return CatalogReservationResult.unavailable("INTERRUPTED");
        } catch (HttpTimeoutException ex) {
            log.warn("Timeout de Catálogo para producción {}", productionId);
            return CatalogReservationResult.unavailable("TIMEOUT");
        } catch (IOException ex) {
            log.warn("Error de conexión a Catálogo para producción {}", productionId);
            return CatalogReservationResult.unavailable("CONNECTION_ERROR");
        } catch (Exception ex) {
            // No incluir cuerpos, URLs ni mensajes remotos potencialmente sensibles.
            log.warn("Respuesta o configuración de Catálogo inválida para producción {}", productionId);
            return CatalogReservationResult.unavailable("INVALID_RESPONSE");
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
