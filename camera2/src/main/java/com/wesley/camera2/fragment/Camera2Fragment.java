package com.wesley.camera2.fragment;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v13.app.FragmentCompat;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import com.wesley.camera2.util.CameraUtil;
import com.wesley.camera2.widget.AutoFitTextureView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Created by wesley on 2016/03/04.
 */
@TargetApi(21)
public abstract class Camera2Fragment extends Fragment {

    public interface Cam2Listener {
        void onCameraException(CameraAccessException cae);

        void onNullPointerException(NullPointerException npe);

        void onInterruptedException(InterruptedException ie);

        void onIOException(IOException ioe);

        void onConfigurationFailed();
    }

    private AutoFitTextureView mCameraLayout;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mPreviewSession;
    private Size mPreviewSize;
    private Size mVideoSize;
    private CaptureRequest.Builder mPreviewBuilder;
    private MediaRecorder mMediaRecorder;
    private boolean isRecording;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    private boolean upsideDown;
    private int mCameraFacing = CameraCharacteristics.LENS_FACING_BACK;

    public abstract Cam2Listener getCam2Listener();

    public abstract int getTextureResource();

    public abstract File getVideoFile(Context context);

    public int getCameraFacing() {
        return mCameraFacing;
    }

    public void setCameraFacing(int mCameraFacing) {
        this.mCameraFacing = mCameraFacing;
    }

    public boolean isRecording() {
        return isRecording;
    }

    public void startRecordingVideo() {
        try {
            // UI
            isRecording = true;

            // Start recording
            mMediaRecorder.start();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
    }

    public void stopRecordingVideo() {
        stopRecordingVideo(false);
    }

    private void stopRecordingVideo(boolean kill) {
        // UI
        isRecording = false;

        // Stop Recording
        closeCamera();
        if (!kill) {
            openCamera(mCameraLayout.getWidth(), mCameraLayout.getHeight());
        }
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mCameraLayout = (AutoFitTextureView) view.findViewById(getTextureResource());
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();
        if (mCameraLayout.isAvailable()) {
            openCamera(mCameraLayout.getWidth(), mCameraLayout.getHeight());
        } else {
            mCameraLayout.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        if (isRecording) {
            stopRecordingVideo(true);
        } else {
            mMediaRecorder = null;
            closeCamera();
        }
        stopBackgroundThread();
        super.onPause();
    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
            getCam2Listener().onInterruptedException(e);
        }
    }

    protected void requestVideoPermissions() {
        if (CameraUtil.shouldShowRequestPermissionRationale(this, Camera2PermissionDialog.VIDEO_PERMISSIONS)) {
            Camera2PermissionDialog.newInstance(getParentFragment()).show(getChildFragmentManager(), Camera2PermissionDialog.FRAGMENT_DIALOG);
        } else {
            FragmentCompat.requestPermissions(this, Camera2PermissionDialog.VIDEO_PERMISSIONS, Camera2PermissionDialog.REQUEST_VIDEO_PERMISSIONS);
        }
    }

    private void openCamera(int width, int height) {
        if (!CameraUtil.hasPermissionsGranted(getActivity(), Camera2PermissionDialog.VIDEO_PERMISSIONS)) {
            requestVideoPermissions();
            return;
        }

        final Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }

        CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }

            String cameraId = cameraManager.getCameraIdList()[0]; // Default to back camera
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                int cameraFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (cameraFacing == mCameraFacing) {
                    cameraId = id;
                    break;
                }
            }

            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            mVideoSize = CameraUtil.chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
            mPreviewSize = CameraUtil.chooseVideoSize(map.getOutputSizes(SurfaceTexture.class));

            int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                mCameraLayout.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            } else {
                mCameraLayout.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
            }

            int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (sensorOrientation == 270) {
                // Camera is mounted the wrong way...
                upsideDown = true;
            }

            configureTransform(width, height);
            mMediaRecorder = new MediaRecorder();
            cameraManager.openCamera(cameraId, mStateCallback, null);

        } catch (CameraAccessException cae) {
            cae.printStackTrace();
            getCam2Listener().onCameraException(cae);
        } catch (NullPointerException npe) {
            npe.printStackTrace();
            getCam2Listener().onNullPointerException(npe);
        } catch (InterruptedException ie) {
            ie.printStackTrace();
            getCam2Listener().onInterruptedException(ie);
            throw new RuntimeException("Interrupted while trying to lock camera opening.");
        } catch (SecurityException se) {
            requestVideoPermissions();
        }
    }

    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (mPreviewSession != null) {
                mPreviewSession.close();
                mPreviewSession = null;
            }
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (mMediaRecorder != null) {
                mMediaRecorder.stop();
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
        } catch (InterruptedException ie) {
            ie.printStackTrace();
            getCam2Listener().onInterruptedException(ie);
            throw new RuntimeException("Interrupted while trying to lock camera closing.");
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    private void startPreview() {
        if (mCameraDevice == null || !mCameraLayout.isAvailable() || mPreviewSize == null) {
            return;
        }
        try {
            setUpMediaRecorder();
            mCameraDevice.createCaptureSession(getSurfaces(), mSessionCallback, mBackgroundHandler);
        } catch (CameraAccessException cae) {
            cae.printStackTrace();
            getCam2Listener().onCameraException(cae);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            getCam2Listener().onIOException(ioe);
        }
    }

    private List<Surface> getSurfaces() {
        List<Surface> surfaces = new ArrayList<>();
        try {
            SurfaceTexture texture = mCameraLayout.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

            Surface previewSurface = new Surface(texture);
            surfaces.add(previewSurface);
            mPreviewBuilder.addTarget(previewSurface);

            Surface recorderSurface = mMediaRecorder.getSurface();
            surfaces.add(recorderSurface);
            mPreviewBuilder.addTarget(recorderSurface);
        } catch (CameraAccessException cae) {
            cae.printStackTrace();
            getCam2Listener().onCameraException(cae);
        }

        return surfaces;
    }

    private void updatePreview() {
        if (mCameraDevice == null) {
            return;
        }
        try {
            setUpCaptureRequestBuilder(mPreviewBuilder);
            HandlerThread thread = new HandlerThread("CameraPreview");
            thread.start();
            mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException cae) {
            cae.printStackTrace();
            getCam2Listener().onCameraException(cae);
        }
    }

    protected void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (mCameraLayout == null || mPreviewSize == null || activity == null) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        mCameraLayout.setTransform(matrix);
    }

    protected void setUpMediaRecorder() throws IOException {
        final Activity activity = getActivity();
        if (null == activity) {
            return;
        }
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mMediaRecorder.setOutputFile(getVideoFile(activity).getAbsolutePath());
        mMediaRecorder.setVideoEncodingBitRate(1600 * 1000);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int orientation = CameraUtil.getOrientation(rotation, upsideDown);
        mMediaRecorder.setOrientationHint(orientation);
        mMediaRecorder.prepare();
    }

    // Listeners
    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCameraDevice = camera;
            startPreview();
            mCameraOpenCloseLock.release();
            if (null != mCameraLayout) {
                configureTransform(mCameraLayout.getWidth(), mCameraLayout.getHeight());
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            mCameraOpenCloseLock.release();
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            mCameraOpenCloseLock.release();
            camera.close();
            mCameraDevice = null;
            Activity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }
    };

    private CameraCaptureSession.StateCallback mSessionCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
            mPreviewSession = cameraCaptureSession;
            updatePreview();
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            Activity activity = getActivity();
            if (null != activity) {
                getCam2Listener().onConfigurationFailed();
                activity.finish();
            }
        }
    };

    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };
}