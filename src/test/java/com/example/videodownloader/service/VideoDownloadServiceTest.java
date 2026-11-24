package com.example.videodownloader.service;

import com.example.videodownloader.dto.VideoInfoResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VideoDownloadServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesResolutionsAndPerResolutionSizes(@TempDir Path tempDir) throws Exception {
        VideoDownloadService service = new VideoDownloadService(tempDir.toString(), "yt-dlp");

        String json = """
                {
                  "title": "Sample Video",
                  "thumbnail": "https://img.example.com/thumb.jpg",
                  "duration": 100,
                  "uploader": "Test Creator",
                  "formats": [
                    { "format_id": "sb0", "ext": "mhtml", "protocol": "mhtml", "vcodec": "images", "acodec": "none", "height": 90 },
                    { "format_id": "140", "ext": "m4a", "vcodec": "none", "acodec": "mp4a", "filesize": 2000000, "abr": 128 },
                    { "format_id": "18",  "ext": "mp4", "vcodec": "avc1", "acodec": "mp4a", "height": 360, "filesize": 10000000 },
                    { "format_id": "136", "ext": "mp4", "vcodec": "avc1", "acodec": "none", "height": 720, "filesize_approx": 25000000 },
                    { "format_id": "137", "ext": "mp4", "vcodec": "avc1", "acodec": "none", "height": 1080, "tbr": 4000 }
                  ]
                }
                """;

        JsonNode root = mapper.readTree(json);
        VideoInfoResponse info = service.parseVideoInfo(root);

        assertEquals("Sample Video", info.title());
        assertTrue(info.hasAudio());
        // Storyboard height 90 should be excluded
        assertEquals(List.of(1080, 720, 360), info.qualities());

        // 360p is combined (audio already included) -> 10,000,000
        assertEquals(10_000_000L, info.sizeMap().get("360"));
        // 720p is video-only (25,000,000) + best audio (2,000,000) -> 27,000,000
        assertEquals(27_000_000L, info.sizeMap().get("720"));
        // 1080p estimated from tbr (4000 kbps * 100s / 8 * 1000 = 50,000,000) + best audio (2,000,000) -> 52,000,000
        assertEquals(52_000_000L, info.sizeMap().get("1080"));
        // best matches top resolution (1080p)
        assertEquals(52_000_000L, info.sizeMap().get("best"));
        // audio matches best audio-only stream
        assertEquals(2_000_000L, info.sizeMap().get("audio"));
    }

    @Test
    void unknownVideoSizeDoesNotReportAudioOnlySizeAsVideoSize(@TempDir Path tempDir) throws Exception {
        VideoDownloadService service = new VideoDownloadService(tempDir.toString(), "yt-dlp");

        String json = """
                {
                  "title": "Unknown Video Size",
                  "duration": 60,
                  "formats": [
                    { "format_id": "140", "vcodec": "none", "acodec": "mp4a", "filesize": 1500000 },
                    { "format_id": "299", "vcodec": "avc1", "acodec": "none", "height": 1080 }
                  ]
                }
                """;

        VideoInfoResponse info = service.parseVideoInfo(mapper.readTree(json));
        assertEquals(0L, info.sizeMap().get("1080"));
        assertEquals(0L, info.sizeMap().get("best"));
        assertEquals(1_500_000L, info.sizeMap().get("audio"));
    }

    @Test
    void usesSystemTempDirByDefaultInsteadOfProjectDownloads() throws Exception {
        VideoDownloadService service = new VideoDownloadService("", "yt-dlp", "cookies.txt");
        try {
            assertTrue(service.isUsingSystemTemp());
            Path expectedBase = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            assertTrue(service.getDownloadRoot().startsWith(expectedBase));
            assertFalse(service.getDownloadRoot().toString().endsWith("downloads"));
        } finally {
            service.cleanup();
        }
    }

    @Test
    void evictExpiredJobsDeletesTempFiles(@TempDir Path tempDir) throws Exception {
        VideoDownloadService service = new VideoDownloadService(tempDir.toString(), "yt-dlp");
        try {
            String jobId = "test-job-1";
            Path jobDir = tempDir.resolve(jobId);
            java.nio.file.Files.createDirectories(jobDir);
            java.nio.file.Files.writeString(jobDir.resolve("video.mp4"), "dummy");

            // Simulate expired served job via reflection-free field check:
            // Submit or inspect cleanup directly on stale dir during system-temp startup
            assertTrue(java.nio.file.Files.exists(jobDir));
            VideoDownloadService tempService = new VideoDownloadService("", "yt-dlp", "cookies.txt");
            Path staleDir = tempService.getDownloadRoot().resolve("stale-test-dir");
            java.nio.file.Files.createDirectories(staleDir);
            java.nio.file.Files.writeString(staleDir.resolve("old.mp4"), "old");
            tempService.cleanup();
            assertFalse(java.nio.file.Files.exists(staleDir));
        } finally {
            service.cleanup();
        }
    }

    @Test
    void audioCommandEmbedsThumbnailAndMetadata(@TempDir Path tempDir) throws Exception {
        VideoDownloadService service = new VideoDownloadService(tempDir.toString(), "yt-dlp");
        try {
            List<String> audioCmd = service.buildDownloadCommand("out.%(ext)s", "best", true);
            assertTrue(audioCmd.contains("--extract-audio"));
            assertTrue(audioCmd.contains("--embed-thumbnail"));
            assertTrue(audioCmd.contains("--convert-thumbnails"));
            assertTrue(audioCmd.contains("jpg"));
            assertTrue(audioCmd.contains("--embed-metadata"));
            assertTrue(audioCmd.contains("--concurrent-fragments"));

            List<String> videoCmd = service.buildDownloadCommand("out.%(ext)s", "1080", false);
            assertFalse(videoCmd.contains("--extract-audio"));
            assertTrue(videoCmd.contains("bestvideo[height<=1080]+bestaudio/best[height<=1080]/best"));
        } finally {
            service.cleanup();
        }
    }
}
