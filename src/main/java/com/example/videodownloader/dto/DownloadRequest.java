package com.example.videodownloader.dto;

public record DownloadRequest(
        String url,
        String quality,
        boolean useCookies,
        String browser
) {}
