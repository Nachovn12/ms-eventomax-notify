package cl.duoc.eventomax.notify.sender;

import cl.duoc.eventomax.notify.messaging.common.MessageEnvelope;
import cl.duoc.eventomax.notify.messaging.quote.QuoteCommandPayload;

import java.nio.file.Path;

/**
 * Contrato de generación de acta de cierre PDF para producciones finalizadas ({@code CERRADO}).
 */
public interface QuoteGenerator {

    /**
     * Genera y persiste un archivo PDF real con los datos del cierre de la producción.
     *
     * @param envelope el sobre del comando con trazabilidad y datos de la producción
     * @return la ruta del archivo PDF persistido
     */
    Path generate(MessageEnvelope<QuoteCommandPayload> envelope);
}

