package cl.duoc.eventomax.notify.sender;

import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Implementación de {@link EmailSender} que simula el envío de emails mediante logging.
 * Solo se activa si se configura explícitamente eventomax.notify.email.provider=logging.
 */
@Component
@ConditionalOnProperty(name = "eventomax.notify.email.provider", havingValue = "logging")
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
