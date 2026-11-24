package com.example.videodownloader.dto;

import java.util.List;
import java.util.Map;

public record VideoInfoResponse(
        String title,
        String thumbnail,
        int duration,
        String uploader,
        List<Integer> qualities,
        boolean hasAudio,
        Map<String, Long> sizeMap
) {}
