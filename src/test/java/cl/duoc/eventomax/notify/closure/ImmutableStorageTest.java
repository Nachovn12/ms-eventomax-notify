package cl.duoc.eventomax.notify.closure;

import cl.duoc.eventomax.notify.storage.LocalFileSystemDocumentStorageService;
import cl.duoc.eventomax.notify.storage.S3DocumentStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImmutableStorageTest {
    @Test void simultaneousLocalPublicationNeverOverwritesExistingBytes(@TempDir Path temp) throws Exception {
        var backend=new LocalFileSystemDocumentStorageService(temp.toString());
        var executor=Executors.newFixedThreadPool(2);
        try {
            var barrier=new CyclicBarrier(2);
            List<Callable<Boolean>> calls=List.of(
                    ()->{barrier.await();return backend.storeDocumentIfAbsent("closures","fixture.pdf",new byte[]{1},"application/pdf");},
                    ()->{barrier.await();return backend.storeDocumentIfAbsent("closures","fixture.pdf",new byte[]{2},"application/pdf");});
            var results=executor.invokeAll(calls);
            assertThat(results.get(0).get()).isNotEqualTo(results.get(1).get());
            byte[] published=backend.retrieveDocumentExact("closures","fixture.pdf");
            assertThat(backend.storeDocumentIfAbsent("closures","fixture.pdf",new byte[]{3},"application/pdf")).isFalse();
            assertThat(backend.retrieveDocumentExact("closures","fixture.pdf")).isEqualTo(published);
            assertThatThrownBy(()->backend.storeDocumentIfAbsent("closures","../outside.pdf",new byte[]{1},"application/pdf"))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {executor.shutdownNow();}
    }
    @Test void s3UsesConditionalPutAndTreatsPreconditionFailureAsExistingObject() {
        var client=mock(S3Client.class);var backend=new S3DocumentStorageService(client,"fixture","prefix");
        when(client.putObject(any(PutObjectRequest.class),any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(412).build());
        assertThat(backend.storeDocumentIfAbsent("closures","fixture.pdf",new byte[]{1},"application/pdf")).isFalse();
        var request=ArgumentCaptor.forClass(PutObjectRequest.class);verify(client).putObject(request.capture(),any(RequestBody.class));
        assertThat(request.getValue().overrideConfiguration().orElseThrow().headers().get("If-None-Match")).containsExactly("*");
        assertThat(request.getValue().key()).isEqualTo("prefix/closures/fixture.pdf");
    }
    @Test void s3StorageOutageIsNotSilentlyConvertedToSuccess() {
        var client=mock(S3Client.class);var backend=new S3DocumentStorageService(client,"fixture","prefix");
        when(client.putObject(any(PutObjectRequest.class),any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(503).build());
        assertThatThrownBy(()->backend.storeDocumentIfAbsent("closures","fixture.pdf",new byte[]{1},"application/pdf"))
                .isInstanceOf(S3Exception.class);
    }
}
