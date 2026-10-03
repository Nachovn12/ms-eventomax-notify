package cl.duoc.eventomax.notify.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryProcessedEventStoreTest {

    private InMemoryProcessedEventStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryProcessedEventStore();
    }

    @Test
    @DisplayName("G1. Inicialmente no está procesado")
    void isProcessed_initiallyFalse() {
        assertFalse(store.isProcessed("evt-100"));
    }

    @Test
    @DisplayName("G2. Después de marcar, está procesado")
    void markAsProcessed_thenIsProcessed() {
        store.markAsProcessed("evt-100");
        assertTrue(store.isProcessed("evt-100"));
    }

    @Test
    @DisplayName("G3. Eventos distintos son independientes")
    void differentEvents_areIndependent() {
        store.markAsProcessed("evt-100");
        assertFalse(store.isProcessed("evt-200"));
    }

    @Test
    @DisplayName("G4. processOnce ejecuta y retorna true la primera vez")
    void processOnce_executesAndReturnsTrue() {
        AtomicInteger counter = new AtomicInteger(0);
        boolean result = store.processOnce("evt-300", counter::incrementAndGet);

        assertTrue(result);
        assertEquals(1, counter.get());
        assertTrue(store.isProcessed("evt-300"));
    }

    @Test
    @DisplayName("G5. processOnce retorna false si ya existe, no ejecuta")
    void processOnce_returnsFalseIfDuplicate() {
        AtomicInteger counter = new AtomicInteger(0);
        store.markAsProcessed("evt-400");

        boolean result = store.processOnce("evt-400", counter::incrementAndGet);

        assertFalse(result);
        assertEquals(0, counter.get());
    }

    @Test
    @DisplayName("G6. processOnce propaga excepción y no registra")
    void processOnce_propagatesExceptionAndDoesNotMark() {
        assertThrows(RuntimeException.class, () ->
            store.processOnce("evt-500", () -> {
                throw new RuntimeException("Error simulado");
            })
        );

        assertFalse(store.isProcessed("evt-500"));
    }

    @Test
    @DisplayName("G7. Concurrencia Real: processOnce garantiza ejecución única")
    void threadSafety_processOnce_isAtomic() throws InterruptedException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger executionCount = new AtomicInteger(0);
        AtomicInteger successReturns = new AtomicInteger(0);

        final String eventId = "evt-concurrent-1";

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    boolean result = store.processOnce(eventId, () -> {
                        executionCount.incrementAndGet();
                        try {
                            Thread.sleep(10); // Simular trabajo para forzar traslape
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    if (result) {
                        successReturns.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertTrue(store.isProcessed(eventId));
        assertEquals(1, executionCount.get(), "El processor debe ejecutarse exactamente UNA vez");
        assertEquals(1, successReturns.get(), "Solo UN llamado debe retornar true");
    }
}
