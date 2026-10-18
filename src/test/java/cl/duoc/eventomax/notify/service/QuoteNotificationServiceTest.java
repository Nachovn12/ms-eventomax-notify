package cl.duoc.eventomax.notify.service;

import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.quote.QuoteCommandPayload;
import cl.duoc.eventomax.notify.sender.QuoteGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuoteNotificationServiceTest {

    @Mock
    private QuoteGenerator quoteGenerator;

    private QuoteNotificationService service;

    @BeforeEach
    void setUp() {
        service = new QuoteNotificationService(quoteGenerator);
    }

    @Test
    void processQuoteGeneration_shouldDelegateToGenerator() {
        QuoteCommandPayload payload = new QuoteCommandPayload(
                300L, "org-1", "Prod Cierre", "CERRADO", "2026-10-01T20:00", "Santiago", "2026-10-02T01:00:00Z"
        );
        MessageEnvelope<QuoteCommandPayload> envelope = new MessageEnvelope<>(
                "GenerateClosingQuote", "evt-quote-1", "2026-10-02T01:00:01Z", "trace-1", "corr-1", payload
        );
        Path expectedPath = Path.of("data/quotes/quote-production-300-evt-quote-1.pdf");
        when(quoteGenerator.generate(envelope)).thenReturn(expectedPath);

        Path result = service.processQuoteGeneration(envelope);

        assertEquals(expectedPath, result);
        verify(quoteGenerator, times(1)).generate(envelope);
    }
}

