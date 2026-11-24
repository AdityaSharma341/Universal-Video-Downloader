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
}
