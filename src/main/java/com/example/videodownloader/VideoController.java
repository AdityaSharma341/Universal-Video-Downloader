package com.example.videodownloader;

import com.example.videodownloader.dto.*;
import com.example.videodownloader.model.DownloadJob;
import com.example.videodownloader.service.VideoDownloadService;
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

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api")
public class VideoController {

    private final VideoDownloadService downloadService;
    private final String accessToken;

    public VideoController(
            VideoDownloadService downloadService,
            @Value("${app.access-token:}") String accessToken) {
        this.downloadService = downloadService;
        this.accessToken = accessToken == null ? "" : accessToken;
    }

    @GetMapping("/config")
    public ConfigResponse config() {
        return new ConfigResponse(
                !accessToken.isBlank(),
                downloadService.hasCookieFile(),
                downloadService.getCookieFilePath().toString()
        );
    }

    @PostMapping("/info")
    public Map<String, Object> info(@RequestBody UrlRequest request, HttpServletRequest http) throws Exception {
        authorize(http.getHeader("X-App-Token"));
        String url = validateUrl(request.url());
        return downloadService.fetchVideoInfo(url, request.useCookies(), request.browser());
    }

    @PostMapping("/download")
    public Map<String, String> start(@RequestBody DownloadRequest request, HttpServletRequest http) {
        authorize(http.getHeader("X-App-Token"));
        String url = validateUrl(request.url());
        String quality = request.quality() == null ? "best" : request.quality();
        if (!Set.of("best", "360", "480", "720", "1080", "1440", "2160").contains(quality)) {
            throw new ResponseStatusException(BAD_REQUEST, "Unsupported quality selection.");
        }
        String id = downloadService.submitDownloadJob(url, quality, request.useCookies(), request.browser());
        return Map.of("jobId", id);
    }

    @GetMapping("/status/{id}")
    public JobStatusResponse status(@PathVariable String id, HttpServletRequest http) {
        authorize(http.getHeader("X-App-Token"));
        DownloadJob job = getJob(id);
        return new JobStatusResponse(job.getState(), job.getPercent(), job.getMessage(), job.getFilename());
    }

    @GetMapping("/file/{id}")
    public ResponseEntity<Resource> file(@PathVariable String id, @RequestParam(required = false) String token) throws IOException {
        authorize(token);
        DownloadJob job = getJob(id);
        if (!"finished".equals(job.getState()) || job.getFile() == null || !Files.isRegularFile(job.getFile())) {
            throw new ResponseStatusException(NOT_FOUND, "The video is not ready.");
        }
        Resource resource = new FileSystemResource(job.getFile());
        String filename = job.getFile().getFileName().toString();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(job.getFile()))
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
            new ProcessBuilder("cmd", "/c", "start", exe, url.replace("&", "^&")).start();
        } catch (IOException e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Could not open browser.");
        }
        return Map.of("status", "opened", "message", "Complete any prompts in the browser, then return here.");
    }

    @PostMapping("/upload-cookies")
    public Map<String, Object> uploadCookies(@RequestParam("file") MultipartFile file, HttpServletRequest http) {
        authorize(http.getHeader("X-App-Token"));
        if (file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "No file uploaded.");
        }
        try {
            byte[] content = file.getBytes();
            String text = new String(content, StandardCharsets.UTF_8);
            if (!text.contains("# Netscape HTTP Cookie File") && !text.contains("\t")) {
                throw new ResponseStatusException(BAD_REQUEST,
                        "This doesn't look like a valid cookies.txt file. Export it using the 'Get cookies.txt LOCALLY' extension.");
            }
            downloadService.saveCookies(content);
            return Map.of(
                    "status", "saved",
                    "path", downloadService.getCookieFilePath().toString(),
                    "message", "Cookie file saved! You can now detect and download the video."
            );
        } catch (IOException e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Could not save cookie file.");
        }
    }

    @DeleteMapping("/cookies")
    public Map<String, String> deleteCookies(HttpServletRequest http) {
        authorize(http.getHeader("X-App-Token"));
        downloadService.deleteCookies();
        return Map.of("status", "deleted", "message", "Cookie file removed.");
    }

    private DownloadJob getJob(String id) {
        return downloadService.findJob(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Download job not found."));
    }

    private String validateUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 4096) {
            throw new ResponseStatusException(BAD_REQUEST, "Enter a valid webpage URL.");
        }
        try {
            URI uri = URI.create(value.trim());
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (Exception ex) {
            throw new ResponseStatusException(BAD_REQUEST, "Only valid http:// and https:// URLs are accepted.");
        }
    }

    private void authorize(String supplied) {
        if (!accessToken.isBlank() && !accessToken.equals(supplied)) {
            throw new ResponseStatusException(UNAUTHORIZED, "Invalid or missing access token.");
        }
    }
}
