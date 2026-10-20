package cl.duoc.eventomax.notify.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalFileSystemDocumentStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalFileSystemDocumentStorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new LocalFileSystemDocumentStorageService(tempDir.toString());
    }

    @Test
    @DisplayName("Almacena y recupera documento localmente")
    void storeAndRetrieve_success() {
        byte[] content = "CONTENIDO_PDF_PRUEBA".getBytes(StandardCharsets.UTF_8);
        String path = storageService.storeDocument("quotes", "test.pdf", content, "application/pdf");

        assertNotNull(path);
        assertTrue(storageService.documentExists("quotes", "test.pdf"));

        byte[] retrieved = storageService.retrieveDocument("quotes", "test.pdf");
        assertArrayEquals(content, retrieved);
    }

    @Test
    @DisplayName("Lanza DocumentNotFoundException si el documento no existe")
    void retrieve_notFound_throwsException() {
        assertFalse(storageService.documentExists("quotes", "nonexistent.pdf"));
        assertThrows(DocumentNotFoundException.class, () ->
                storageService.retrieveDocument("quotes", "nonexistent.pdf"));
    }
}

