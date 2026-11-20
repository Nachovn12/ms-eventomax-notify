package cl.duoc.eventomax.notify.closure;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback only. Never relays mail. Can model negative replies and lost acceptance replies. */
final class FixtureSmtpServer implements AutoCloseable {
    enum Mode { ACCEPT, REJECT_PERMANENT, REJECT_TRANSIENT, AMBIGUOUS }
    private final ServerSocket server;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    volatile Mode mode = Mode.ACCEPT;
    final AtomicInteger submissions = new AtomicInteger();
    final List<byte[]> messages = new CopyOnWriteArrayList<>();
    FixtureSmtpServer() throws IOException {
        server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        executor.submit(() -> {
            while (!server.isClosed()) {
                try (var socket = server.accept()) { handle(socket); }
                catch (IOException ex) { if (server.isClosed()) return; }
            }
        });
    }
    int port() { return server.getLocalPort(); }
    private void handle(Socket socket) throws IOException {
        socket.setSoTimeout(3000);
        var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
        reply(writer, "220 fixture ESMTP");
        for (String line; (line = reader.readLine()) != null;) {
            if (line.startsWith("EHLO") || line.startsWith("HELO")) reply(writer, "250 fixture");
            else if (line.equals("DATA")) {
                reply(writer, "354 fixture data");
                var body = new ByteArrayOutputStream();
                while ((line = reader.readLine()) != null && !line.equals(".")) {
                    if (line.startsWith("..")) line = line.substring(1);
                    body.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
                }
                submissions.incrementAndGet(); messages.add(body.toByteArray());
                if (mode == Mode.AMBIGUOUS) return;
                reply(writer, mode == Mode.REJECT_PERMANENT ? "550 fixture rejected" :
                        mode == Mode.REJECT_TRANSIENT ? "451 fixture retry later" : "250 fixture accepted");
            } else if (line.equals("QUIT")) { reply(writer, "221 fixture bye"); return; }
            else reply(writer, "250 fixture ok");
        }
    }
    private void reply(BufferedWriter writer, String text) throws IOException {
        writer.write(text + "\r\n"); writer.flush();
    }
    @Override public void close() throws IOException { server.close(); executor.shutdownNow(); }
}
