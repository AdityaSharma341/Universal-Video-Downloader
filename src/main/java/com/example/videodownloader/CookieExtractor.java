package com.example.videodownloader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

/**
 * Automates cookie extraction from Chrome using Chrome DevTools Protocol (CDP).
 * <p>
 * Flow: launch Chrome with --remote-debugging-port → user completes verification →
 * connect via CDP WebSocket → Network.getAllCookies → write Netscape cookies.txt.
 */
@Service
public class CookieExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEBUG_PORT = 9222;

    private volatile Process chromeProcess;
    private volatile Path tempProfileDir;

    // ── Launch ────────────────────────────────────────────────────────────

    /**
     * Opens Chrome with remote debugging so we can later extract cookies via CDP.
     * Uses a temporary profile to avoid interfering with the user's normal Chrome.
     */
    public synchronized void launch(String url) throws IOException {
        close();

        String chrome = findChromePath();
        if (chrome == null) {
            throw new IOException(
                    "Chrome not found. Expected in Program Files or LocalAppData.");
        }

        tempProfileDir = Files.createTempDirectory("videodrop-cdp-");

        chromeProcess = new ProcessBuilder(
                chrome,
                "--remote-debugging-port=" + DEBUG_PORT,
                "--user-data-dir=" + tempProfileDir,
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-background-networking",
                url
        ).redirectErrorStream(true).start();

        waitForDebugPort();
    }

    // ── Extract cookies ──────────────────────────────────────────────────

    /**
     * Connects to the running Chrome via CDP and extracts every cookie.
     *
     * @return list of cookie maps (domain, path, secure, expires, name, value)
     */
    public List<Map<String, Object>> extractCookies() throws Exception {
        if (!isRunning()) {
            throw new IOException(
                    "Chrome is not running. Click \"Open site\" first.");
        }

        HttpClient client = HttpClient.newHttpClient();

        // 1. Find the first page target
        HttpResponse<String> targets = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + DEBUG_PORT + "/json"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        JsonNode pages = MAPPER.readTree(targets.body());
        String wsUrl = null;
        for (JsonNode t : pages) {
            if ("page".equals(t.path("type").asText())) {
                wsUrl = t.path("webSocketDebuggerUrl").asText(null);
                break;
            }
        }
        if (wsUrl == null) {
            throw new IOException("No page found in Chrome debugging session.");
        }

        // 2. Connect via WebSocket and ask for all cookies
        CompletableFuture<String> response = new CompletableFuture<>();

        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket,
                                                     CharSequence data, boolean last) {
                        buf.append(data);
                        webSocket.request(1);
                        if (last) {
                            String msg = buf.toString();
                            buf.setLength(0);
                            try {
                                JsonNode node = MAPPER.readTree(msg);
                                if (node.has("id") && node.get("id").asInt(-1) == 1) {
                                    response.complete(msg);
                                }
                            } catch (Exception ignored) { }
                        }
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket,
                                                      int code, String reason) {
                        if (!response.isDone()) {
                            response.completeExceptionally(
                                    new IOException("Chrome closed the connection."));
                        }
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        response.completeExceptionally(error);
                    }
                }).join();

        ws.sendText("{\"id\":1,\"method\":\"Network.getAllCookies\"}", true);

        String raw = response.get(15, TimeUnit.SECONDS);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");

        // 3. Parse the cookie array
        JsonNode cookies = MAPPER.readTree(raw).path("result").path("cookies");
        List<Map<String, Object>> list = new ArrayList<>();
        if (cookies.isArray()) {
            for (JsonNode c : cookies) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("domain",   c.path("domain").asText());
                m.put("path",     c.path("path").asText("/"));
                m.put("secure",   c.path("secure").asBoolean());
                m.put("expires",  c.path("expires").asDouble(-1));
                m.put("name",     c.path("name").asText());
                m.put("value",    c.path("value").asText());
                list.add(m);
            }
        }
        return list;
    }

    // ── Write cookie file ────────────────────────────────────────────────

    /**
     * Writes cookies in Netscape format that yt-dlp can read with --cookies.
     *
     * @return number of cookies written
     */
    public int writeCookieFile(List<Map<String, Object>> cookies,
                               Path outputPath) throws IOException {
        var sb = new StringBuilder();
        sb.append("# Netscape HTTP Cookie File\n");
        sb.append("# Generated by VideoDrop\n\n");

        for (var c : cookies) {
            String domain = (String) c.get("domain");
            String path   = (String) c.get("path");
            boolean secure = (Boolean) c.get("secure");
            double  exp    = (Double) c.get("expires");
            String name    = (String) c.get("name");
            String value   = (String) c.get("value");

            sb.append(domain).append('\t')
              .append(domain.startsWith(".") ? "TRUE" : "FALSE").append('\t')
              .append(path).append('\t')
              .append(secure ? "TRUE" : "FALSE").append('\t')
              .append(exp > 0 ? (long) exp : 0).append('\t')
              .append(name).append('\t')
              .append(value).append('\n');
        }

        Files.writeString(outputPath, sb.toString(), StandardCharsets.UTF_8);
        return cookies.size();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    public boolean isRunning() {
        return chromeProcess != null && chromeProcess.isAlive();
    }

    @PreDestroy
    public synchronized void close() {
        if (chromeProcess != null) {
            chromeProcess.destroyForcibly();
            try { chromeProcess.waitFor(5, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            chromeProcess = null;
        }
        if (tempProfileDir != null) {
            deleteDirQuietly(tempProfileDir);
            tempProfileDir = null;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void waitForDebugPort() throws IOException {
        HttpClient client = HttpClient.newHttpClient();
        for (int i = 0; i < 30; i++) {
            try {
                Thread.sleep(500);
                client.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + DEBUG_PORT + "/json/version"))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
                return;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for Chrome.");
            } catch (Exception ignored) { }
        }
        close();
        throw new IOException("Chrome debug port did not respond within 15 seconds.");
    }

    static String findChromePath() {
        String[] envVars = { "LOCALAPPDATA", "ProgramFiles", "ProgramFiles(x86)" };
        for (String env : envVars) {
            String base = System.getenv(env);
            if (base != null) {
                Path p = Path.of(base, "Google", "Chrome", "Application", "chrome.exe");
                if (Files.isRegularFile(p)) return p.toString();
            }
        }
        return null;
    }

    private static void deleteDirQuietly(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    Files.deleteIfExists(f);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) { }
    }
}
