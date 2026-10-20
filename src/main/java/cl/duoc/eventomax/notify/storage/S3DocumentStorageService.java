package cl.duoc.eventomax.notify.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@ConditionalOnProperty(name = "eventomax.notify.storage.provider", havingValue = "s3")
public class S3DocumentStorageService implements DocumentStorageService {

    private static final Logger log = LoggerFactory.getLogger(S3DocumentStorageService.class);

    private final S3Client s3Client;
    private final String bucketName;
    private final String prefix;

    public S3DocumentStorageService(
            @Value("${eventomax.notify.storage.s3.bucket-name:eventomax-private-documents}") String bucketName,
            @Value("${eventomax.notify.storage.s3.region:us-east-1}") String region,
            @Value("${eventomax.notify.storage.s3.prefix:quotes/}") String prefix) {
        this.bucketName = bucketName;
        this.prefix = prefix != null ? prefix : "";
        this.s3Client = S3Client.builder()
                .region(Region.of(region))
                .build();
    }

    public S3DocumentStorageService(S3Client s3Client, String bucketName, String prefix) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.prefix = prefix != null ? prefix : "";
    }

    @Override
    public String storeDocument(String category, String filename, byte[] content, String contentType) {
        String key = buildKey(category, filename);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(contentType != null ? contentType : "application/octet-stream")
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(content));
        log.info("[DOCUMENT STORED S3] bucket={}, key={}, sizeBytes={}", bucketName, key, content.length);
        return "s3://" + bucketName + "/" + key;
    }

    @Override
    public byte[] retrieveDocument(String category, String filename) {
        String key = buildKey(category, filename);
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            ResponseBytes<GetObjectResponse> bytes = s3Client.getObjectAsBytes(request);
            return bytes.asByteArray();
        } catch (NoSuchKeyException e) {
            throw new DocumentNotFoundException("Documento no encontrado en S3: " + key, e);
        } catch (Exception e) {
            throw new IllegalStateException("Error recuperando documento de S3: " + key, e);
        }
    }

    @Override
    public boolean documentExists(String category, String filename) {
        String key = buildKey(category, filename);
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            s3Client.headObject(request);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            log.warn("Error verificando existencia en S3: {}", key, e);
            return false;
        }
    }

    private String buildKey(String category, String filename) {
        String cleanPrefix = prefix.endsWith("/") ? prefix : prefix + "/";
        return cleanPrefix + category + "/" + filename;
    }
}

