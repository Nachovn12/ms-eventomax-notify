package cl.duoc.eventomax.notify.client.catalog;

import java.util.List;

/**
 * Abstracción de consulta para el inventario de equipamiento reservado desde ms-eventomax-catalog.
 * <p>
 * Diseñado bajo el principio de resiliencia documental, distinguiendo explícitamente entre
 * consulta exitosa con reservas, consulta confirmada sin reservas, caída/timeout de red e información no provista.
 */
public interface CatalogReservationClient {

    CatalogReservationResult getReservationsForProduction(Long productionId);

    enum CatalogAvailabilityState {
        /**
         * Consulta exitosa al servicio de catálogo confirmando reservas asignadas.
         */
        AVAILABLE,

        /**
         * Consulta exitosa al servicio de catálogo que confirma que no existen reservas asociadas.
         */
        EMPTY,

        /**
         * Servicio de catálogo inaccesible, error de red, fallo 5xx o timeout agotado.
         */
        UNAVAILABLE,

        /**
         * No se realizó consulta (payload antiguo o configuración documental desacoplada).
         */
        NOT_PROVIDED
    }

    record CatalogReservationItem(
            Long equipmentId,
            String equipmentName,
            Integer quantity,
            String reservationStatus
    ) {}

    record CatalogReservationResult(
            CatalogAvailabilityState state,
            List<CatalogReservationItem> items,
            String message
    ) {
        public static CatalogReservationResult available(List<CatalogReservationItem> items) {
            return new CatalogReservationResult(
                    CatalogAvailabilityState.AVAILABLE,
                    items != null ? List.copyOf(items) : List.of(),
                    null
            );
        }

        public static CatalogReservationResult empty() {
            return new CatalogReservationResult(
                    CatalogAvailabilityState.EMPTY,
                    List.of(),
                    "Consulta confirmada sin reservas asociadas a la producción"
            );
        }

        public static CatalogReservationResult unavailable(String message) {
            return new CatalogReservationResult(
                    CatalogAvailabilityState.UNAVAILABLE,
                    List.of(),
                    message != null ? message : "Servicio de inventario y catálogo temporalmente inaccesible"
            );
        }

        public static CatalogReservationResult notProvided() {
            return new CatalogReservationResult(
                    CatalogAvailabilityState.NOT_PROVIDED,
                    List.of(),
                    "Información de reservas de catálogo no provista"
            );
        }
    }
}

