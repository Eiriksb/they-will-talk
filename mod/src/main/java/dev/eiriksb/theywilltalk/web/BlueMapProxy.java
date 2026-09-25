package dev.eiriksb.theywilltalk.web;

import com.sun.net.httpserver.HttpExchange;
import dev.eiriksb.theywilltalk.TwtConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Serves BlueMap's web map under {@code /bluemap/} on the dashboard's own port, behind the dashboard login. The map
 * page embeds it from the same origin, and admins reaching the dashboard from another machine (or through an SSH
 * tunnel) get BlueMap too without opening its port.
 */
final class BlueMapProxy {
    static final String PREFIX = "/bluemap";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final List<String> REQUEST_HEADERS = List.of("Accept", "Accept-Encoding", "If-None-Match", "If-Modified-Since");
    private static final List<String> RESPONSE_HEADERS = List.of("Content-Type", "Content-Encoding", "Cache-Control", "ETag", "Last-Modified");

    private BlueMapProxy() {}

    static void handle(HttpExchange ex) throws IOException, InterruptedException {
        String path = ex.getRequestURI().getRawPath();
        if (path.equals(PREFIX)) {
            DashboardServer.redirect(ex, PREFIX + "/", null);
            return;
        }
        if (!ex.getRequestMethod().equals("GET")) {
            DashboardServer.sendJson(ex, 405, DashboardServer.error("GET only"));
            return;
        }
        String query = ex.getRequestURI().getRawQuery();
        String base = TwtConfig.BLUEMAP_URL.get().replaceAll("/+$", "");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path.substring(PREFIX.length()) + (query == null ? "" : "?" + query)))
                .timeout(Duration.ofSeconds(30)).GET();
        for (String h : REQUEST_HEADERS) {
            String v = ex.getRequestHeaders().getFirst(h);
            if (v != null) {
                request.header(h, v);
            }
        }
        HttpResponse<InputStream> response;
        try {
            response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            DashboardServer.sendJson(ex, 502, DashboardServer.error("BlueMap isn't reachable at " + base + " (bluemapUrl in the config)"));
            return;
        }
        for (String h : RESPONSE_HEADERS) {
            response.headers().firstValue(h).ifPresent(v -> ex.getResponseHeaders().set(h, v));
        }
        int status = response.statusCode();
        try (InputStream in = response.body()) {
            if (status == 304 || status == 204) {
                ex.sendResponseHeaders(status, -1);
                return;
            }
            long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            ex.sendResponseHeaders(status, length > 0 ? length : 0); // 0 = chunked
            try (OutputStream out = ex.getResponseBody()) {
                in.transferTo(out);
            }
        }
    }
}
