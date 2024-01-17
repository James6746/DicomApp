package com.jamesmobiledev.dicom.ui;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.renderscript.Allocation;
import androidx.renderscript.Element;
import androidx.renderscript.RenderScript;
import androidx.renderscript.ScriptIntrinsicYuvToRGB;
import androidx.renderscript.Type;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Toast;

import com.jamesmobiledev.dicom.databinding.ActivityCameraBinding;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import timber.log.Timber;

public class CameraActivity extends AppCompatActivity {

    private ActivityCameraBinding binding;
    private String TAG = "@@@";

    private TextureView textureView;
    private String cameraId;
    private HandlerThread backgroundHandlerThread;
    private Handler backgroundHandler;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession cameraCaptureSession;
    private ImageReader imageReader;
    private Size previewSize;
    private boolean isContinuousCapture = false;
    private int frameCounter = 0;
    long savedTimeMillis = 0;

    private List<Uri> capturedImageUris = new ArrayList<>();

    private static final int FRAME_COUNT = 5;
    private static final long FRAME_INTERVAL_MS = 80; // 100ms
    private int currentFrameCount = 0;
    private boolean canCapture = false;

    String dicomDataJson;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCameraBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());


        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            dicomDataJson = extras.getString("dicomData");
        }

        textureView = binding.textureView;
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        binding.cameraCaptureButton.setOnClickListener(v -> {
            deleteFilesInDirectory();
            capturedImageUris.clear();
            currentFrameCount = 0;
            savedTimeMillis = System.currentTimeMillis();

//            frameCaptureHandler.post(frameCaptureRunnable);
        });

        binding.btnCancel.setOnClickListener(v -> {
            this.finish();
        });

    }

    private void setupCameraAndBackgroundThread() {
        startBackgroundThread();
        setupCamera();
    }

    private void setupCamera() {
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            for (String id : cameraIds) {
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(id);
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue; // Skip front-facing camera
                }

                StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    previewSize = new Size(1280, 720);
                    // Set up ImageReader for frame capture with the chosen size
                    imageReader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2 /* maxImages */);
                    imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);
                    cameraId = id; // Use this camera ID for capturing
                    break;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error accessing camera: " + e.getMessage());
        }
    }

    private void startBackgroundThread() {
        backgroundHandlerThread = new HandlerThread("CameraBackground");
        backgroundHandlerThread.start();
        backgroundHandler = new Handler(backgroundHandlerThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundHandlerThread != null) {
            backgroundHandlerThread.quitSafely();
            try {
                backgroundHandlerThread.join();
                backgroundHandlerThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error on stopping background thread: " + e.getMessage());
            }
        }
    }

    private TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
