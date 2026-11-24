package com.example.videodownloader.util;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class UrlValidatorTest {

    @Test
    void acceptsValidHttpAndHttpsUrls() {
        assertEquals("https://example.com/watch?v=123",
                UrlValidator.validateAndNormalizeUrl("  https://example.com/watch?v=123  "));
        assertEquals("http://example.org/video",
                UrlValidator.validateAndNormalizeUrl("http://example.org/video"));
    }

    @Test
    void rejectsInvalidUrls() {
        assertThrows(ResponseStatusException.class, () -> UrlValidator.validateAndNormalizeUrl(null));
        assertThrows(ResponseStatusException.class, () -> UrlValidator.validateAndNormalizeUrl("   "));
        assertThrows(ResponseStatusException.class, () -> UrlValidator.validateAndNormalizeUrl("ftp://example.com/file"));
        assertThrows(ResponseStatusException.class, () -> UrlValidator.validateAndNormalizeUrl("javascript:alert(1)"));
    }

    @Test
    void validatesDynamicResolutionsAndBest() {
        assertEquals("best", UrlValidator.validateQuality(null));
        assertEquals("best", UrlValidator.validateQuality("BEST"));
        assertEquals("144", UrlValidator.validateQuality("144"));
        assertEquals("1080", UrlValidator.validateQuality("1080"));
        assertEquals("2160", UrlValidator.validateQuality("2160"));
        assertEquals("4320", UrlValidator.validateQuality("4320"));

        assertThrows(ResponseStatusException.class, () -> UrlValidator.validateQuality("720]+bestaudio"));
        assertThrows(ResponseStatusException.class, () -> UrlValidator.validateQuality("0"));
        assertThrows(ResponseStatusException.class, () -> UrlValidator.validateQuality("invalid"));
    }

    @Test
    void validatesDownloadModes() {
        assertEquals("video", UrlValidator.validateMode(null));
        assertEquals("video", UrlValidator.validateMode("VIDEO"));
        assertEquals("audio", UrlValidator.validateMode("audio"));
        assertThrows(ResponseStatusException.class, () -> UrlValidator.validateMode("image"));
    }
}
