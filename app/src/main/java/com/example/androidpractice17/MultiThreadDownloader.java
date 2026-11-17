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
    private long totalDownloaded;
    private volatile boolean isPaused = false;
    private volatile boolean isCanceled = false;

    private ExecutorService executor;
    private List<DownloadThread> downloadThreads;
    private Handler mainHandler;
    private TaskInfo taskInfo;

    private long lastUpdateTime;
    private long lastDownloadedBytes;
    private static final long UPDATE_INTERVAL = 1000;

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
        this.saveDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
    }

    private String extractFileName(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

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
                mainHandler.post(() -> listener.onError("初始化失败：" + e.getMessage()));
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
                File file = new File(saveDir, fileName);
                RandomAccessFile raf = new RandomAccessFile(file, "rw");
                raf.setLength(fileSize);
                raf.close();
            } else {
                throw new IOException("服务器响应：" + conn.getResponseCode());
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

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

    private void loadOrCreateTaskInfo() {
        File infoFile = new File(saveDir, fileName + ".download_info");
        if (infoFile.exists()) {
            try (FileReader reader = new FileReader(infoFile)) {
                Gson gson = new Gson();
                taskInfo = gson.fromJson(reader, TaskInfo.class);
                totalDownloaded = 0;
                for (DownloadThreadInfo ti : taskInfo.getThreadInfoList()) {
                    totalDownloaded += (ti.getCurrentPos() - ti.getStartPos());
                }
            } catch (Exception e) {
                buildTaskInfoFromScratch();
            }
        } else {
            buildTaskInfoFromScratch();
        }
        mainHandler.post(() -> listener.onStatusChanged("下载中"));
    }

    private void startDownloadThreads() {
        executor = Executors.newFixedThreadPool(threadCount);
        downloadThreads = new ArrayList<>();
        for (DownloadThreadInfo info : taskInfo.getThreadInfoList()) {
            DownloadThread dt = new DownloadThread(info);
            downloadThreads.add(dt);
            executor.execute(dt);
        }
    }

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

    private void startProgressUpdate() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isCanceled) return;
                long now = System.currentTimeMillis();
                long interval = now - lastUpdateTime;

                if (interval >= UPDATE_INTERVAL) {
                    long downloaded = totalDownloaded;
                    long delta = downloaded - lastDownloadedBytes;
                    double speed = delta * 1000.0 / interval;

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
                    mainHandler.postDelayed(this, 500);
                } else if (totalDownloaded >= fileSize) {
                    listener.onStatusChanged("下载完成");
                    listener.onDownloadComplete();
                    cleanup(true);
                }
            }
        });
    }

    public void pause() {
        isPaused = true;
        if (executor != null) executor.shutdownNow();
        saveTaskInfo();
        mainHandler.post(() -> {
            listener.onStatusChanged("已暂停");
            if(listener instanceof MainActivity){
                ((MainActivity) listener).updateButtonsOnPause();
            }
        });
    }

    public void resume() {
        isPaused = false;
        if (totalDownloaded >= fileSize) return;
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
        mainHandler.post(() -> {
            listener.onStatusChanged("下载中");
            if(listener instanceof MainActivity){
                ((MainActivity) listener).updateButtonsOnResume();
            }
        });
    }

    public void cancel() {
        isCanceled = true;
        if (executor != null) executor.shutdownNow();
        new File(saveDir, fileName).delete();
        new File(saveDir, fileName + ".download_info").delete();
        mainHandler.post(() -> listener.onStatusChanged("已取消"));
        cleanup(false);
    }

    private void cleanup(boolean success) {
        if (executor != null) executor.shutdown();
        mainHandler.post(()->{
            if(listener instanceof MainActivity){
                ((MainActivity) listener).updateButtonsOnCancelOrComplete();
            }
        });
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        else if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        else if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        else return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String formatSpeed(double bytesPerSec) {
        if (bytesPerSec < 1024) return String.format("%.1f B/s", bytesPerSec);
        else if (bytesPerSec < 1024 * 1024) return String.format("%.1f KB/s", bytesPerSec / 1024);
        else if (bytesPerSec < 1024L * 1024 * 1024) return String.format("%.1f MB/s", bytesPerSec / (1024 * 1024));
        else return String.format("%.1f GB/s", bytesPerSec / (1024L * 1024 * 1024));
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

                if (startPos > endPos) return;

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
                    if (isCanceled || isPaused) break;
                    raf.write(buffer, 0, len);
                    startPos += len;
                    threadInfo.setCurrentPos(startPos);
                    synchronized (MultiThreadDownloader.this) {
                        totalDownloaded += len;
                    }
                }

                is.close();
                threadInfo.setCurrentPos(endPos + 1);
                saveTaskInfo();

            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(()-> listener.onError("下载异常："+e.getMessage()));
            } finally {
                try { if (raf != null) raf.close(); } catch (IOException ignored) {}
                if (conn != null) conn.disconnect();
            }
        }
    }
}