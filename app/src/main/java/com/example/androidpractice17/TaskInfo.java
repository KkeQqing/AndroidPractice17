package com.example.androidpractice17;

import java.util.List;

public class TaskInfo {
    private String url;
    private String filePath;
    private long fileSize;
    private int threadCount;
    private List<DownloadThreadInfo> threadInfoList;

    public TaskInfo() {
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public int getThreadCount() {
        return threadCount;
    }

    public void setThreadCount(int threadCount) {
        this.threadCount = threadCount;
    }

    public List<DownloadThreadInfo> getThreadInfoList() {
        return threadInfoList;
    }

    public void setThreadInfoList(List<DownloadThreadInfo> threadInfoList) {
        this.threadInfoList = threadInfoList;
    }
}