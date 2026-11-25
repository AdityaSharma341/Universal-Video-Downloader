package com.example.videodownloader;

import com.example.videodownloader.dto.*;
import com.example.videodownloader.model.DownloadJob;
import com.example.videodownloader.service.VideoDownloadService;
import com.example.videodownloader.util.UrlValidator;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
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

    /**
     * Lightweight public endpoint for uptime monitors (supports GET and HEAD).
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "timestamp", Instant.now().toString()
        );
    }

    @GetMapping("/config")
    public ConfigResponse config() {
        return new ConfigResponse(
                !accessToken.isBlank(),
                downloadService.hasCookieFile(),
                downloadService.getCookieFilePath().toString()
        );
    }

    @GetMapping("/system-check")
    public SystemCheckResponse systemCheck(HttpServletRequest http) {
        authorize(http.getHeader("X-App-Token"));
        return downloadService.checkDiagnostics();
    }

    /**
     * Detect video info including available resolutions and estimated sizes.
     * Cookies are handled automatically.
     */
    @PostMapping("/info")
    public VideoInfoResponse info(@RequestBody UrlRequest request, HttpServletRequest http) throws Exception {
        authorize(http.getHeader("X-App-Token"));
        String url = UrlValidator.validateAndNormalizeUrl(request.url());
        String browser = UrlValidator.sanitizeBrowser(request.browser());
        return downloadService.fetchVideoInfo(url, browser);
    }

    /**
     * Start download. Mode can be "video" (default) or "audio".
     * Quality is a resolution height from the detected qualities, or "best".
     * Cookies are handled automatically.
     */
    @PostMapping("/download")
    public Map<String, String> start(@RequestBody DownloadRequest request, HttpServletRequest http) {
        authorize(http.getHeader("X-App-Token"));
        String url = UrlValidator.validateAndNormalizeUrl(request.url());
        String mode = UrlValidator.validateMode(request.mode());
        String quality = "audio".equals(mode) ? "best" : UrlValidator.validateQuality(request.quality());
        String browser = UrlValidator.sanitizeBrowser(request.browser());

        String id = downloadService.submitDownloadJob(url, quality, mode, browser);
        return Map.of("jobId", id);
    }

    @DeleteMapping("/download/{id}")
    public Map<String, Object> cancel(@PathVariable String id, HttpServletRequest http) {
        authorize(http.getHeader("X-App-Token"));
        boolean cancelled = downloadService.cancelJob(id);
        if (!cancelled) {
            throw new ResponseStatusException(BAD_REQUEST, "Job cannot be cancelled or was not found.");
        }
        return Map.of("status", "cancelled", "message", "Download cancelled successfully.");
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
            throw new ResponseStatusException(NOT_FOUND, "The file is not ready.");
        }
        Resource resource = new FileSystemResource(job.getFile());
        String filename = job.getFile().getFileName().toString();
        long size = Files.size(job.getFile());
        downloadService.markJobServed(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(size)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(resource);
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

    private void authorize(String supplied) {
        if (!accessToken.isBlank() && !accessToken.equals(supplied)) {
            throw new ResponseStatusException(UNAUTHORIZED, "Invalid or missing access token.");
        }
    }
}
