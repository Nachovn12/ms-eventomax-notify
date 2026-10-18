package cl.duoc.eventomax.notify.service;

import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.quote.QuoteCommandPayload;
import cl.duoc.eventomax.notify.sender.QuoteGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * Servicio de aplicación que coordina la generación del acta de cierre PDF
 * al recibir el comando {@code GenerateClosingQuote}.
 */
@Service
public class QuoteNotificationService {

    private static final Logger log = LoggerFactory.getLogger(QuoteNotificationService.class);

    private final QuoteGenerator quoteGenerator;

    public QuoteNotificationService(QuoteGenerator quoteGenerator) {
        this.quoteGenerator = quoteGenerator;
    }

    public Path processQuoteGeneration(MessageEnvelope<QuoteCommandPayload> envelope) {
        log.info("Procesando comando de generación de acta PDF (quote): productionId={}, status={}, traceId={}, correlationId={}",
                envelope.payload().productionId(),
                envelope.payload().status(),
                envelope.traceId(),
                envelope.correlationId());

        return quoteGenerator.generate(envelope);
    }
}

