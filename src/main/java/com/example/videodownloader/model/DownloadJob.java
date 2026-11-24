package com.example.videodownloader.model;

import java.nio.file.Path;

public class DownloadJob {
    private final String id;
    private final long createdAt = System.currentTimeMillis();
    private volatile String state = "queued";
    private volatile double percent = 0;
    private volatile String message = "Waiting to start...";
    private volatile String filename;
    private volatile Path file;
    private volatile Process process;
    private volatile long downloadedAt = 0L;

    public DownloadJob(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public double getPercent() {
        return percent;
    }

    public void setPercent(double percent) {
        this.percent = percent;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public Path getFile() {
        return file;
    }

    public void setFile(Path file) {
        this.file = file;
    }

    public Process getProcess() {
        return process;
    }

    public void setProcess(Process process) {
        this.process = process;
    }

    public long getDownloadedAt() {
        return downloadedAt;
    }

    public void markDownloaded() {
        this.downloadedAt = System.currentTimeMillis();
    }
}
