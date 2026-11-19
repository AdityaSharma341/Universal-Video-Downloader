package com.example.videodownloader.dto;

public record UrlRequest(
        String url,
        boolean useCookies,
        String browser
) {}
