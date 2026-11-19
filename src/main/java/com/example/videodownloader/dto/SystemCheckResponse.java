package com.example.videodownloader.dto;

public record SystemCheckResponse(
        boolean healthy,
        ToolStatus ytdlp,
        ToolStatus ffmpeg,
        DiskStatus disk
) {
    public record ToolStatus(
            boolean available,
            String version,
            String path,
            String error
    ) {}

    public record DiskStatus(
            long freeBytes,
            long totalBytes,
            String downloadDir
    ) {}
}
