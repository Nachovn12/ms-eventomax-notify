package cl.duoc.eventomax.notify.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
@ConditionalOnProperty(name = "eventomax.notify.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalFileSystemDocumentStorageService implements DocumentStorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalFileSystemDocumentStorageService.class);

    private final Path rootDir;

    public LocalFileSystemDocumentStorageService(
            @Value("${eventomax.notify.storage.local.base-dir:./data/quotes}") String localDirPath) {
        this.rootDir = Path.of(localDirPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootDir);
        } catch (IOException e) {
            log.warn("No se pudo crear el directorio raíz de almacenamiento local: {}", this.rootDir, e);
        }
    }

    @Override
    public String storeDocument(String category, String filename, byte[] content, String contentType) {
        try {
            Path categoryDir = rootDir.resolve(category).normalize();
            Files.createDirectories(categoryDir);
            Path targetFile = categoryDir.resolve(filename).normalize();
            Path tempFile = categoryDir.resolve(filename + ".tmp").normalize();

            Files.write(tempFile, content);
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);

            log.info("[DOCUMENT STORED LOCAL] category={}, filename={}, sizeBytes={}, path={}",
                    category, filename, content.length, targetFile);
            return targetFile.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Error guardando documento localmente: " + filename, e);
        }
    }

    @Override
    public byte[] retrieveDocument(String category, String filename) {
        Path targetFile = rootDir.resolve(category).resolve(filename).normalize();
        if (!Files.exists(targetFile)) {
            // Fallback: intentar directamente en rootDir sin subcarpeta de categoría si coincide
            Path directFile = rootDir.resolve(filename).normalize();
            if (Files.exists(directFile)) {
                try {
                    return Files.readAllBytes(directFile);
                } catch (IOException e) {
                    throw new IllegalStateException("Error leyendo documento local: " + filename, e);
                }
            }
            throw new DocumentNotFoundException("Documento no encontrado: " + category + "/" + filename);
        }
        try {
            return Files.readAllBytes(targetFile);
        } catch (IOException e) {
            throw new IllegalStateException("Error leyendo documento local: " + filename, e);
        }
    }

    @Override
    public boolean documentExists(String category, String filename) {
        Path targetFile = rootDir.resolve(category).resolve(filename).normalize();
        if (Files.exists(targetFile)) {
            return true;
        }
        Path directFile = rootDir.resolve(filename).normalize();
        return Files.exists(directFile);
    }
}

