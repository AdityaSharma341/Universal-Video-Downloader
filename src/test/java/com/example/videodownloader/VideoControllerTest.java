package com.example.videodownloader;

import com.example.videodownloader.service.VideoDownloadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(VideoController.class)
class VideoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VideoDownloadService downloadService;

    @Test
    void healthEndpointReturnsUpForGetAndHead() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        mockMvc.perform(head("/api/health"))
                .andExpect(status().isOk());
    }

    @Test
    void configEndpointReturnsDefaults() throws Exception {
        when(downloadService.hasCookieFile()).thenReturn(false);
        when(downloadService.getCookieFilePath()).thenReturn(Path.of("cookies.txt"));

        mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenRequired").value(false))
                .andExpect(jsonPath("$.hasCookieFile").value(false));
    }

    @Test
    void startDownloadAcceptsDynamicResolutionAndAudioMode() throws Exception {
        when(downloadService.submitDownloadJob(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("job-123");

        mockMvc.perform(post("/api/download")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://example.com/video",
                                  "quality": "1440",
                                  "mode": "video",
                                  "browser": "firefox"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-123"));

        mockMvc.perform(post("/api/download")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://example.com/video",
                                  "quality": "best",
                                  "mode": "audio",
                                  "browser": "chrome"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-123"));
    }

    @Test
    void startDownloadRejectsInvalidQuality() throws Exception {
        mockMvc.perform(post("/api/download")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://example.com/video",
                                  "quality": "720]+evil",
                                  "mode": "video"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingJobReturnsNotFound() throws Exception {
        when(downloadService.findJob("unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/status/unknown"))
                .andExpect(status().isNotFound());
    }
}
