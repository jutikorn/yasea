package net.ossrs.yasea;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;
import java.util.List;

/**
 * Created by leo.ma on 2016/9/13.
 */
public class SrsCameraView extends SurfaceView implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private Camera mCamera;

    private int mPreviewRotation = 90;
    private int mPreviewOrientation = Configuration.ORIENTATION_PORTRAIT;
    private int mCamId = -1;
    private PreviewCallback mPrevCb;
    private byte[] mYuvPreviewFrame;
    private int mPreviewWidth;
    private int mPreviewHeight;
    private boolean mIsEncoding;
    private int mRotationPicture;


    public interface PreviewCallback {
        void onGetYuvFrame(byte[] data, int rotationPicture);
    }

    public SrsCameraView(Context context) {
        this(context, null);
    }

    public SrsCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setCameraId(int id) {
        mCamId = id;
        setPreviewOrientation(mCamId, mCamera);
    }

    public void setPreviewOrientation(int cameraId){
        setPreviewOrientation(cameraId, mCamera);
    }


    public void setPreviewOrientation(int cameraId, android.hardware.Camera camera) {
        android.hardware.Camera.CameraInfo info =
                new android.hardware.Camera.CameraInfo();
        android.hardware.Camera.getCameraInfo(cameraId, info);
        int rotation = ((Activity)getContext()).getWindowManager().getDefaultDisplay()
                .getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0: degrees = 0; break;
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
        }

        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
            mRotationPicture = (360 - result) % 360;
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360;
            mRotationPicture = result;

        }
        mPreviewRotation = result;
    }

    public int getCameraId() {
        return mCamId;
    }

    public void setPreviewCallback(PreviewCallback cb) {
        mPrevCb = cb;
        getHolder().addCallback(this);
    }

    public int[] setPreviewResolution(int width, int height) {
        getHolder().setFixedSize(width, height);

        mCamera = openCamera();
        mPreviewWidth = width;
        mPreviewHeight = height;
        Camera.Size rs = adaptPreviewResolution(mCamera.new Size(width, height));
        if (rs != null) {
            mPreviewWidth = rs.width;
            mPreviewHeight = rs.height;
        }
        mCamera.getParameters().setPreviewSize(mPreviewWidth, mPreviewHeight);
        mYuvPreviewFrame = new byte[mPreviewWidth * mPreviewHeight * 3 / 2];
        return new int[] { mPreviewWidth, mPreviewHeight };
    }

    public void enableEncoding() {
        mIsEncoding = true;
    }

    public void disableEncoding() {
        mIsEncoding = false;
    }

    public boolean startCamera() {
        if (mCamera == null) {
            mCamera = openCamera();
            if (mCamera == null) {
                return false;
            }
        }

        Camera.Parameters params = mCamera.getParameters();
        int[] range = adaptFpsRange(SrsEncoder.VFPS, params.getSupportedPreviewFpsRange());
        params.setPreviewFpsRange(range[0], range[1]);
        params.setPreviewSize(mPreviewWidth, mPreviewHeight);
        params.setPreviewFormat(ImageFormat.NV21);
        params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
        params.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
        params.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO);
        params.setRotation(mRotationPicture);
        params.set("orientation", "portrait");
        params.set("rotation", mRotationPicture);
        List<String> supportedFocusModes = params.getSupportedFocusModes();
        if (supportedFocusModes != null && !supportedFocusModes.isEmpty()) {
            if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                mCamera.autoFocus(null);
            } else {
                params.setFocusMode(supportedFocusModes.get(0));
            }
        }

        List<String> supportedFlashModes = params.getSupportedFlashModes();
        if (supportedFlashModes != null && !supportedFlashModes.isEmpty()) {
            if (supportedFlashModes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            } else {
                params.setFlashMode(supportedFlashModes.get(0));
            }
        }

        mCamera.setParameters(params);

        mCamera.setDisplayOrientation(mPreviewRotation);

        mCamera.addCallbackBuffer(mYuvPreviewFrame);
        mCamera.setPreviewCallbackWithBuffer(this);
        try {
            mCamera.setPreviewDisplay(getHolder());
        } catch (IOException e) {
            e.printStackTrace();
        }
        mCamera.startPreview();

        return true;
    }

    public void stopCamera() {
        disableEncoding();

        if (mCamera != null) {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    public void flashOff() {
        if (getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)){
            if (mCamera != null) {
                Camera.Parameters parameters = mCamera.getParameters();
                List<String> flashModes = parameters.getSupportedFlashModes();

                String flashMode = parameters.getFlashMode();
                if (!Camera.Parameters.FLASH_MODE_OFF.equals(flashMode)) {
                    if (flashModes.contains(Camera.Parameters.FLASH_MODE_OFF)) {
                        parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                        mCamera.setParameters(parameters);
                    }
                }
            }
        }
    }

    private Camera openCamera() {
        Camera camera;
        if (mCamId < 0) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            int numCameras = Camera.getNumberOfCameras();
            int frontCamId = -1;
            int backCamId = -1;
            for (int i = 0; i < numCameras; i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    backCamId = i;
                } else if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    frontCamId = i;
                    break;
                }
            }
            if (frontCamId != -1) {
                mCamId = frontCamId;
            } else if (backCamId != -1) {
                mCamId = backCamId;
            } else {
                mCamId = 0;
            }
        }
        camera = Camera.open(mCamId);
        setPreviewOrientation(mCamId, camera);
        return camera;
    }

    private Camera.Size adaptPreviewResolution(Camera.Size resolution) {
        float diff = 100f;
        float xdy = (float) resolution.width / (float) resolution.height;
        Camera.Size best = null;
        for (Camera.Size size : mCamera.getParameters().getSupportedPreviewSizes()) {
            if (size.equals(resolution)) {
                return size;
            }
            float tmp = Math.abs(((float) size.width / (float) size.height) - xdy);
            if (tmp < diff) {
                diff = tmp;
                best = size;
            }
        }
        return best;
    }

    private int[] adaptFpsRange(int expectedFps, List<int[]> fpsRanges) {
        expectedFps *= 1000;
        int[] closestRange = fpsRanges.get(0);
        int measure = Math.abs(closestRange[0] - expectedFps) + Math.abs(closestRange[1] - expectedFps);
        for (int[] range : fpsRanges) {
            if (range[0] <= expectedFps && range[1] >= expectedFps) {
                int curMeasure = Math.abs(range[0] - expectedFps) + Math.abs(range[1] - expectedFps);
                if (curMeasure < measure) {
                    closestRange = range;
                    measure = curMeasure;
                }
            }
        }
        return closestRange;
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (mIsEncoding) {
            mPrevCb.onGetYuvFrame(data, mRotationPicture);
            camera.addCallbackBuffer(mYuvPreviewFrame);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceCreated(SurfaceHolder arg0) {
        if (mCamera != null) {
            try {
                mCamera.setPreviewDisplay(getHolder());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder arg0) {
    }

}
