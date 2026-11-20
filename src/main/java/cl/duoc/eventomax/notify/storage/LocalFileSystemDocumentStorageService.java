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

    @Override
    public boolean storeDocumentIfAbsent(String category, String filename, byte[] content, String contentType) {
        Path target = exactPath(category, filename);
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".closure-", ".tmp");
            try (var channel = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // Atomic publication without overwrite, unlike ATOMIC_MOVE whose replacement semantics vary.
            Files.createLink(target, temp);
            return true;
        } catch (java.nio.file.FileAlreadyExistsException ex) {
            return false;
        } catch (IOException ex) {
            throw new IllegalStateException("IMMUTABLE_STORAGE_WRITE_FAILED", ex);
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); }
                catch (IOException ex) { log.warn("Temporary closure storage cleanup failed"); }
            }
        }
    }

    @Override
    public byte[] retrieveDocumentExact(String category, String filename) {
        try { return Files.readAllBytes(exactPath(category, filename)); }
        catch (java.nio.file.NoSuchFileException ex) { throw new DocumentNotFoundException("CLOSURE_DOCUMENT_MISSING"); }
        catch (IOException ex) { throw new IllegalStateException("IMMUTABLE_STORAGE_READ_FAILED", ex); }
    }

    private Path exactPath(String category, String filename) {
        if (category == null || filename == null || !category.matches("[A-Za-z0-9_-]+")
                || !filename.matches("[A-Za-z0-9._-]+") || filename.equals(".") || filename.equals("..")) {
            throw new IllegalArgumentException("INVALID_STORAGE_KEY");
        }
        Path target = rootDir.resolve(category).resolve(filename).normalize();
        if (!target.startsWith(rootDir)) throw new IllegalArgumentException("INVALID_STORAGE_KEY");
        return target;
    }
}

