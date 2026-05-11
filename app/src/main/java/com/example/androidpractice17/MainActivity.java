package com.example.androidpractice17;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private EditText etUrl;
    private EditText etThreadCount;
    private Button btnStart, btnPause, btnResume, btnCancel;
    private ProgressBar pbDownload;
    private TextView tvPercent, tvFileSize, tvDownloaded, tvSpeed, tvRemaining, tvStatus;

    private MultiThreadDownloader downloader;

    private static final int REQUEST_STORAGE_PERMISSION = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etUrl = findViewById(R.id.et_url);
        etThreadCount = findViewById(R.id.et_thread_count);
        btnStart = findViewById(R.id.btn_start);
        btnPause = findViewById(R.id.btn_pause);
        btnResume = findViewById(R.id.btn_resume);
        btnCancel = findViewById(R.id.btn_cancel);
        pbDownload = findViewById(R.id.pb_download);
        tvPercent = findViewById(R.id.tv_percent);
        tvFileSize = findViewById(R.id.tv_file_size);
        tvDownloaded = findViewById(R.id.tv_downloaded);
        tvSpeed = findViewById(R.id.tv_speed);
        tvRemaining = findViewById(R.id.tv_remaining);
        tvStatus = findViewById(R.id.tv_status);

        // 请求存储权限（Android 10以下需要，但我们使用公共目录实际也需要权限，这里保留请求逻辑）
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                Manifest.permission.READ_EXTERNAL_STORAGE},
                        REQUEST_STORAGE_PERMISSION);
            }
        }

        btnStart.setOnClickListener(v -> startDownload());
        btnPause.setOnClickListener(v -> { if (downloader != null) downloader.pause(); });
        btnResume.setOnClickListener(v -> { if (downloader != null) downloader.resume(); });
        btnCancel.setOnClickListener(v -> { if (downloader != null) downloader.cancel(); });
    }

    private void startDownload() {
        String url = etUrl.getText().toString().trim();
        String threadStr = etThreadCount.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "请输入URL", Toast.LENGTH_SHORT).show();
            return;
        }
        int threadCount;
        try {
            threadCount = Integer.parseInt(threadStr);
            if (threadCount < 1) threadCount = 1;
        } catch (NumberFormatException e) {
            threadCount = 3;
        }

        // 切换按钮状态
        setButtonState(true);

        downloader = new MultiThreadDownloader(url, threadCount, new MultiThreadDownloader.OnDownloadListener() {
            @Override
            public void onProgress(int percent, String fileSize, String downloaded, String speed, String remainingTime) {
                runOnUiThread(() -> {
                    pbDownload.setProgress(percent);
                    tvPercent.setText(percent + "%");
                    tvFileSize.setText("文件大小：" + fileSize);
                    tvDownloaded.setText("已下载：" + downloaded);
                    tvSpeed.setText("速度：" + speed);
                    tvRemaining.setText("剩余时间：" + remainingTime);
                });
            }

            @Override
            public void onStatusChanged(String status) {
                runOnUiThread(() -> tvStatus.setText("状态：" + status));
            }

            @Override
            public void onDownloadComplete() {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "下载完成", Toast.LENGTH_SHORT).show();
                    setButtonState(false);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                    setButtonState(false);
                });
            }
        });
        downloader.start();
    }

    private void setButtonState(boolean downloading) {
        btnStart.setEnabled(!downloading);
        btnPause.setEnabled(downloading);
        btnResume.setEnabled(false);
        btnCancel.setEnabled(downloading);
    }

    // 外部动态调整按钮（可在暂停/继续时调用）
    public void updateButtonsOnPause() {
        btnPause.setEnabled(false);
        btnResume.setEnabled(true);
        btnCancel.setEnabled(true);
    }

    public void updateButtonsOnResume() {
        btnPause.setEnabled(true);
        btnResume.setEnabled(false);
        btnCancel.setEnabled(true);
    }

    public void updateButtonsOnCancelOrComplete() {
        setButtonState(false);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限已授予
            } else {
                Toast.makeText(this, "存储权限被拒绝，可能无法保存文件到公共目录", Toast.LENGTH_SHORT).show();
            }
        }
    }
}