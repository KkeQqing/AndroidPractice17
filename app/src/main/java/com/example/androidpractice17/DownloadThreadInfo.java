package com.example.androidpractice17;

public class DownloadThreadInfo {
    private int threadId;    // 线程编号
    private long startPos;   // 本段起始字节
    private long endPos;     // 本段结束字节(含)
    private long currentPos; // 当前已下载到的位置

    public DownloadThreadInfo() {
    }

    public DownloadThreadInfo(int threadId, long startPos, long endPos, long currentPos) {
        this.threadId = threadId;
        this.startPos = startPos;
        this.endPos = endPos;
        this.currentPos = currentPos;
    }

    public int getThreadId() {
        return threadId;
    }

    public void setThreadId(int threadId) {
        this.threadId = threadId;
    }

    public long getStartPos() {
        return startPos;
    }

    public void setStartPos(long startPos) {
        this.startPos = startPos;
    }

    public long getEndPos() {
        return endPos;
    }

    public void setEndPos(long endPos) {
        this.endPos = endPos;
    }

    public long getCurrentPos() {
        return currentPos;
    }

    public void setCurrentPos(long currentPos) {
        this.currentPos = currentPos;
    }
}