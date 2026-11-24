package com.example.videodownloader.dto;

public record DownloadRequest(
        String url,
        String quality,
        String mode,
        String browser
) {}
