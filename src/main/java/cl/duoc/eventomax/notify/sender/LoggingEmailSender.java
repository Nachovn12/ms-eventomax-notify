package cl.duoc.eventomax.notify.sender;

import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implementación de {@link EmailSender} que simula el envío de emails mediante logging.
 * <p>
 * En este incremento NO se envían emails reales.
 * Se registra información funcional relevante sin exponer datos sensibles.
 */
@Component
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(EmailProductionStatusPayload payload) {
        log.info("[EMAIL SIMULADO] Enviando notificación de estado de producción: "
                        + "productionId={}, status={}, organizerId={}",
                payload.productionId(),
                payload.status(),
                payload.organizerId());
    }
}
