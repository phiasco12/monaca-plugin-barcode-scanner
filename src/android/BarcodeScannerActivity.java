package io.monaca.plugin.barcodescanner;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

public class BarcodeScannerActivity extends AppCompatActivity {

    private static final String TAG = "BarcodeScannerActivity";
    private PreviewView previewView;
    private Button detectedTextButton;
    private ImageView detectionArea;
    private Barcode detectedBarcode;
    private TextView timeoutPromptView;
    private ImageView debugPreviewView;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;

    public static final String INTENT_DETECTED_TEXT = "detectedText";
    public static final String INTENT_DETECTED_FORMAT = "detectedFormat";

    private final int DETECTION_AREA_COLOR = 0xffffffff;
    private final int DETECTION_AREA_DETECTED_COLOR = 0xff0085b1;
    private final int DETECTION_AREA_BORDER = 12;

    private final int DETECTED_TEXT_BACKGROUND_COLOR = 0xff0085b1;
    private final int DETECTED_TEXT_COLOR = 0xffffffff;
    private final int DETECTED_TEXT_MAX_LENGTH = 40;

    private final int TIMEOUT_PROMPT_BACKGROUND_COLOR = 0xb4404040;
    private final int TIMEOUT_PROMPT_BACKGROUND_CORNER_RADIUS = 20;

    private boolean oneShot = false;
    private boolean showTimeoutPrompt;
    private int timeoutPromptSpan;
    private String timeoutPrompt = "Barcode not detected";
    private int debugPreviewMode = 0;

