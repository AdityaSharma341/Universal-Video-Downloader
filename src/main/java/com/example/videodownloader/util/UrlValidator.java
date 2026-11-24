package com.example.videodownloader.util;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

public final class UrlValidator {

    public static final int MAX_URL_LENGTH = 4096;
    private static final int MAX_RESOLUTION_HEIGHT = 16384;
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<String> ALLOWED_MODES = Set.of("video", "audio");
    private static final Set<String> SUPPORTED_BROWSERS = Set.of("chrome", "firefox", "edge", "brave", "opera", "vivaldi");

    private UrlValidator() {}

    public static String validateAndNormalizeUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid webpage URL.");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL exceeds maximum permitted length of " + MAX_URL_LENGTH + " characters.");
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT)) || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only valid http:// and https:// URLs are accepted.");
        }
    }

    /**
     * Validates dynamic resolution height (any positive integer up to 16384) or "best".
     */
    public static String validateQuality(String quality) {
        if (quality == null || quality.isBlank()) {
            return "best";
        }
        String normalized = quality.trim().toLowerCase(Locale.ROOT);
        if ("best".equals(normalized)) {
            return "best";
        }
        if (normalized.matches("\\d{1,5}")) {
            int height = Integer.parseInt(normalized);
            if (height >= 1 && height <= MAX_RESOLUTION_HEIGHT) {
                return normalized;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported quality selection: " + quality);
    }

    public static String validateMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "video";
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_MODES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported download mode: " + mode);
        }
        return normalized;
    }

    public static String sanitizeBrowser(String browser) {
        if (browser == null || browser.isBlank()) {
            return "chrome";
        }
        String b = browser.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_BROWSERS.contains(b) ? b : "chrome";
    }
}
