package com.example.videodownloader.dto;

public record JobStatusResponse(
        String state,
        double percent,
        String message,
        String filename
) {}