    private Handler timeoutPromptHandler;
    private Runnable timeoutPromptRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_barcode_scanner);
        previewView = findViewById(R.id.preview_view);
        detectedTextButton = findViewById(R.id.detected_text);
        detectionArea = findViewById(R.id.detection_area);
        timeoutPromptView = findViewById(R.id.timeout_prompt);
        debugPreviewView = findViewById(R.id.debug_preview);

        Intent intent = getIntent();
        oneShot = intent.getBooleanExtra("oneShot", false);
        showTimeoutPrompt = intent.getBooleanExtra("timeoutPrompt.show", false);
        timeoutPromptSpan = intent.getIntExtra("timeoutPrompt.timeout", -1);
        String prompt = intent.getStringExtra("timeoutPrompt.prompt");
        if (prompt != null && prompt.length() > 0) {
            timeoutPrompt = prompt;
        }
        debugPreviewMode = intent.getIntExtra("debug.preview", 0);

        detectedTextButton.setOnClickListener(v -> {
            if (detectedBarcode != null) {
                setResult(Activity.RESULT_OK, getResultIntent());
                finish();
            }
        });

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Failed to checkSelfPermission");
            return;
        }
        initCamera();
    }

    private Intent getResultIntent() {
        Intent intent = new Intent();
        if (detectedBarcode != null) {
            intent.putExtra(INTENT_DETECTED_TEXT, detectedBarcode.getDisplayValue());
            intent.putExtra(INTENT_DETECTED_FORMAT, getBarcodeFormatString(detectedBarcode.getFormat()));
        }
        return intent;
    }

    private static String getBarcodeFormatString(int format) {
        switch (format) {
            case Barcode.FORMAT_QR_CODE:
                return "QR_CODE";
            case Barcode.FORMAT_EAN_8:
                return "EAN_8";
            case Barcode.FORMAT_EAN_13:
                return "EAN_13";
            case Barcode.FORMAT_ITF:
                return "ITF";
            case Barcode.FORMAT_CODE_128:
                return "CODE_128";
            case Barcode.FORMAT_CODE_39:
                return "CODE_39";
            case Barcode.FORMAT_CODE_93:
                return "CODE_93";
            case Barcode.FORMAT_CODABAR:
                return "CODABAR";
            case Barcode.FORMAT_UPC_A:
                return "UPC_A";
            case Barcode.FORMAT_UPC_E:
                return "UPC_E";
            case Barcode.FORMAT_PDF417:
                return "PDF417";
            case Barcode.FORMAT_AZTEC:
                return "AZTEC";
            case Barcode.FORMAT_DATA_MATRIX:
                return "DATA_MATRIX";
            default:
                return "UNKNOWN";
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private void initCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        Executor executor = ContextCompat.getMainExecutor(this);

        Runnable listenerRunnable = () -> {
            ProcessCameraProvider cameraProvider = null;
            try {
                cameraProvider = cameraProviderFuture.get();
                bindToLifecycle(cameraProvider, executor);
            } catch (ExecutionException e) {
                Log.d(TAG, "CameraProvider ExecutionException");
            } catch (InterruptedException e) {
                Log.d(TAG, "CameraProvider InterruptedException");
            }
        };
        cameraProviderFuture.addListener(listenerRunnable, executor);

        startDetectionTimer();
    }

    private void bindToLifecycle(ProcessCameraProvider cameraProvider, Executor executor) {
        Preview preview = new Preview.Builder().build();
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ScannerAnalyzer analyzer = new ScannerAnalyzer();

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(executor, analyzer);

        cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis, preview);
    }

    private void onDetectionTaskSuccess(List<Barcode> barcodes) {
        int detected = 0;
        for (Barcode barcode : barcodes) {
            detectedBarcode = barcode;
            String detectedText = barcode.getDisplayValue();
            if (detectedText == null) {
                detectedBarcode = null;
                continue;
            }
            detected++;

            if (!oneShot) {
                GradientDrawable drawable = (GradientDrawable) detectionArea.getDrawable();
                drawable.setStroke(DETECTION_AREA_BORDER, DETECTION_AREA_DETECTED_COLOR);

                detectedTextButton.setText(
                        detectedText.substring(0, Math.min(DETECTED_TEXT_MAX_LENGTH, detectedText.length())));
                detectedTextButton.setVisibility(View.VISIBLE);
            }
        }
        if (detected == 0) {
            detectedBarcode = null;

            detectedTextButton.setText("");
            detectedTextButton.setVisibility(View.INVISIBLE);
            GradientDrawable drawable = (GradientDrawable) detectionArea.getDrawable();
            drawable.setStroke(DETECTION_AREA_BORDER, DETECTION_AREA_COLOR);
        } else {
            if (oneShot) {
                setResult(Activity.RESULT_OK, getResultIntent());
                finish();
            }
            restartDetectionTimer();
        }
    }

    private class ScannerAnalyzer implements ImageAnalysis.Analyzer {
        private BarcodeScanner scanner;

        ScannerAnalyzer() {
            BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                    .build();
            scanner = BarcodeScanning.getClient(options);
        }

        @Override
        public void analyze(@NonNull ImageProxy imageProxy) {
            Image mediaImage = imageProxy.getImage();

            if (mediaImage != null) {
                ByteBuffer imageBuffer = ByteBuffer.allocate(mediaImage.getHeight() * mediaImage.getWidth() * 4);
                mediaImage.getPlanes()[0].getBuffer().rewind();
                byte[] bytes = new byte[mediaImage.getPlanes()[0].getBuffer().remaining()];
                mediaImage.getPlanes()[0].getBuffer().get(bytes);
                imageBuffer.put(bytes);
                InputImage inputImage = InputImage.fromByteBuffer(imageBuffer, mediaImage.getWidth(), mediaImage.getHeight(), InputImage.IMAGE_FORMAT_NV21, mediaImage.getImageInfo().getRotationDegrees());

                scanner.process(inputImage)
                        .addOnSuccessListener(barcodes -> {
                            onDetectionTaskSuccess(barcodes);
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "Barcode detection failed: " + e.getMessage());
                        })
                        .addOnCompleteListener(task -> {
                            imageProxy.close();
                        });
            }
        }
    }

    private boolean isEnableTimeoutPrompt() {
        return showTimeoutPrompt && timeoutPromptSpan >= 0;
    }

    private void startDetectionTimer () {
        if (!isEnableTimeoutPrompt()) {
            return;
        }
        timeoutPromptHandler = new Handler();
        timeoutPromptRunnable = () -> timeoutPromptView.setVisibility(View.VISIBLE);
        timeoutPromptHandler.postDelayed(timeoutPromptRunnable, Math.max(timeoutPromptSpan * 1000, 400));
    }

    private void restartDetectionTimer () {
        if (!isEnableTimeoutPrompt()) {
            return;
        }
        if (timeoutPromptHandler != null) {
            timeoutPromptHandler.removeCallbacks(timeoutPromptRunnable);
        }
        timeoutPromptView.setVisibility(View.INVISIBLE);
        startDetectionTimer();
    }
}
