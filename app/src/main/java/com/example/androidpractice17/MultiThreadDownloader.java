package com.example.androidpractice17;

import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MultiThreadDownloader {

    private String url;
    private String saveDir;
    private String fileName;
    private int threadCount;
    private long fileSize;
    private long totalDownloaded;  // 已下载字节总数，volatile保证可见性，配合synchronized读写
    private volatile boolean isPaused = false;
    private volatile boolean isCanceled = false;

    private ExecutorService executor;
    private List<DownloadThread> downloadThreads;
    private Handler mainHandler;
    private TaskInfo taskInfo;

    private long lastUpdateTime;
    private long lastDownloadedBytes;
    private static final long UPDATE_INTERVAL = 1000; // 1秒

    private OnDownloadListener listener;

    public interface OnDownloadListener {
        void onProgress(int percent, String fileSize, String downloaded, String speed, String remainingTime);

        void onStatusChanged(String status);

        void onDownloadComplete();

        void onError(String error);
    }

    public MultiThreadDownloader(String url, int threadCount, OnDownloadListener listener) {
        this.url = url;
        this.threadCount = threadCount;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
        // 使用应用私有下载目录，无需存储权限
        this.saveDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
    }

    /**
     * 从URL提取文件名
     */
    private String extractFileName(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    /**
     * 开始下载
     */
    public void start() {
        if (downloadThreads != null && !downloadThreads.isEmpty()) return;
        isCanceled = false;
        isPaused = false;
        new Thread(() -> {
            try {
                initDownload();
                loadOrCreateTaskInfo();
                lastUpdateTime = System.currentTimeMillis();
                lastDownloadedBytes = totalDownloaded;
                startDownloadThreads();
                startProgressUpdate();
            } catch (IOException e) {
                mainHandler.post(() -> listener.onError("下载初始化失败：" + e.getMessage()));
            }
        }).start();
    }

    private void initDownload() throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.connect();
            if (conn.getResponseCode() == 200) {
                fileSize = conn.getContentLengthLong();
                if (fileSize <= 0) throw new IOException("无法获取文件大小");
                fileName = extractFileName(url);
                // 预先创建空文件并设置大小
                File file = new File(saveDir, fileName);
                RandomAccessFile raf = new RandomAccessFile(file, "rw");
                raf.setLength(fileSize);
                raf.close();
            } else {
                throw new IOException("服务器响应码：" + conn.getResponseCode());
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 从零创建任务信息（无断点）
     */
    private void buildTaskInfoFromScratch() {
        taskInfo = new TaskInfo();
        taskInfo.setUrl(url);
        taskInfo.setFilePath(new File(saveDir, fileName).getAbsolutePath());
        taskInfo.setFileSize(fileSize);
        taskInfo.setThreadCount(threadCount);
        List<DownloadThreadInfo> list = new ArrayList<>();
        long blockSize = fileSize / threadCount;
        for (int i = 0; i < threadCount; i++) {
            long start = i * blockSize;
            long end = (i == threadCount - 1) ? fileSize - 1 : (i + 1) * blockSize - 1;
            list.add(new DownloadThreadInfo(i, start, end, start));
        }
        taskInfo.setThreadInfoList(list);
        totalDownloaded = 0;
    }

    /**
     * 加载断点信息或新建
     */
    private void loadOrCreateTaskInfo() {
        File infoFile = new File(saveDir, fileName + ".download_info");
        if (infoFile.exists()) {
            try (FileReader reader = new FileReader(infoFile)) {
                Gson gson = new Gson();
                taskInfo = gson.fromJson(reader, TaskInfo.class);
                // 重新计算已下载总量
                totalDownloaded = 0;
                for (DownloadThreadInfo ti : taskInfo.getThreadInfoList()) {
                    totalDownloaded += (ti.getCurrentPos() - ti.getStartPos());
                }
            } catch (IOException e) {
                buildTaskInfoFromScratch();
            }
        } else {
            buildTaskInfoFromScratch();
        }
        // 更新UI状态
        mainHandler.post(() -> listener.onStatusChanged("下载中"));
    }

    /**
     * 启动所有下载线程
     */
    private void startDownloadThreads() {
        executor = Executors.newFixedThreadPool(threadCount);
        downloadThreads = new ArrayList<>();
        for (DownloadThreadInfo info : taskInfo.getThreadInfoList()) {
            DownloadThread dt = new DownloadThread(info);
            downloadThreads.add(dt);
            executor.execute(dt);
        }
    }

    /**
     * 保存断点信息到文件
     */
    private synchronized void saveTaskInfo() {
        try {
            Gson gson = new Gson();
            String json = gson.toJson(taskInfo);
            File infoFile = new File(saveDir, fileName + ".download_info");
            FileWriter writer = new FileWriter(infoFile);
            writer.write(json);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 启动进度更新定时器
     */
    private void startProgressUpdate() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isCanceled) return;
                long now = System.currentTimeMillis();
                long interval = now - lastUpdateTime;
                // 每秒更新一次界面
                if (interval >= UPDATE_INTERVAL) {
                    long downloaded = totalDownloaded; // 一次读取，减少同步影响
                    long delta = downloaded - lastDownloadedBytes;
                    double speed = delta * 1000.0 / interval; // 字节/秒

                    int percent = (int) (downloaded * 100 / fileSize);
                    String speedStr = formatSpeed(speed);
                    String fileSizeStr = formatSize(fileSize);
                    String downloadedStr = formatSize(downloaded);
                    String remainingTimeStr;
                    if (speed > 0) {
                        long remainingBytes = fileSize - downloaded;
                        long sec = (long) (remainingBytes / speed);
                        remainingTimeStr = formatTime(sec);
                    } else {
                        remainingTimeStr = "计算中…";
                    }

                    listener.onProgress(percent, fileSizeStr, downloadedStr, speedStr, remainingTimeStr);

                    lastDownloadedBytes = downloaded;
                    lastUpdateTime = now;
                }

                if (!isPaused && !isCanceled && totalDownloaded < fileSize) {
                    // 继续定时
                    mainHandler.postDelayed(this, 500);
                } else if (totalDownloaded >= fileSize) {
                    // 下载完成
                    listener.onStatusChanged("下载完成");
                    listener.onDownloadComplete();
                    cleanup(true);
                }
            }
        });
    }

    /**
     * 暂停下载
     */
    public void pause() {
        isPaused = true;
        if (executor != null) {
            executor.shutdownNow(); // 中断当前线程，run方法中会检测isPaused并保存进度
        }
        // 保存一次进度
        saveTaskInfo();
        mainHandler.post(() -> {
            listener.onStatusChanged("已暂停");
            listener.onProgress((int)(totalDownloaded * 100 / fileSize),
                    formatSize(fileSize), formatSize(totalDownloaded), "0 KB/s", "--");
        });
    }

    /**
     * 继续下载
     */
    public void resume() {
        isPaused = false;
        if (totalDownloaded >= fileSize) return;
        // 重新创建线程池，提交未完成的任务
        executor = Executors.newFixedThreadPool(threadCount);
        downloadThreads = new ArrayList<>();
        for (DownloadThreadInfo info : taskInfo.getThreadInfoList()) {
            if (info.getCurrentPos() <= info.getEndPos()) {
                DownloadThread dt = new DownloadThread(info);
                downloadThreads.add(dt);
                executor.execute(dt);
            }
        }
        lastUpdateTime = System.currentTimeMillis();
        lastDownloadedBytes = totalDownloaded;
        startProgressUpdate();
        mainHandler.post(() -> listener.onStatusChanged("下载中"));
    }

    /**
     * 取消下载
     */
    public void cancel() {
        isCanceled = true;
        if (executor != null) {
            executor.shutdownNow();
        }
        // 删除文件及断点信息
        new File(saveDir, fileName).delete();
        new File(saveDir, fileName + ".download_info").delete();
        mainHandler.post(() -> listener.onStatusChanged("已取消"));
        cleanup(false);
    }

    /**
     * 清理资源
     */
    private void cleanup(boolean success) {
        if (executor != null) executor.shutdown();
    }

    // ---------- 格式工具方法 ----------
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        else if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        else if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        else return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String formatSpeed(double bytesPerSec) {
        if (bytesPerSec < 1024) return String.format("%.1f B/s", bytesPerSec);
        else if (bytesPerSec < 1024 * 1024) return String.format("%.1f KB/s", bytesPerSec / 1024);
        else if (bytesPerSec < 1024 * 1024 * 1024) return String.format("%.1f MB/s", bytesPerSec / (1024 * 1024));
        else return String.format("%.1f GB/s", bytesPerSec / (1024 * 1024 * 1024));
    }

    private String formatTime(long seconds) {
        if (seconds < 0) return "--";
        if (seconds < 60) return seconds + "秒";
        long min = seconds / 60;
        long sec = seconds % 60;
        if (min < 60) return min + "分" + sec + "秒";
        long hour = min / 60;
        min = min % 60;
        return hour + "时" + min + "分";
    }

    // ---------- 内部下载线程类 ----------
    private class DownloadThread implements Runnable {
        private DownloadThreadInfo threadInfo;

        public DownloadThread(DownloadThreadInfo threadInfo) {
            this.threadInfo = threadInfo;
        }

        @Override
        public void run() {
            RandomAccessFile raf = null;
            HttpURLConnection conn = null;
            try {
                raf = new RandomAccessFile(new File(saveDir, fileName), "rw");
                long startPos = threadInfo.getCurrentPos();
                long endPos = threadInfo.getEndPos();

                if (startPos > endPos) {
                    // 本段已下载完成
                    return;
                }

                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Range", "bytes=" + startPos + "-" + endPos);
                conn.connect();

                InputStream is = conn.getInputStream();
                byte[] buffer = new byte[4096];
                int len;
                raf.seek(startPos);
                while ((len = is.read(buffer)) != -1) {
                    if (isCanceled) {
                        conn.disconnect();
                        return;
                    }
                    if (isPaused) {
                        // 保存当前进度并退出
                        threadInfo.setCurrentPos(startPos);
                        saveTaskInfo();
                        conn.disconnect();
                        return;
                    }
                    raf.write(buffer, 0, len);
                    startPos += len;
                    threadInfo.setCurrentPos(startPos);

                    // 同步更新总下载量
                    synchronized (MultiThreadDownloader.this) {
                        totalDownloaded += len;
                    }
                }
                is.close();
                // 下载完成后，确保currentPos等于endPos+1
                threadInfo.setCurrentPos(endPos + 1);
                saveTaskInfo(); // 完成一段也可保存
            } catch (IOException e) {
                e.printStackTrace();
                // 可以重试或通知错误
            } finally {
                try { if (raf != null) raf.close(); } catch (IOException ignored) {}
                if (conn != null) conn.disconnect();
            }
        }
    }
}