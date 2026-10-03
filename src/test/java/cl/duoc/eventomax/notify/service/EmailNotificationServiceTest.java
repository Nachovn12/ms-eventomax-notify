package cl.duoc.eventomax.notify.service;

import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.email.EmailProductionStatusPayload;
import cl.duoc.eventomax.notify.sender.EmailSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailNotificationServiceTest {

    @Mock
    private EmailSender emailSender;

    @InjectMocks
    private EmailNotificationService emailNotificationService;

    @Test
    @DisplayName("F. Delega correctamente a EmailSender")
    void processEmailNotification_shouldDelegateToSender() {
        var payload = new EmailProductionStatusPayload(
                200L, "org-002", "Festival Jazz", "PENDING",
                "2026-11-15T19:00:00", "Valparaíso, Chile"
        );
        var envelope = new MessageEnvelope<>(
                "SendProductionStatusEmail",
                "evt-010",
                "2026-10-03T00:00:00Z",
                "trace-010",
                "corr-010",
                payload
        );

        emailNotificationService.processEmailNotification(envelope);

        verify(emailSender, times(1)).send(payload);
    }
}
