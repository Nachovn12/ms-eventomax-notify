package cl.duoc.eventomax.notify.closure;

import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;
import java.util.UUID;

/** Transport only. Durable audit is committed by the coordinator after a successful return. */
public interface ClosureEmailTransport {
    void sendClosure(MessageEnvelope<EmailProductionStatusPayload> envelope,
                     UUID deliveryId, UUID documentId, byte[] pdf, String sha256);
}
