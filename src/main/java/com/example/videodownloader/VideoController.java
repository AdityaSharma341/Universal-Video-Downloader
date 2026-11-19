package com.example.videodownloader;

import com.example.videodownloader.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api")
public class VideoController {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentMap<String, DownloadJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Path downloadRoot;
    private final String ytDlpPath;
    private final String accessToken;
    private final Path cookieFilePath;
    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)%");
    private static final Set<String> SUPPORTED_BROWSERS = Set.of("chrome", "firefox", "edge", "brave", "opera", "vivaldi");

    public VideoController(
            @Value("${app.download-dir}") String downloadDir,
            @Value("${app.ytdlp-path}") String ytDlpPath,
            @Value("${app.access-token}") String accessToken) throws IOException {
        this.downloadRoot = Paths.get(downloadDir).toAbsolutePath().normalize();
        Files.createDirectories(downloadRoot);
        this.ytDlpPath = ytDlpPath;
        this.accessToken = accessToken == null ? "" : accessToken;
        this.cookieFilePath = downloadRoot.getParent() != null
                ? downloadRoot.getParent().resolve("cookies.txt")
                : downloadRoot.resolve("cookies.txt");
    }

    @GetMapping("/config")
    public ConfigResponse config() {
        return new ConfigResponse(!accessToken.isBlank(), Files.isRegularFile(cookieFilePath), cookieFilePath.toString());
    }

    @PostMapping("/info")
    public Map<String, Object> info(@RequestBody UrlRequest request, HttpServletRequest http) throws Exception {
        authorize(http.getHeader("X-App-Token"));
        String url = validateUrl(request.url());
        List<String> command = new ArrayList<>(List.of(ytDlpPath, "--dump-single-json", "--no-warnings", "--no-playlist"));
        if (request.useCookies()) {
            addCookieArgs(command, request.browser());
        }
        command.add(url);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
            output = result.toString();
        }
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new ResponseStatusException(GATEWAY_TIMEOUT, "The video site took too long to respond.");
        }
        if (process.exitValue() != 0) {
            if (request.useCookies()) {
                Map<String, Object> retry = new LinkedHashMap<>();
                retry.put("needsBrowser", true);
                retry.put("browserUrl", url);
                retry.put("errorDetail", safeMessage(output));
                retry.put("hasCookieFile", Files.isRegularFile(cookieFilePath));
                retry.put("cookieFilePath", cookieFilePath.toString());
                retry.put("message", "Could not read browser cookies. Use the cookie file method below.");
                return retry;
            }
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY, safeMessage(output));
        }
        JsonNode data = mapper.readTree(output);
        List<Integer> heights = new ArrayList<>();
        JsonNode formats = data.path("formats");
        if (formats.isArray()) {
            for (JsonNode format : formats) {
                JsonNode height = format.get("height");
                String vcodec = format.path("vcodec").asText("");
                if (height != null && height.canConvertToInt() && !"none".equals(vcodec)) heights.add(height.asInt());
            }
        }
        List<Integer> uniqueHeights = heights.stream().distinct().sorted(Comparator.reverseOrder()).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", data.path("title").asText("Untitled video"));
        result.put("thumbnail", data.path("thumbnail").asText(""));
        result.put("duration", data.path("duration").asInt(0));
        result.put("uploader", data.path("uploader").asText(data.path("channel").asText("Unknown creator")));
        result.put("qualities", uniqueHeights);
        return result;
    }

    @PostMapping("/download")
    public Map<String, String> start(@RequestBody DownloadRequest request, HttpServletRequest http) {
        authorize(http.getHeader("X-App-Token"));
        String url = validateUrl(request.url());
        String quality = request.quality() == null ? "best" : request.quality();
        if (!Set.of("best", "360", "480", "720", "1080", "1440", "2160").contains(quality)) {
            throw new ResponseStatusException(BAD_REQUEST, "Unsupported quality selection.");
        }
        String id = UUID.randomUUID().toString();
        DownloadJob job = new DownloadJob(id);
        jobs.put(id, job);
        boolean useCookies = request.useCookies();
        String browser = request.browser();
        executor.submit(() -> performDownload(job, url, quality, useCookies, browser));
        return Map.of("jobId", id);
    }

    @GetMapping("/status/{id}")
    public JobStatusResponse status(@PathVariable String id, HttpServletRequest http) {
        authorize(http.getHeader("X-App-Token"));
        DownloadJob job = getJob(id);
        return new JobStatusResponse(job.state, job.percent, job.message, job.filename);
    }

    @GetMapping("/file/{id}")
    public ResponseEntity<Resource> file(@PathVariable String id, @RequestParam(required = false) String token) throws IOException {
        authorize(token);
        DownloadJob job = getJob(id);
        if (!"finished".equals(job.state) || job.file == null || !Files.isRegularFile(job.file)) {
            throw new ResponseStatusException(NOT_FOUND, "The video is not ready.");
        }
        Resource resource = new FileSystemResource(job.file);
        String filename = job.file.getFileName().toString();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(job.file))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(resource);
    }

    @PostMapping("/open-browser")
    public Map<String, String> openBrowser(@RequestBody UrlRequest request, HttpServletRequest http) {
        authorize(http.getHeader("X-App-Token"));
        String url = validateUrl(request.url());
        String browser = request.browser() != null ? request.browser() : "chrome";
        try {
            String exe = switch (browser) {
                case "firefox" -> "firefox";
                case "edge" -> "msedge";
                case "brave" -> "brave";
                default -> "chrome";
            };
            new ProcessBuilder("cmd", "/c", "start", exe, url.replace("&", "^&"))
                    .start();
        } catch (IOException e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Could not open browser.");
        }
        return Map.of("status", "opened", "message", "Complete any prompts in the browser, then return here.");
    }

    /**
     * Upload a cookies.txt file exported from a browser extension.
     */
    @PostMapping("/upload-cookies")
    public Map<String, Object> uploadCookies(@RequestParam("file") MultipartFile file, HttpServletRequest http) {
        authorize(http.getHeader("X-App-Token"));
        if (file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "No file uploaded.");
        }
        try {
            byte[] content = file.getBytes();
            String text = new String(content, StandardCharsets.UTF_8);
            // Basic validation: Netscape cookie format starts with a comment or domain
            if (!text.contains("# Netscape HTTP Cookie File") && !text.contains("\t")) {
                throw new ResponseStatusException(BAD_REQUEST,
                        "This doesn't look like a valid cookies.txt file. Export it using the 'Get cookies.txt LOCALLY' extension.");
            }
            Files.write(cookieFilePath, content);
            return Map.of(
                    "status", "saved",
                    "path", cookieFilePath.toString(),
                    "message", "Cookie file saved! You can now detect and download the video."
            );
        } catch (IOException e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Could not save cookie file.");
        }
    }

    /**
     * Delete the stored cookies.txt file.
     */
    @DeleteMapping("/cookies")
    public Map<String, String> deleteCookies(HttpServletRequest http) {
        authorize(http.getHeader("X-App-Token"));
        try {
            Files.deleteIfExists(cookieFilePath);
        } catch (IOException e) {
            // Ignore
        }
        return Map.of("status", "deleted", "message", "Cookie file removed.");
    }

    private void performDownload(DownloadJob job, String url, String quality, boolean useCookies, String browser) {
        try {
            Path jobDir = downloadRoot.resolve(job.id).normalize();
            Files.createDirectories(jobDir);
            String format = quality.equals("best") ? "bv*+ba/b" :
                    "bestvideo[height<=" + quality + "]+bestaudio/best[height<=" + quality + "]/best";
            String output = jobDir.resolve("%(title).120B [%(id)s].%(ext)s").toString();
            List<String> command = new ArrayList<>(List.of(
                    ytDlpPath, "--newline", "--no-warnings", "--no-playlist",
                    "--format", format, "--merge-output-format", "mp4",
                    "--output", output));
            if (useCookies) {
                addCookieArgs(command, browser);
            }
            command.add(url);
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            job.state = "downloading"; job.message = "Downloading video...";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = PERCENT.matcher(line);
                    if (matcher.find()) {
                        try { job.percent = Math.min(99.0, Double.parseDouble(matcher.group(1))); }
                        catch (NumberFormatException ignored) { }
                    }
                    if (line.toLowerCase(Locale.ROOT).contains("merging formats") || line.toLowerCase(Locale.ROOT).contains("already been downloaded")) {
                        job.state = "processing"; job.message = "Preparing video...";
                    }
                }
            }
            int exit = process.waitFor();
            if (exit != 0) throw new IOException("yt-dlp could not download this page. It may be unsupported or access-restricted.");
            try (Stream<Path> files = Files.list(jobDir)) {
                job.file = files.filter(Files::isRegularFile)
                        .filter(p -> !p.toString().endsWith(".part"))
                        .max(Comparator.comparingLong(p -> p.toFile().length()))
                        .orElseThrow(() -> new IOException("No output video file was created. Check FFmpeg installation."));
            }
            job.filename = job.file.getFileName().toString();
            job.percent = 100.0; job.state = "finished"; job.message = "Your video is ready.";
        } catch (Exception ex) {
            job.state = "error";
            job.message = safeMessage(ex.getMessage());
        }
    }

    /**
     * Adds cookie arguments to the yt-dlp command.
     * Priority: cookies.txt file (most reliable) → --cookies-from-browser (fallback).
     */
    private void addCookieArgs(List<String> command, String browser) {
        if (Files.isRegularFile(cookieFilePath)) {
            command.add("--cookies");
            command.add(cookieFilePath.toString());
        } else {
            String b = browser != null && SUPPORTED_BROWSERS.contains(browser) ? browser : "chrome";
            command.add("--cookies-from-browser");
            command.add(b);
        }
    }

    private DownloadJob getJob(String id) {
        DownloadJob job = jobs.get(id);
        if (job == null) throw new ResponseStatusException(NOT_FOUND, "Download job not found.");
        return job;
    }

    private String validateUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 4096) throw new ResponseStatusException(BAD_REQUEST, "Enter a valid webpage URL.");
        try {
            URI uri = URI.create(value.trim());
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException();
            return uri.toString();
        } catch (Exception ex) {
            throw new ResponseStatusException(BAD_REQUEST, "Only valid http:// and https:// URLs are accepted.");
        }
    }

    private void authorize(String supplied) {
        if (!accessToken.isBlank() && !accessToken.equals(supplied)) throw new ResponseStatusException(UNAUTHORIZED, "Invalid or missing access token.");
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) return "The operation failed. Check the URL and try again.";
        return message.length() > 450 ? message.substring(0, 450) : message;
    }

    private static class DownloadJob {
        final String id;
        volatile String state = "queued";
        volatile double percent = 0;
        volatile String message = "Waiting to start...";
        volatile String filename;
        volatile Path file;
        DownloadJob(String id) { this.id = id; }
    }
}
