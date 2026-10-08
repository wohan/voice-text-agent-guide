package dev.textagent.core;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class HttpTransportTest {
    @Test void rejectsLargeBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> { byte[] data = new byte[2048]; ex.sendResponseHeaders(200, data.length); ex.getResponseBody().write(data); ex.close(); }); server.start();
        try {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort())).build();
            assertThrows(ExecutionException.class, () -> HttpTransport.send(HttpClient.newHttpClient(), request, HttpTransport.limited(100), Duration.ofSeconds(2)));
        } finally { server.stop(0); }
    }
    @Test void deadlineIncludesSlowResponseBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1);
        server.createContext("/", ex -> { ex.sendResponseHeaders(200, 10); ex.getResponseBody().write('a'); ex.getResponseBody().flush();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } finally { ex.close(); }
        }); server.start();
        try {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort())).build();
            assertThrows(TimeoutException.class, () -> HttpTransport.send(HttpClient.newHttpClient(), request, HttpTransport.limited(100), Duration.ofMillis(200)));
        } finally { release.countDown(); server.stop(0); }
    }
}
