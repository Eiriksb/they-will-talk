package dev.eiriksb.theywilltalk.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.eiriksb.theywilltalk.TheyWillTalk;
import dev.eiriksb.theywilltalk.TwtConfig;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The admin dashboard: a small web app served straight from the mod jar plus a JSON API.
 * Protected by an admin token (config/theywilltalk-admin-token.txt) and bound to localhost by default.
 */
public final class DashboardServer {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long SESSION_MS = 7L * 24 * 3600 * 1000;

    final TheyWillTalk mod;
    private final Path tokenFile;
    private String token;
    private HttpServer http;
    private ExecutorService pool;
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> oneTimeCodes = new ConcurrentHashMap<>();
    private final Api api;

    public DashboardServer(TheyWillTalk mod, Path worldData) {
        this.mod = mod;
        this.tokenFile = FMLPaths.CONFIGDIR.get().resolve("theywilltalk-admin-token.txt");
        this.api = new Api(this);
    }

    public void start() {
        try {
            token = loadOrCreateToken();
            String bind = TwtConfig.DASHBOARD_BIND.get();
            int port = TwtConfig.DASHBOARD_PORT.get();
            http = HttpServer.create(new InetSocketAddress(bind, port), 64);
            pool = Executors.newVirtualThreadPerTaskExecutor();
            http.setExecutor(pool);
            http.createContext("/", this::handle);
            http.start();
            TheyWillTalk.LOGGER.info("Admin dashboard: http://{}:{}/ (log in with the token in {}, or run /twt dashboard in game)",
                    bind.equals("0.0.0.0") ? "<server-ip>" : bind, port, tokenFile);
        } catch (IOException e) {
            TheyWillTalk.LOGGER.error("Could not start the dashboard on port {}: {}", TwtConfig.DASHBOARD_PORT.get(), e.toString());
        }
    }

    public void stop() {
        if (http != null) {
            http.stop(0);
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    public Path tokenFile() {
        return tokenFile;
    }

    /** One-time login link for /twt dashboard. */
    public String loginUrl() {
        String code = randomString(18);
        oneTimeCodes.put(code, System.currentTimeMillis() + 10 * 60_000L);
        String bind = TwtConfig.DASHBOARD_BIND.get();
        String host = bind.equals("0.0.0.0") ? "localhost" : bind;
        return "http://" + host + ":" + TwtConfig.DASHBOARD_PORT.get() + "/login?code=" + code;
    }

    private String loadOrCreateToken() throws IOException {
        if (Files.exists(tokenFile)) {
            String t = Files.readString(tokenFile).trim();
            if (t.length() >= 16) {
                return t;
            }
        }
        String t = randomString(24);
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, t + System.lineSeparator());
        return t;
    }

    private static String randomString(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // ---------------------------------------------------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            if (path.equals("/login")) {
                oneTimeLogin(ex);
            } else if (path.equals("/api/login")) {
                passwordLogin(ex);
            } else if (path.equals("/api/logout")) {
                String sid = cookie(ex, "twt_session");
                if (sid != null) {
                    sessions.remove(sid);
                }
                ex.getResponseHeaders().add("Set-Cookie", "twt_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
                sendJson(ex, 200, new JsonObject());
            } else if (path.startsWith("/api/")) {
                if (!authorized(ex)) {
                    sendJson(ex, 401, error("login required"));
                    return;
                }
                api.handle(ex, path.substring(4));
            } else if (path.equals(BlueMapProxy.PREFIX) || path.startsWith(BlueMapProxy.PREFIX + "/")) {
                if (!authorized(ex)) {
                    sendJson(ex, 401, error("login required"));
                    return;
                }
                BlueMapProxy.handle(ex);
            } else {
                serveStatic(ex, path);
            }
        } catch (Exception e) {
            TheyWillTalk.LOGGER.warn("Dashboard request {} failed: {}", ex.getRequestURI(), e.toString());
            try {
                sendJson(ex, 500, error(e.toString()));
            } catch (IOException ignored) {
                // client went away
            }
        } finally {
            ex.close();
        }
    }