//            long currentTimeMillis = System.currentTimeMillis();
//            long timeDifference = currentTimeMillis - savedTimeMillis;
//
//            if (savedTimeMillis != 0 && timeDifference >= FRAME_INTERVAL_MS && currentFrameCount < FRAME_COUNT) {
//                savedTimeMillis = currentTimeMillis;
//                Log.d("@@@", "onSurfaceTextureUpdated: " + currentFrameCount);
//                currentFrameCount++;
//                processAndSaveImage(textureView.getBitmap());
//            }

        }
    };

    private CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera device error: " + error);
            camera.close();
            cameraDevice = null;
        }
    };

    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;

            // Configure the size of default buffer to be the size of camera preview we want
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            // This is the output Surface we need to start the preview
            Surface previewSurface = new Surface(texture);
            Surface imageReaderSurface = imageReader.getSurface(); // Surface for ImageReader

            // Set up a CaptureRequest.Builder with the output Surface
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(previewSurface);
            captureRequestBuilder.addTarget(imageReaderSurface); // Add ImageReader surface as target

            // Create a CameraCaptureSession for camera preview
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, imageReaderSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    if (null == cameraDevice) {
                        return;
                    }
                    CameraActivity.this.cameraCaptureSession = cameraCaptureSession;
                    try {
                        // Auto focus should be continuous for camera preview
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        // Start displaying the camera preview
                        CaptureRequest previewRequest = captureRequestBuilder.build();
                        CameraActivity.this.cameraCaptureSession.setRepeatingRequest(previewRequest, null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Error setting up preview", e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    showToast("Failed to configure camera.");
                }
            }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error setting up preview: " + e.getMessage());
        }
    }

    private Handler frameCaptureHandler = new Handler();
    private Runnable frameCaptureRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentFrameCount < FRAME_COUNT) {
                canCapture = true;
                frameCaptureHandler.postDelayed(this, FRAME_INTERVAL_MS); // Schedule next frame capture
            }
        }
    };

    private void captureComplete() {
        savedTimeMillis = 0;
        ArrayList<String> uriStrings = new ArrayList<>();
        for (Uri uri : capturedImageUris) {
            uriStrings.add(uri.toString());
        }

        Intent intent = new Intent(this, CameraResultActivity.class);
        intent.putStringArrayListExtra("uris", uriStrings);
        intent.putExtra("dicomData", dicomDataJson);
        startActivity(intent);
    }

    private ImageReader.OnImageAvailableListener onImageAvailableListener = reader -> {
        Image image = null;
        try {
            image = reader.acquireNextImage();
            if (image != null) {
                long currentTimeMillis = System.currentTimeMillis();
                long timeDifference = currentTimeMillis - savedTimeMillis;

                if (savedTimeMillis != 0 && timeDifference >= FRAME_INTERVAL_MS && currentFrameCount < FRAME_COUNT) {
                    savedTimeMillis = currentTimeMillis;
                    Log.d("@@@", "onSurfaceTextureUpdated: " + currentFrameCount);
                    currentFrameCount++;
                    processAndSaveImage(yuvToBitmap(image));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving image: " + e.getMessage());
        } finally {
            if (image != null) {
                image.close();
            }
        }
    };

    private void processAndSaveImage(Bitmap bitmap) {
        new Thread(() -> {
            Uri uri = saveBitmapToFile(bitmap);
            if (uri != null) {
                capturedImageUris.add(uri);
            }
            if (capturedImageUris.size() == FRAME_COUNT) {
                captureComplete();
            }
        }).start();
    }

    private Bitmap rotateBitmap(Bitmap source, float angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }


    private Uri saveBitmapToFile(Bitmap bitmap) {
        String fileName = "image_" + System.currentTimeMillis() + ".jpg";
        File file = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), fileName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
            return Uri.fromFile(file);
        } catch (IOException e) {
            Log.e(TAG, "Error saving bitmap", e);
        }
        return null;
    }

    private Bitmap yuvToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        // U and V are swapped
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        RenderScript rs = RenderScript.create(this);
        ScriptIntrinsicYuvToRGB yuvToRgbIntrinsic = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs));

        Type.Builder yuvType = new Type.Builder(rs, Element.U8(rs)).setX(nv21.length);
        Allocation in = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT);

        Type.Builder rgbaType = new Type.Builder(rs, Element.RGBA_8888(rs)).setX(image.getWidth()).setY(image.getHeight());
        Allocation out = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT);

        in.copyFrom(nv21);

        yuvToRgbIntrinsic.setInput(in);
        yuvToRgbIntrinsic.forEach(out);

        Bitmap bmpOut = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
        out.copyTo(bmpOut);

        return bmpOut;
    }

    private void showToast(final String text) {
        runOnUiThread(() -> Toast.makeText(CameraActivity.this, text, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupCameraAndBackgroundThread();

        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        closeCamera();
        stopBackgroundThread();
    }


    @SuppressLint("MissingPermission")
    private void openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        try {
            if (cameraManager == null) {
                cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            }

            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

                // Check if this camera is the rear camera
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                // Open the camera
                this.cameraId = cameraId;
                cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler);
                break;
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Exception accessing camera", e);
        }
    }


    private void closeCamera() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    private void deleteFilesInDirectory() {
        File directory = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (directory != null && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!file.delete()) {
                        Timber.tag("@@@").e("Failed to delete %s", file);
                    }
                }
            }
        }
    }


}