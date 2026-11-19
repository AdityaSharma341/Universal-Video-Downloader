package com.example.videodownloader.service;

import com.example.videodownloader.model.DownloadJob;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class VideoDownloadService {

    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)%");
    private static final Set<String> SUPPORTED_BROWSERS = Set.of("chrome", "firefox", "edge", "brave", "opera", "vivaldi");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentMap<String, DownloadJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private final Path downloadRoot;
    private final String ytDlpPath;
    private final Path cookieFilePath;

    public VideoDownloadService(
            @Value("${app.download-dir:downloads}") String downloadDir,
            @Value("${app.ytdlp-path:yt-dlp}") String ytDlpPath) throws IOException {
        this.downloadRoot = Paths.get(downloadDir).toAbsolutePath().normalize();
        Files.createDirectories(this.downloadRoot);
        this.ytDlpPath = ytDlpPath;
        this.cookieFilePath = this.downloadRoot.getParent() != null
                ? this.downloadRoot.getParent().resolve("cookies.txt")
                : this.downloadRoot.resolve("cookies.txt");
    }

    public Path getDownloadRoot() {
        return downloadRoot;
    }

    public Path getCookieFilePath() {
        return cookieFilePath;
    }

    public boolean hasCookieFile() {
        return Files.isRegularFile(cookieFilePath);
    }

    public void saveCookies(byte[] content) throws IOException {
        Files.write(cookieFilePath, content);
    }

    public boolean deleteCookies() {
        try {
            return Files.deleteIfExists(cookieFilePath);
        } catch (IOException e) {
            return false;
        }
    }

    public Optional<DownloadJob> findJob(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public Map<String, Object> fetchVideoInfo(String url, boolean useCookies, String browser) throws Exception {
        List<String> command = new ArrayList<>(List.of(ytDlpPath, "--dump-single-json", "--no-warnings", "--no-playlist"));
        if (useCookies) {
            addCookieArgs(command, browser);
        }
        command.add(url);

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
            output = result.toString();
        }

        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "The video site took too long to respond.");
        }

        if (process.exitValue() != 0) {
            if (useCookies) {
                Map<String, Object> retry = new LinkedHashMap<>();
                retry.put("needsBrowser", true);
                retry.put("browserUrl", url);
                retry.put("errorDetail", safeMessage(output));
                retry.put("hasCookieFile", hasCookieFile());
                retry.put("cookieFilePath", cookieFilePath.toString());
                retry.put("message", "Could not read browser cookies. Use the cookie file method below.");
                return retry;
            }
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, safeMessage(output));
        }

        JsonNode data = mapper.readTree(output);
        List<Integer> heights = new ArrayList<>();
        JsonNode formats = data.path("formats");
        if (formats.isArray()) {
            for (JsonNode format : formats) {
                JsonNode height = format.get("height");
                String vcodec = format.path("vcodec").asText("");
                if (height != null && height.canConvertToInt() && !"none".equals(vcodec)) {
                    heights.add(height.asInt());
                }
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

    public String submitDownloadJob(String url, String quality, boolean useCookies, String browser) {
        String id = UUID.randomUUID().toString();
        DownloadJob job = new DownloadJob(id);
        jobs.put(id, job);
        executor.submit(() -> performDownload(job, url, quality, useCookies, browser));
        return id;
    }

    private void performDownload(DownloadJob job, String url, String quality, boolean useCookies, String browser) {
        try {
            Path jobDir = downloadRoot.resolve(job.getId()).normalize();
            Files.createDirectories(jobDir);
            String format = "best".equals(quality)
                    ? "bv*+ba/b"
                    : "bestvideo[height<=" + quality + "]+bestaudio/best[height<=" + quality + "]/best";
            String outputPattern = jobDir.resolve("%(title).120B [%(id)s].%(ext)s").toString();

            List<String> command = new ArrayList<>(List.of(
                    ytDlpPath, "--newline", "--no-warnings", "--no-playlist",
                    "--format", format, "--merge-output-format", "mp4",
                    "--output", outputPattern));

            if (useCookies) {
                addCookieArgs(command, browser);
            }
            command.add(url);

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            job.setProcess(process);
            job.setState("downloading");
            job.setMessage("Downloading video...");

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = PERCENT.matcher(line);
                    if (matcher.find()) {
                        try {
                            job.setPercent(Math.min(99.0, Double.parseDouble(matcher.group(1))));
                        } catch (NumberFormatException ignored) {}
                    }
                    if (line.toLowerCase(Locale.ROOT).contains("merging formats")
                            || line.toLowerCase(Locale.ROOT).contains("already been downloaded")) {
                        job.setState("processing");
                        job.setMessage("Preparing video...");
                    }
                }
            }

            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("yt-dlp could not download this page. It may be unsupported or access-restricted.");
            }

            try (Stream<Path> files = Files.list(jobDir)) {
                Path file = files.filter(Files::isRegularFile)
                        .filter(p -> !p.toString().endsWith(".part"))
                        .max(Comparator.comparingLong(p -> p.toFile().length()))
                        .orElseThrow(() -> new IOException("No output video file was created. Check FFmpeg installation."));
                job.setFile(file);
                job.setFilename(file.getFileName().toString());
            }

            job.setPercent(100.0);
            job.setState("finished");
            job.setMessage("Your video is ready.");
        } catch (Exception ex) {
            job.setState("error");
            job.setMessage(safeMessage(ex.getMessage()));
        } finally {
            job.setProcess(null);
        }
    }

    private void addCookieArgs(List<String> command, String browser) {
        if (hasCookieFile()) {
            command.add("--cookies");
            command.add(cookieFilePath.toString());
        } else {
            String b = browser != null && SUPPORTED_BROWSERS.contains(browser) ? browser : "chrome";
            command.add("--cookies-from-browser");
            command.add(b);
        }
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "The operation failed. Check the URL and try again.";
        }
        return message.length() > 450 ? message.substring(0, 450) : message;
    }

    @PreDestroy
    public void cleanup() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
