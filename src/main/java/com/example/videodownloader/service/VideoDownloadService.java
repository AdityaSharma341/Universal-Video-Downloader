package com.example.videodownloader.service;

import com.example.videodownloader.dto.SystemCheckResponse;
import com.example.videodownloader.dto.VideoInfoResponse;
import com.example.videodownloader.model.DownloadJob;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class VideoDownloadService {

    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)%");
    private static final Set<String> SUPPORTED_BROWSERS = Set.of("chrome", "firefox", "edge", "brave", "opera", "vivaldi");
    private static final List<String> FALLBACK_BROWSERS = List.of("firefox", "edge", "chrome", "brave", "opera", "vivaldi");

    /** Number of concurrent fragment downloads for speed. */
    private static final int CONCURRENT_FRAGMENTS = 8;
    private static final long SERVED_CLEANUP_DELAY_MS = 60_000L;
    private static final long UNSERVED_EXPIRY_MS = 15 * 60_000L;
    private static final Path RENDER_SECRET_COOKIE = Paths.get("/etc/secrets/cookies.txt");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentMap<String, DownloadJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "videodrop-temp-cleanup");
        t.setDaemon(true);
        return t;
    });

    private final Path downloadRoot;
    private final boolean usingSystemTemp;
    private final String ytDlpPath;
    private final Path cookieFilePath;

    public VideoDownloadService(String downloadDir, String ytDlpPath) throws IOException {
        this(downloadDir, ytDlpPath, "cookies.txt", "", "");
    }

    public VideoDownloadService(String downloadDir, String ytDlpPath, String cookieFile) throws IOException {
        this(downloadDir, ytDlpPath, cookieFile, "", "");
    }

    @Autowired
    public VideoDownloadService(
            @Value("${app.download-dir:}") String downloadDir,
            @Value("${app.ytdlp-path:yt-dlp}") String ytDlpPath,
            @Value("${app.cookie-file:cookies.txt}") String cookieFile,
            @Value("${app.cookies-b64:}") String cookiesB64,
            @Value("${app.cookies-txt:}") String cookiesTxt) throws IOException {
        this.usingSystemTemp = (downloadDir == null || downloadDir.isBlank());
        this.downloadRoot = this.usingSystemTemp
                ? Paths.get(System.getProperty("java.io.tmpdir"), "videodrop-temp").toAbsolutePath().normalize()
                : Paths.get(downloadDir).toAbsolutePath().normalize();
        Files.createDirectories(this.downloadRoot);

        if (this.usingSystemTemp) {
            cleanStaleTempDirectories();
        }

        this.ytDlpPath = ytDlpPath;
        this.cookieFilePath = Paths.get(cookieFile).toAbsolutePath().normalize();
        seedCookiesFromEnvIfAbsent(cookiesB64, cookiesTxt);

        this.cleanupScheduler.scheduleWithFixedDelay(this::evictExpiredJobs, 30, 30, TimeUnit.SECONDS);
    }

    private void seedCookiesFromEnvIfAbsent(String cookiesB64, String cookiesTxt) throws IOException {
        if (Files.isRegularFile(cookieFilePath)) {
            return;
        }
        if (cookiesB64 != null && !cookiesB64.isBlank()) {
            byte[] decoded = Base64.getMimeDecoder().decode(cookiesB64.trim());
            if (cookieFilePath.getParent() != null) {
                Files.createDirectories(cookieFilePath.getParent());
            }
            Files.write(cookieFilePath, decoded);
            return;
        }
        if (cookiesTxt != null && !cookiesTxt.isBlank()) {
            String normalized = (!cookiesTxt.contains("\n") && cookiesTxt.contains("\\n"))
                    ? cookiesTxt.replace("\\n", "\n").replace("\\t", "\t")
                    : cookiesTxt;
            if (cookieFilePath.getParent() != null) {
                Files.createDirectories(cookieFilePath.getParent());
            }
            Files.writeString(cookieFilePath, normalized, StandardCharsets.UTF_8);
        }
    }

    public Path getDownloadRoot() { return downloadRoot; }
    public boolean isUsingSystemTemp() { return usingSystemTemp; }
    public Path getCookieFilePath() { return cookieFilePath; }
    public boolean hasCookieFile() {
        return Files.isRegularFile(cookieFilePath) || Files.isRegularFile(RENDER_SECRET_COOKIE);
    }

    private Path resolveActiveCookieFile() {
        return Files.isRegularFile(cookieFilePath) ? cookieFilePath : RENDER_SECRET_COOKIE;
    }

    public void saveCookies(byte[] content) throws IOException {
        Files.write(cookieFilePath, content);
    }

    public boolean deleteCookies() {
        try { return Files.deleteIfExists(cookieFilePath); }
        catch (IOException e) { return false; }
    }

    public SystemCheckResponse checkDiagnostics() {
        var ytdlpStatus = checkTool(List.of(ytDlpPath, "--version"), ytDlpPath);
        var ffmpegStatus = checkTool(List.of("ffmpeg", "-version"), "ffmpeg");
        File rootFile = downloadRoot.toFile();
        var diskStatus = new SystemCheckResponse.DiskStatus(
                rootFile.getUsableSpace(), rootFile.getTotalSpace(), downloadRoot.toString());
        boolean healthy = ytdlpStatus.available() && ffmpegStatus.available();
        return new SystemCheckResponse(healthy, ytdlpStatus, ffmpegStatus, diskStatus);
    }

    private SystemCheckResponse.ToolStatus checkTool(List<String> command, String path) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.readLine();
            }
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) { process.destroyForcibly(); return new SystemCheckResponse.ToolStatus(false, null, path, "Execution timed out"); }
            if (process.exitValue() == 0 && output != null) return new SystemCheckResponse.ToolStatus(true, output.trim(), path, null);
            return new SystemCheckResponse.ToolStatus(false, null, path, "Process exited with code " + process.exitValue());
        } catch (Exception e) {
            return new SystemCheckResponse.ToolStatus(false, null, path, e.getMessage());
        }
    }

    public Optional<DownloadJob> findJob(String id) { return Optional.ofNullable(jobs.get(id)); }

    public void markJobServed(String id) {
        DownloadJob job = jobs.get(id);
        if (job != null) {
            job.markDownloaded();
        }
    }

    public boolean cancelJob(String id) {
        DownloadJob job = jobs.get(id);
        if (job == null) return false;
        if ("downloading".equals(job.getState()) || "processing".equals(job.getState()) || "queued".equals(job.getState())) {
            job.setState("cancelled");
            job.setMessage("Download was cancelled by the user.");
            Process proc = job.getProcess();
            if (proc != null && proc.isAlive()) {
                proc.destroyForcibly();
                try { proc.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            deleteJobDirectory(id);
            return true;
        }
        return false;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Video info with per-resolution estimated file sizes & auto cookies
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Fetches video metadata including all available resolutions, estimated
     * downloading sizes per resolution, and audio availability.
     * Cookies are handled automatically (cookies.txt first, then direct / browser fallback).
     */
    public VideoInfoResponse fetchVideoInfo(String url, String browser) throws Exception {
        String output = runInfoWithAutoCookies(url, browser);
        JsonNode data = mapper.readTree(output);
        return parseVideoInfo(data);
    }

    /**
     * Parses yt-dlp --dump-single-json output into VideoInfoResponse with
     * accurate per-resolution downloading sizes.
     */
    public VideoInfoResponse parseVideoInfo(JsonNode data) {
        double videoDuration = data.path("duration").asDouble(0);
        JsonNode formats = data.path("formats");

        Set<Integer> allHeights = new HashSet<>();
        Map<Integer, Long> bestVideoOnlySizeByHeight = new LinkedHashMap<>();
        Map<Integer, Long> bestCombinedSizeByHeight = new LinkedHashMap<>();
        boolean hasAudio = false;
        long bestAudioOnlySize = 0;
        double bestAudioBitrate = 0;

        if (formats.isArray()) {
            for (JsonNode fmt : formats) {
                String vcodec = fmt.path("vcodec").asText("");
                String acodec = fmt.path("acodec").asText("");
                String ext = fmt.path("ext").asText("");
                String protocol = fmt.path("protocol").asText("");
                String note = fmt.path("format_note").asText("");

                // Skip storyboards and image thumbnails
                if ("mhtml".equalsIgnoreCase(ext)
                        || "mhtml".equalsIgnoreCase(protocol)
                        || "images".equalsIgnoreCase(vcodec)
                        || note.toLowerCase(Locale.ROOT).contains("storyboard")) {
                    continue;
                }

                long fsize = getFilesize(fmt, videoDuration);
                boolean hasVideo = !"none".equalsIgnoreCase(vcodec);
                boolean hasFmtAudio = !"none".equalsIgnoreCase(acodec);

                JsonNode heightNode = fmt.get("height");
                if (heightNode != null && heightNode.canConvertToInt() && hasVideo) {
                    int h = heightNode.asInt();
                    if (h > 0) {
                        allHeights.add(h);
                        if (!hasFmtAudio) {
                            bestVideoOnlySizeByHeight.merge(h, fsize, Math::max);
                        } else {
                            bestCombinedSizeByHeight.merge(h, fsize, Math::max);
                        }
                    }
                }

                // Audio-only tracks
                if ("none".equalsIgnoreCase(vcodec) && hasFmtAudio) {
                    hasAudio = true;
                    bestAudioOnlySize = Math.max(bestAudioOnlySize, fsize);
                    bestAudioBitrate = Math.max(bestAudioBitrate, fmt.path("abr").asDouble(0));
                }

                // Combined tracks also indicate audio is available
                if (hasFmtAudio) {
                    hasAudio = true;
                    bestAudioBitrate = Math.max(bestAudioBitrate, fmt.path("abr").asDouble(0));
                }
            }
        }

        // If no separate audio-only size was reported, estimate audio download size from abr if available
        if (bestAudioOnlySize <= 0 && hasAudio && bestAudioBitrate > 0 && videoDuration > 0) {
            bestAudioOnlySize = (long) (bestAudioBitrate * 1000.0 / 8.0 * videoDuration);
        }

        List<Integer> uniqueHeights = allHeights.stream()
                .sorted(Comparator.reverseOrder())
                .toList();

        // Build sizeMap: height -> estimated total downloading bytes
        Map<String, Long> sizeMap = new LinkedHashMap<>();
        long topLevelFallbackSize = getFilesize(data, videoDuration);
        long maxKnownSize = 0;

        for (int h : uniqueHeights) {
            long videoOnlyBytes = bestVideoOnlySizeByHeight.getOrDefault(h, 0L);
            long combinedBytes = bestCombinedSizeByHeight.getOrDefault(h, 0L);

            long totalBytes = 0L;
            if (videoOnlyBytes > 0 && bestAudioOnlySize > 0) {
                totalBytes = videoOnlyBytes + bestAudioOnlySize;
            } else if (combinedBytes > 0) {
                totalBytes = combinedBytes;
            } else if (videoOnlyBytes > 0) {
                totalBytes = videoOnlyBytes;
            } else if (uniqueHeights.size() == 1 && topLevelFallbackSize > 0) {
                totalBytes = topLevelFallbackSize;
            }

            sizeMap.put(String.valueOf(h), totalBytes);
            if (totalBytes > maxKnownSize) {
                maxKnownSize = totalBytes;
            }
        }

        long bestSize = 0L;
        if (!uniqueHeights.isEmpty()) {
            bestSize = sizeMap.getOrDefault(String.valueOf(uniqueHeights.get(0)), 0L);
        }
        if (bestSize <= 0) {
            bestSize = maxKnownSize > 0 ? maxKnownSize : topLevelFallbackSize;
        }
        sizeMap.put("best", bestSize);
        sizeMap.put("audio", bestAudioOnlySize);

        return new VideoInfoResponse(
                data.path("title").asText("Untitled video"),
                data.path("thumbnail").asText(""),
                data.path("duration").asInt(0),
                data.path("uploader").asText(data.path("channel").asText("Unknown creator")),
                uniqueHeights,
                hasAudio,
                sizeMap
        );
    }

    /**
     * Extracts exact or estimated filesize in bytes from a format or video JSON node,
     * falling back to bitrate * duration using the provided video duration.
     */
    long getFilesize(JsonNode fmt, double videoDuration) {
        JsonNode fs = fmt.get("filesize");
        if (fs != null && fs.canConvertToLong() && fs.asLong() > 0) {
            return fs.asLong();
        }
        JsonNode fsa = fmt.get("filesize_approx");
        if (fsa != null && fsa.canConvertToLong() && fsa.asLong() > 0) {
            return fsa.asLong();
        }
        double bitrate = fmt.path("tbr").asDouble(0);
        if (bitrate <= 0) {
            double vbr = fmt.path("vbr").asDouble(0);
            double abr = fmt.path("abr").asDouble(0);
            if (vbr > 0 || abr > 0) {
                bitrate = vbr + abr;
            }
        }
        double dur = fmt.path("duration").asDouble(0);
        if (dur <= 0) {
            dur = videoDuration;
        }
        if (bitrate > 0 && dur > 0) {
            return (long) (bitrate * 1000.0 / 8.0 * dur);
        }
        return 0L;
    }

    private String runInfoWithAutoCookies(String url, String browser) throws Exception {
        List<List<String>> strategies = buildCookieStrategies(browser);
        String firstOutput = "";
        String lastOutput = "";
        int lastExit = 1;

        for (int i = 0; i < strategies.size(); i++) {
            List<String> command = new ArrayList<>(
                    List.of(ytDlpPath, "--dump-single-json", "--no-warnings", "--no-playlist"));
            command.addAll(strategies.get(i));
            command.add(url);

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = readProcessOutput(process);

            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "The video site took too long to respond.");
            }
            if (process.exitValue() == 0) {
                return output;
            }

            if (i == 0) {
                firstOutput = output;
            }
            lastOutput = output;
            lastExit = process.exitValue();

            // Only retry with next cookie strategy if error relates to auth/cookies/bot-check
            if (i == 0 && !hasCookieFile() && !looksLikeCookieOrAuthIssue(output)) {
                break;
            }
        }

        if (lastExit != 0) {
            if (!hasCookieFile() && looksLikeCookieOrAuthIssue(firstOutput) && looksLikeMissingBrowserDb(lastOutput)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "This site requires sign-in or bot verification from this server. Open 'Manage cookies' and upload a cookies.txt file.");
            }
            String bestError = (!hasCookieFile() && looksLikeMissingBrowserDb(lastOutput) && !firstOutput.isBlank())
                    ? firstOutput
                    : lastOutput;
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, safeMessage(bestError));
        }
        return lastOutput;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Download with concurrent fragments, chunking, and automatic cookies
    // ────────────────────────────────────────────────────────────────────────

    public String submitDownloadJob(String url, String quality, String mode, String browser) {
        String id = UUID.randomUUID().toString();
        DownloadJob job = new DownloadJob(id);
        jobs.put(id, job);
        executor.submit(() -> performDownload(job, url, quality, mode, browser));
        return id;
    }

    private void performDownload(DownloadJob job, String url, String quality, String mode, String browser) {
        try {
            Path jobDir = downloadRoot.resolve(job.getId()).normalize();
            Files.createDirectories(jobDir);

            boolean audioOnly = "audio".equalsIgnoreCase(mode);
            String outputPattern = jobDir.resolve("%(title).120B [%(id)s].%(ext)s").toString();

            List<List<String>> strategies = buildCookieStrategies(browser);
            int exit = -1;
            String lastErrorOutput = "";

            for (int i = 0; i < strategies.size(); i++) {
                if ("cancelled".equals(job.getState())) return;

                List<String> command = buildDownloadCommand(outputPattern, quality, audioOnly);
                command.addAll(strategies.get(i));
                command.add(url);

                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                job.setProcess(process);
                job.setState("downloading");
                job.setPercent(0);
                job.setMessage(audioOnly ? "Preparing audio download..." : "Preparing video download...");

                StringBuilder outputCapture = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if ("cancelled".equals(job.getState())) break;
                        outputCapture.append(line).append('\n');
                        Matcher matcher = PERCENT.matcher(line);
                        if (matcher.find()) {
                            try {
                                job.setPercent(Math.min(99.0, Double.parseDouble(matcher.group(1))));
                            } catch (NumberFormatException ignored) {}
                        }
                        String lower = line.toLowerCase(Locale.ROOT);
                        if (lower.contains("merging formats") || lower.contains("already been downloaded")) {
                            job.setState("processing");
                            job.setMessage("Merging video and audio...");
                        }
                        if (lower.contains("converting") || lower.contains("extracting audio")) {
                            job.setState("processing");
                            job.setMessage("Converting to audio...");
                        }
                        if (lower.contains("embedding thumbnail") || lower.contains("adding metadata")) {
                            job.setState("processing");
                            job.setMessage("Embedding thumbnail & tags...");
                        }
                    }
                }

                exit = process.waitFor();
                if ("cancelled".equals(job.getState())) return;

                if (exit == 0) {
                    break;
                }

                lastErrorOutput = outputCapture.toString();
                if (i == 0 && !hasCookieFile() && !looksLikeCookieOrAuthIssue(lastErrorOutput)) {
                    break;
                }
            }

            if ("cancelled".equals(job.getState())) return;

            if (exit != 0) {
                String detail = safeMessage(lastErrorOutput);
                throw new IOException(detail.isBlank()
                        ? "yt-dlp could not download this page. It may be unsupported or access-restricted."
                        : detail);
            }

            try (Stream<Path> files = Files.list(jobDir)) {
                Path file = files.filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            return !name.endsWith(".part") && !name.endsWith(".ytdl")
                                    && !name.endsWith(".jpg") && !name.endsWith(".jpeg")
                                    && !name.endsWith(".png") && !name.endsWith(".webp");
                        })
                        .max(Comparator.comparingLong(p -> p.toFile().length()))
                        .orElseThrow(() -> new IOException("No output file was created. Check FFmpeg installation."));
                job.setFile(file);
                job.setFilename(file.getFileName().toString());
            }

            job.setPercent(100.0);
            job.setState("finished");
            job.setMessage("Starting browser download...");
        } catch (Exception ex) {
            if (!"cancelled".equals(job.getState())) {
                job.setState("error");
                job.setMessage(safeMessage(ex.getMessage()));
                deleteJobDirectory(job.getId());
            }
        } finally {
            job.setProcess(null);
        }
    }

    List<String> buildDownloadCommand(String outputPattern, String quality, boolean audioOnly) {
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add("--newline");
        command.add("--no-warnings");
        command.add("--no-playlist");

        // ── Speed optimizations ────────────────────────────────────────
        // Download 8 fragments in parallel for DASH/HLS streams
        command.add("--concurrent-fragments");
        command.add(String.valueOf(CONCURRENT_FRAGMENTS));
        // Larger I/O buffer for faster throughput
        command.add("--buffer-size");
        command.add("64K");
        command.add("--no-resize-buffer");
        // Chunked HTTP downloads help bypass single-stream throttling
        command.add("--http-chunk-size");
        command.add("10M");
        // Fast retries and direct output writing
        command.add("--retries");
        command.add("3");
        command.add("--fragment-retries");
        command.add("3");
        command.add("--no-part");

        if (audioOnly) {
            command.addAll(List.of(
                    "--extract-audio",
                    "--audio-format", "mp3",
                    "--audio-quality", "0",
                    "--embed-thumbnail",
                    "--convert-thumbnails", "jpg",
                    "--embed-metadata",
                    "--output", outputPattern));
        } else {
            String format = "best".equals(quality)
                    ? "bv*+ba/b"
                    : "bestvideo[height<=" + quality + "]+bestaudio/best[height<=" + quality + "]/best";
            command.addAll(List.of(
                    "--format", format,
                    "--merge-output-format", "mp4",
                    "--output", outputPattern));
        }
        return command;
    }

    /**
     * Builds ordered cookie argument strategies:
     * - If cookies.txt exists: use it directly.
     * - Otherwise: try direct first (fast, avoids Windows Chrome DPAPI lock on public videos),
     *   then automatically fall back to preferred browser and other installed browsers.
     */
    private List<List<String>> buildCookieStrategies(String browser) {
        if (hasCookieFile()) {
            return List.of(List.of("--cookies", resolveActiveCookieFile().toString()));
        }
        List<List<String>> strategies = new ArrayList<>();
        strategies.add(List.of()); // Try direct without cookies first
        String preferred = browser != null && SUPPORTED_BROWSERS.contains(browser) ? browser : "chrome";
        strategies.add(List.of("--cookies-from-browser", preferred));
        for (String fallback : FALLBACK_BROWSERS) {
            if (!fallback.equals(preferred)) {
                strategies.add(List.of("--cookies-from-browser", fallback));
            }
        }
        return strategies;
    }

    private boolean looksLikeCookieOrAuthIssue(String output) {
        if (output == null) return false;
        String lower = output.toLowerCase(Locale.ROOT);
        return lower.contains("sign in")
                || lower.contains("cookies")
                || lower.contains("cookie")
                || lower.contains("bot")
                || lower.contains("age")
                || lower.contains("login")
                || lower.contains("logged-in")
                || lower.contains("private")
                || lower.contains("members")
                || lower.contains("403")
                || lower.contains("401")
                || lower.contains("confirm you")
                || lower.contains("dpapi")
                || lower.contains("could not copy");
    }

    private boolean looksLikeMissingBrowserDb(String output) {
        if (output == null) return false;
        String lower = output.toLowerCase(Locale.ROOT);
        return lower.contains("could not find") && lower.contains("cookies database")
                || lower.contains("unsupported") && lower.contains("browser");
    }

    private String readProcessOutput(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!result.isEmpty()) result.append('\n');
                result.append(line);
            }
            return result.toString();
        }
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) return "The operation failed. Check the URL and try again.";
        String trimmed = message.trim();
        return trimmed.length() > 450 ? trimmed.substring(0, 450) : trimmed;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Temporary file cleanup (keeps disk & project directory clean)
    // ────────────────────────────────────────────────────────────────────────

    void evictExpiredJobs() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, DownloadJob> entry : jobs.entrySet()) {
            DownloadJob job = entry.getValue();
            String state = job.getState();
            boolean expiredServed = job.getDownloadedAt() > 0 && (now - job.getDownloadedAt()) >= SERVED_CLEANUP_DELAY_MS;
            boolean expiredUnserved = "finished".equals(state) && job.getDownloadedAt() == 0
                    && (now - job.getCreatedAt()) >= UNSERVED_EXPIRY_MS;
            boolean finishedErrorOrCancel = ("error".equals(state) || "cancelled".equals(state))
                    && (now - job.getCreatedAt()) >= 10_000L;

            if (expiredServed || expiredUnserved || finishedErrorOrCancel) {
                deleteJobDirectory(job.getId());
                jobs.remove(entry.getKey());
            }
        }
    }

    private void deleteJobDirectory(String jobId) {
        Path jobDir = downloadRoot.resolve(jobId).normalize();
        if (jobDir.startsWith(downloadRoot) && Files.exists(jobDir)) {
            deleteDirQuietly(jobDir);
        }
    }

    private void cleanStaleTempDirectories() {
        try (Stream<Path> entries = Files.list(downloadRoot)) {
            entries.filter(Files::isDirectory).forEach(VideoDownloadService::deleteDirQuietly);
        } catch (IOException ignored) {}
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
        } catch (IOException ignored) {}
    }

    @PreDestroy
    public void cleanup() {
        cleanupScheduler.shutdownNow();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (usingSystemTemp) {
            cleanStaleTempDirectories();
        }
    }
}
