package cl.duoc.eventomax.notify.storage;

/**
 * Contrato agnóstico de almacenamiento de documentos (local y S3)
 * para actas de cierre y documentos generados por el servicio de notificaciones.
 */
public interface DocumentStorageService {

    /**
     * Almacena un documento binario en el backend configurado.
     *
     * @param category    categoría o prefijo del documento (ej. "quotes")
     * @param filename    nombre único del archivo (ej. "quote-production-1-latest.pdf")
     * @param content     contenido binario del documento
     * @param contentType tipo MIME del documento (ej. "application/pdf")
     * @return URI o identificador de almacenamiento del recurso guardado
     */
    String storeDocument(String category, String filename, byte[] content, String contentType);

    /**
     * Recupera el contenido binario de un documento previamente almacenado.
     *
     * @param category categoría o prefijo del documento
     * @param filename nombre del archivo a recuperar
     * @return arreglo de bytes con el contenido
     * @throws DocumentNotFoundException si el documento no existe
     */
    byte[] retrieveDocument(String category, String filename);

    /**
     * Verifica si un documento existe en el almacenamiento.
     *
     * @param category categoría o prefijo del documento
     * @param filename nombre del archivo
     * @return true si existe, false en caso contrario
     */
    boolean documentExists(String category, String filename);
}

