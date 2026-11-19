package com.example.videodownloader.dto;

public record ConfigResponse(
        boolean tokenRequired,
        boolean hasCookieFile,
        String cookieFilePath
) {}
