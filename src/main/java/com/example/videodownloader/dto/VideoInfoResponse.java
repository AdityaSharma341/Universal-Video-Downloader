package com.example.videodownloader.dto;

import java.util.List;

public record VideoInfoResponse(
        String title,
        String thumbnail,
        int duration,
        String uploader,
        List<Integer> qualities
) {}
