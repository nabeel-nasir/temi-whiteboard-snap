// reference: https://stackoverflow.com/questions/43031207/take-photo-in-service-using-camera2-api
package com.linklab.whiteboardsnap;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

public class Camera2Service extends Service {
    protected static final int CAMERA_CALIBRATION_DELAY = 5000;
    protected static final String TAG = "camera2Service";
    protected static final int CAMERACHOICE = CameraCharacteristics.LENS_FACING_FRONT;
    protected static long cameraCaptureStartTime;
    protected CameraDevice cameraDevice;
    protected CameraCaptureSession session;
    protected ImageReader imageReader;
    protected CameraCharacteristics camCharacteristics;
    protected boolean alreadyProcessingImage = false;
    protected int numClickedPictures = 0;

    protected CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "CameraDevice.StateCallback onOpened");
            cameraDevice = camera;
            actOnReadyCameraDevice();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.w(TAG, "CameraDevice.StateCallback onDisconnected");
        }

        @Override
        public void onError(@NonNull CameraDevice camera,
                            int error) {
            Log.e(TAG, "CameraDevice.StateCallback onError " + error);
        }
    };

    protected CameraCaptureSession.StateCallback sessionStateCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onReady(CameraCaptureSession session) {
            Camera2Service.this.session = session;
            try {
                session.setRepeatingRequest(createCaptureRequest(), null, null);
//                session.capture(createCaptureRequest(), null, null);
                cameraCaptureStartTime = System.currentTimeMillis();
            } catch (CameraAccessException e) {
                Log.e(TAG, e.getMessage());
            }
        }


        @Override
        public void onConfigured(CameraCaptureSession session) {

        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
        }
    };

    protected ImageReader.OnImageAvailableListener onImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Log.d(TAG, "onImageAvailable");
            Image img = reader.acquireLatestImage();
            if (img != null) {
                // discard a few initial pics to ensure we don't have yellow pics due to unadjusted WB
                if (++numClickedPictures > 3 && !alreadyProcessingImage) {
                    alreadyProcessingImage = true;
                    Log.d(TAG, "onImageAvailable: processing image!");
                    processImage(img);
                } else {
                    Log.d(TAG, "onImageAvailable: discarded image!");
                }
                img.close();
            }
        }
    };

    public void readyCamera(String cameraId) {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
//            String pickedCamera = getCamera(manager);
            String pickedCamera = cameraId; // using the hardcoded cameraId instead of picking with code
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            manager.openCamera(pickedCamera, cameraStateCallback, null);
            imageReader = ImageReader.newInstance(1920, 1088, ImageFormat.JPEG, 1 /* images buffered */);
            imageReader.setOnImageAvailableListener(onImageAvailableListener, null);
            Log.d(TAG, "imageReader created");
        } catch (CameraAccessException e) {
            Log.e(TAG, e.getMessage());
        }
    }

    public String getCamera(CameraManager manager) {
        try {
            for (String cameraId : manager.getCameraIdList()) {
                camCharacteristics = manager.getCameraCharacteristics(cameraId);
                int cOrientation = camCharacteristics.get(CameraCharacteristics.LENS_FACING);
                if (cOrientation == CAMERACHOICE) {
                    return cameraId;
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public int onStartCommand(Intent intent,
                              int flags,
                              int startId) {
        Log.d(TAG, "onStartCommand flags " + flags + " startId " + startId);
        String cameraId = intent.getStringExtra("cameraId");
        readyCamera(cameraId);

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate service");
        super.onCreate();
    }

    public void actOnReadyCameraDevice() {
        try {
            cameraDevice.createCaptureSession(Arrays.asList(imageReader.getSurface()), sessionStateCallback, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        cameraDevice.close();
    }


    private void processImage(Image image) {
        Log.v(TAG, "in processImage!!");

        //Process image data
        ByteBuffer buffer;
        byte[] bytes;
        boolean success = false;
        File file = new File(Environment.getExternalStorageDirectory() + "/Pictures/image.jpg");
        FileOutputStream output = null;

        if (image.getFormat() == ImageFormat.JPEG) {
            buffer = image.getPlanes()[0].getBuffer();
            bytes = new byte[buffer.remaining()]; // makes byte array large enough to hold image
            buffer.get(bytes); // copies image from buffer to byte array
            try {
                output = new FileOutputStream(file);
                output.write(bytes);    // write the byte array to file
//                j++;
                success = true;
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                image.close(); // close this to free up buffer for other images
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                Log.d(TAG, "processImage: we're all done!");
                Intent intent = new Intent();
                intent.setAction("imageReady");
                this.sendBroadcast(intent);
                stopSelf();
            }

        }


    }

//    private int getJpegOrientation(CameraCharacteristics c, int deviceOrientation) {
//        if (deviceOrientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return 0;
//        int sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
//
//        // Round device orientation to a multiple of 90
//        deviceOrientation = (deviceOrientation + 45) / 90 * 90;
//
//        // Reverse device orientation for front-facing cameras
//        boolean facingFront = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT;
//        if (facingFront) deviceOrientation = -deviceOrientation;
//
//        // Calculate desired JPEG orientation relative to camera orientation to make
//        // the image upright relative to the device orientation
//        int jpegOrientation = (sensorOrientation + deviceOrientation + 360) % 360;
//
//        return jpegOrientation;
//    }


    protected CaptureRequest createCaptureRequest() {
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(imageReader.getSurface());
//            WindowManager windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
//            int rotation = windowManager.getDefaultDisplay().getRotation();
//            int jpegRotation = getJpegOrientation(camCharacteristics, rotation);
            builder.set(CaptureRequest.JPEG_ORIENTATION, 180); // hardcoding orientation for the temi camera
            return builder.build();
        } catch (CameraAccessException e) {
            Log.e(TAG, e.getMessage());
            return null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
