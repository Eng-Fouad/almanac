package io.quarkiverse.almanac.maven;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// [Http] against a local server: the pacer spaces real requests out, and a refusal stops all further requests.
class HttpTest {

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<Long> arrivals = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            arrivals.add(System.nanoTime());
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            int status = path.startsWith("/blocked") ? 403 : path.startsWith("/busy") ? 429 : 200;
            byte[] body = ("status " + status).getBytes(StandardCharsets.UTF_8);
            if (status == 429) {
                exchange.getResponseHeaders().add("Retry-After", "0");
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI uri(String path) {
        return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + path);
    }

    @Test
    void requestsArePausedApart() throws IOException {
        try (Http http = new Http(4, false, Pacer.local(Pacer.Pause.parse("0.2-0.3")))) {
            for (int i = 0; i < 3; i++) {
                assertEquals(200, http.get(uri("/ok/" + i)).status());
            }
            assertEquals(3, http.requestCount());
            assertTrue(http.pacer().paused().toMillis() >= 2 * 180, "paused " + http.pacer().paused());
        }
        for (int i = 1; i < arrivals.size(); i++) {
            long gapMillis = (arrivals.get(i) - arrivals.get(i - 1)) / 1_000_000;
            assertTrue(gapMillis >= 180, "requests " + (i - 1) + " and " + i + " only " + gapMillis + " ms apart");
        }
    }

    @Test
    void aRefusalStopsAllFurtherRequests() throws IOException {
        try (Http http = new Http(4, false, Pacer.none())) {
            assertEquals(200, http.get(uri("/ok/before")).status());
            assertEquals(403, http.get(uri("/blocked/a.jar")).status());

            Http.StoppedException stopped =
                    assertThrows(Http.StoppedException.class, () -> http.get(uri("/ok/after")));
            assertTrue(stopped.getMessage().contains("HTTP 403 for " + uri("/blocked/a.jar")), stopped.getMessage());
            assertThrows(Http.StoppedException.class, () -> http.getRange(uri("/ok/range"), "bytes=-10"));
            assertEquals(2, http.requestCount());
        }
        assertEquals(List.of("/ok/before", "/blocked/a.jar"), paths, "nothing was sent after the 403");
    }

    @Test
    void tooManyRequestsAfterEveryRetryStopsTheRequestsToo() throws IOException {
        try (Http http = new Http(1, false, Pacer.none())) {
            assertEquals(429, http.get(uri("/busy")).status());
            assertEquals(6, paths.size(), "the 429 was retried");
            assertThrows(Http.StoppedException.class, () -> http.get(uri("/ok")));
        }
        assertEquals(6, paths.size());
    }
}