    private boolean authorized(HttpExchange ex) {
        // Development runs only (set by the Gradle run configs): skip login for connections from this machine.
        if (Boolean.getBoolean("theywilltalk.dashboardNoAuth") && ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
            return true;
        }
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.equals("Bearer " + token)) {
            return true;
        }
        String sid = cookie(ex, "twt_session");
        if (sid == null) {
            return false;
        }
        Long exp = sessions.get(sid);
        if (exp == null || exp < System.currentTimeMillis()) {
            sessions.remove(sid);
            return false;
        }
        // Cookie-authenticated writes must come from our own page (CSRF protection).
        if (!ex.getRequestMethod().equals("GET")) {
            String xrw = ex.getRequestHeaders().getFirst("X-TWT");
            return "1".equals(xrw);
        }
        return true;
    }

    private void oneTimeLogin(HttpExchange ex) throws IOException {
        String code = query(ex.getRequestURI()).get("code");
        Long exp = code == null ? null : oneTimeCodes.remove(code);
        if (exp == null || exp < System.currentTimeMillis()) {
            redirect(ex, "/?login=expired", null);
            return;
        }
        redirect(ex, "/", newSession());
    }

    private void passwordLogin(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            sendJson(ex, 405, error("POST only"));
            return;
        }
        JsonObject body = readJson(ex);
        String given = body.has("token") ? body.get("token").getAsString().trim() : "";
        if (!constantTimeEquals(given, token)) {
            try {
                Thread.sleep(600); // slow down guessing
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            sendJson(ex, 403, error("wrong token"));
            return;
        }
        String sid = newSession();
        ex.getResponseHeaders().add("Set-Cookie", sessionCookie(sid));
        JsonObject ok = new JsonObject();
        ok.addProperty("ok", true);
        sendJson(ex, 200, ok);
    }

    private String newSession() {
        String sid = randomString(24);
        sessions.put(sid, System.currentTimeMillis() + SESSION_MS);
        return sid;
    }

    private static String sessionCookie(String sid) {
        return "twt_session=" + sid + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + (SESSION_MS / 1000);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        if (path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            send(ex, 400, "text/plain", "bad path".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String resource = "/assets/theywilltalk/web" + path;
        // Development: serve straight from the source tree so the UI can be edited without restarting.
        String devDir = System.getProperty("theywilltalk.webDir");
        if (devDir != null && Files.isRegularFile(Path.of(devDir, path))) {
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            send(ex, 200, contentType(path), Files.readAllBytes(Path.of(devDir, path)));
            return;
        }
        try (InputStream in = DashboardServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                // SPA fallback
                try (InputStream index = DashboardServer.class.getResourceAsStream("/assets/theywilltalk/web/index.html")) {
                    send(ex, 200, "text/html; charset=utf-8", index == null ? new byte[0] : index.readAllBytes());
                }
                return;
            }
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            send(ex, 200, contentType(path), in.readAllBytes());
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    // ---- helpers shared with Api ----------------------------------------------------------------------------

    static void redirect(HttpExchange ex, String to, String sid) throws IOException {
        if (sid != null) {
            ex.getResponseHeaders().add("Set-Cookie", sessionCookie(sid));
        }
        ex.getResponseHeaders().set("Location", to);
        ex.sendResponseHeaders(302, -1);
    }

    static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    static void sendJson(HttpExchange ex, int status, JsonElement json) throws IOException {
        send(ex, status, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8));
    }

    static JsonObject error(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        return o;
    }

    static JsonObject readJson(HttpExchange ex) throws IOException {
        String s = new String(ex.getRequestBody().readNBytes(64 * 1024), StandardCharsets.UTF_8);
        if (s.isBlank()) {
            return new JsonObject();
        }
        JsonElement e = JsonParser.parseString(s);
        return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    static Map<String, String> query(URI uri) {
        Map<String, String> m = new HashMap<>();
        String q = uri.getRawQuery();
        if (q == null) {
            return m;
        }
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? part : part.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    private static String cookie(HttpExchange ex, String name) {
        for (String header : ex.getRequestHeaders().getOrDefault("Cookie", java.util.List.of())) {
            for (String c : header.split(";")) {
                String[] kv = c.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(name)) {
                    return kv[1];
                }
            }
        }
        return null;
    }
}
