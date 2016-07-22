package ru.altarix.cameracontroller;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.ErrorCallback;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.ShutterCallback;
import android.hardware.Camera.Size;
import android.location.Location;
import android.media.AudioManager;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.media.MediaRecorder.OnErrorListener;
import android.media.MediaRecorder.OnInfoListener;
import android.os.Build;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import ru.altarix.cameracontroller.executors.MakePreviewThreadPoolExecutor;
import ru.altarix.cameracontroller.executors.info.MakePreviewRunnableInfo;
import ru.altarix.cameracontroller.settings.COLOR_EFFECT;
import ru.altarix.cameracontroller.settings.FLASH_MODE;
import ru.altarix.cameracontroller.settings.FOCUS_MODE;
import ru.altarix.cameracontroller.settings.photo.IMAGE_FORMAT;
import ru.altarix.cameracontroller.settings.photo.PhotoSettings;
import ru.altarix.cameracontroller.settings.video.AUDIO_ENCODER;
import ru.altarix.cameracontroller.settings.video.VIDEO_ENCODER;
import ru.altarix.cameracontroller.settings.video.VIDEO_QUALITY;
import ru.altarix.cameracontroller.settings.video.record.VideoRecordLimit;
import ru.altarix.cameracontroller.settings.video.record.VideoSettings;
import ru.altarix.commonutils.data.FileHelper;
import ru.altarix.commonutils.graphic.GraphicUtils;
import ru.altarix.tasksutils.taskrunnable.TaskRunnable;


@SuppressWarnings({"SynchronizeOnNonFinalField", "deprecation"})
public class CameraController {

    private static final Logger logger = LoggerFactory.getLogger(CameraController.class);

    private static CameraController mInstance;
    private final Context mContext;

    public static void initInstance(Context context) {
        if (mInstance == null) {
            logger.debug("initInstance()");
            synchronized (CameraController.class) {
                mInstance = new CameraController(context);
            }
        }
    }

    public static CameraController getInstance() {
        if (mInstance == null) {
            throw new IllegalStateException("initInstance() was not called");
        }
        return mInstance;
    }

    public static void releaseInstance() {
        if (mInstance != null) {
            synchronized (CameraController.class) {
                logger.debug("releaseInstance()");
                mInstance.releaseCameraController();
            }
        }
    }

    private CameraController(Context context) {
        mContext = context;
    }

    private void releaseCameraController() {

        releaseMakePreviewThreadPoolExecutor();

        if (cameraId != CAMERA_ID_NONE) {
            releaseCamera();
        }

        synchronized (surfaceHolderCallbacks) {
            surfaceHolderCallbacks.clear();
        }

        synchronized (cameraErrorListeners) {
            cameraErrorListeners.clear();
        }

        synchronized (mediaRecorderErrorListeners) {
            mediaRecorderErrorListeners.clear();
        }

        synchronized (photoReadyListeners) {
            photoReadyListeners.clear();
        }

        synchronized (recordLimitReachedListeners) {
            recordLimitReachedListeners.clear();
        }

        synchronized (videoPreviewReadyListeners) {
            videoPreviewReadyListeners.clear();
        }
    }

    private CAMERA_STATE currentCameraState = CAMERA_STATE.IDLE;

    public CAMERA_STATE getCurrentCameraState() {
        return currentCameraState;
    }

    private void setCurrentCameraState(CAMERA_STATE state) {
        if (state != null && state != currentCameraState) {
            currentCameraState = state;
            logger.info("STATE : " + currentCameraState);
        }
    }

    public boolean isCameraBusy() {
        return currentCameraState != CAMERA_STATE.IDLE;
    }

    public static Size findLowSize(List<Size> sizeList) {

        if (sizeList == null || sizeList.isEmpty()) {
            return null;
        }

        Size lowSize = sizeList.get(0);
        for (Size size : sizeList) {
            if (size.width < lowSize.width) {
                lowSize = size;
            }
        }
        return lowSize;
    }

    public static Size findMediumSize(List<Size> sizeList) {

        if (sizeList == null || sizeList.isEmpty()) {
            return null;
        }

        Size lowPreviewSize = findLowSize(sizeList);
        Size highPreviewSize = findHighSize(sizeList);

        int mediumWidth = (lowPreviewSize.width + highPreviewSize.width) / 2;

        Size mediumSize = sizeList.get(0);
        int mediumDiff = Math.abs(mediumSize.width - mediumWidth);

        int diff;
        for (Size size : sizeList) {
            diff = Math.abs(size.width - mediumWidth);
            if (diff < mediumDiff) {
                mediumDiff = diff;
                mediumSize = size;
            }
        }

        return mediumSize;
    }

    public static Size findHighSize(List<Size> sizeList) {

        if (sizeList == null || sizeList.isEmpty()) {
            return null;
        }

        Size highSize = sizeList.get(0);
        for (Size size : sizeList) {
            if (size.width > highSize.width) {
                highSize = size;
            }
        }
        return highSize;
    }

    public PhotoSettings getCurrentPhotoSettings() {

        if (camera != null) {

            if (!isCameraLocked()) {
                logger.error("can't get parameters: camera is not locked");
                return null;
            }

            final Camera.Parameters params;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()");
                return null;
            }

            try {
                return new PhotoSettings(IMAGE_FORMAT.fromNativeValue(params
                        .getPictureFormat()), params.getPictureSize(), params.getJpegQuality(),
                        FLASH_MODE.fromNativeValue(params.getFlashMode()), FOCUS_MODE.fromNativeValue(params.getFocusMode()),
                        COLOR_EFFECT.fromNativeValue(params.getColorEffect()));
            } catch (IllegalArgumentException e) {
                logger.error("an IllegalArgumentException occurred during fromNativeValue(): " + e.getMessage());
                return null;
            }
        }

        logger.error("camera is null");
        return null;
    }

    public boolean isPreviewSizeSupported(int width, int height) {

        if (camera == null) {
            logger.error("can't get parameters: camera is null");
            return false;
        }

        if (!isCameraLocked()) {
            logger.error("can't get parameters: camera is not locked");
            return false;
        }

        final List<Camera.Size> supportedPreviewSizes = getSupportedPreviewSizes();

        if (supportedPreviewSizes != null) {

            if (width > 0 && height > 0) {

                for (int i = 0; i < supportedPreviewSizes.size(); i++) {
                    if (width == supportedPreviewSizes.get(i).width && height == supportedPreviewSizes.get(i).height) {
                        return true;
                    }
                }

            }
        }

        return false;
    }

    public boolean isPictureSizeSupported(int width, int height) {

        if (camera == null) {
            logger.error("can't get parameters: camera is null");
            return false;
        }

        if (!isCameraLocked()) {
            logger.error("can't get parameters: camera is not locked");
            return false;
        }

        final List<Camera.Size> supportedPictureSizes = getSupportedPictureSizes();

        if (supportedPictureSizes != null) {

            if (width > 0 && height > 0) {

                for (int i = 0; i < supportedPictureSizes.size(); i++) {
                    if (width == supportedPictureSizes.get(i).width && height == supportedPictureSizes.get(i).height) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public PhotoSettings getLowPhotoSettings() {

        if (camera == null) {
            logger.error("can't get parameters: camera is null");
            return null;
        }

        if (!isCameraLocked()) {
            logger.error("can't get parameters: camera is not locked");
            return null;
        }

        final List<Size> supportedPictureSizes = getSupportedPictureSizes();

        if (supportedPictureSizes == null || supportedPictureSizes.isEmpty()) {
            logger.error("supportedPictureSizes is null or empty");
            return null;
        }

        StringBuilder picSizesStr = new StringBuilder();
        for (Size s : supportedPictureSizes)
            picSizesStr.append("[" + s.width + "x" + s.height + "]");
        logger.debug("_ supported picture sizes: " + picSizesStr);

        Size lowPictureSize = findLowSize(supportedPictureSizes);
        if (lowPictureSize != null) {
            logger.debug(" _ low picture size: " + lowPictureSize.width + "x" + lowPictureSize.height);
        } else {
            logger.error(" _ low picture size is null");
        }

        return new PhotoSettings(/* IMAGE_FORMAT.NV21, */IMAGE_FORMAT.JPEG, /* optimalPreviewSize, */lowPictureSize, 50, FLASH_MODE.AUTO,
                FOCUS_MODE.AUTO, COLOR_EFFECT.NONE);
    }

    public PhotoSettings getMediumPhotoSettings() {

        if (camera == null) {
            logger.error("can't get parameters: camera is null");
            return null;
        }

        if (!isCameraLocked()) {
            logger.error("can't get parameters: camera is not locked");
            return null;
        }

        final List<Camera.Size> supportedPictureSizes = getSupportedPictureSizes();

        if (supportedPictureSizes == null || supportedPictureSizes.isEmpty()) {
            logger.error("supportedPictureSizes is null or empty");
            return null;
        }

        StringBuilder picSizesStr = new StringBuilder();
        for (Size s : supportedPictureSizes)
            picSizesStr.append("[" + s.width + "x" + s.height + "]");
        logger.debug("_ supported picture sizes: " + picSizesStr);

        final Size mediumPictureSize = findMediumSize(supportedPictureSizes);
        if (mediumPictureSize != null) {
            logger.debug(" _ medium picture size: " + mediumPictureSize.width + "x" + mediumPictureSize.height);
        } else {
            logger.error(" _ medium picture size is null");
        }

        return new PhotoSettings(/* IMAGE_FORMAT.NV21, */IMAGE_FORMAT.JPEG, /* optimalPreviewSize, */mediumPictureSize, 85,
                FLASH_MODE.AUTO, FOCUS_MODE.AUTO, COLOR_EFFECT.NONE);
    }

    public PhotoSettings getHighPhotoSettings() {

        if (camera == null) {
            logger.error("can't get parameters: camera is null");
            return null;
        }

        if (!isCameraLocked()) {
            logger.error("can't get parameters: camera is not locked");
            return null;
        }

        final List<Camera.Size> supportedPictureSizes = getSupportedPictureSizes();

        if (supportedPictureSizes == null || supportedPictureSizes.isEmpty()) {
            logger.error("supportedPictureSizes is null or empty");
            return null;
        }

        StringBuilder picSizesStr = new StringBuilder();
        for (Size s : supportedPictureSizes)
            picSizesStr.append("[" + s.width + "x" + s.height + "]");
        logger.debug("_ supported picture sizes: " + picSizesStr);

        Size highPictureSize = findHighSize(supportedPictureSizes);
        if (highPictureSize != null) {
            logger.debug(" _ high picture size: " + highPictureSize.width + "x" + highPictureSize.height);
        } else {
            logger.error(" _ high picture size is null");
        }

        return new PhotoSettings(/* IMAGE_FORMAT.NV21, */IMAGE_FORMAT.JPEG, /* optimalPreviewSize, */highPictureSize, 100, FLASH_MODE.AUTO,
                FOCUS_MODE.AUTO, COLOR_EFFECT.NONE);
    }

    private SurfaceView cameraSurfaceView;

    public SurfaceView getCameraSurfaceView() {
        return cameraSurfaceView;
    }

    private boolean enableChangeSurfaceViewSize = false;
    private boolean isFullscreenSurfaceViewSize = false;

    public void enableChangeSurfaceViewSize(boolean enable, boolean fullscreen /* , Handler uiHandler */) {
        // this.uiHandler = uiHandler;

        enableChangeSurfaceViewSize = enable;
        isFullscreenSurfaceViewSize = fullscreen;
    }

    private boolean isSurfaceCreated = false;

    /**
     * indicates whether surface is fully initialized
     */
    public boolean isSurfaceCreated() {
        return (cameraSurfaceView != null && isSurfaceCreated && !cameraSurfaceView.getHolder().isCreating() && cameraSurfaceView.getHolder().getSurface() != null);
    }

    private int surfaceWidth = 0;
    private int surfaceHeight = 0;

    private final LinkedList<SurfaceHolder.Callback> surfaceHolderCallbacks = new LinkedList<SurfaceHolder.Callback>();

    public CameraController addSurfaceHolderCallback(SurfaceHolder.Callback callback) throws NullPointerException {

        if (callback == null)
            throw new NullPointerException();

        synchronized (surfaceHolderCallbacks) {
            if (!surfaceHolderCallbacks.contains(callback)) {
                surfaceHolderCallbacks.add(callback);
            }
        }

        // if (cameraSurfaceView != null) {
        // cameraSurfaceView.getHolder().addCallback(callback);
        // }

        return this;
    }

    public CameraController removeSurfaceHolderCallback(SurfaceHolder.Callback callback) {

        synchronized (surfaceHolderCallbacks) {
            if (surfaceHolderCallbacks.contains(callback))
                surfaceHolderCallbacks.remove(callback);
        }

        // if (cameraSurfaceView != null) {
        // cameraSurfaceView.getHolder().removeCallback(callback);
        // }

        return this;
    }

    public final static int DEFAULT_PREVIEW_CALLBACK_BUFFER_QUEUE_SIZE = 3;
    private int callbackBufferQueueSize = DEFAULT_PREVIEW_CALLBACK_BUFFER_QUEUE_SIZE;

    public boolean isPreviewCallbackBufferUsing() {
        return callbackBufferQueueSize > 0;
    }

    public int getPreviewCallbackBufferQueueSize() {
        return callbackBufferQueueSize;
    }

    public boolean usePreviewCallbackWithBuffer(boolean use, int queueSize) {
        logger.debug("usePreviewCallbackWithBuffer(), use=" + use + ", queueSize=" + queueSize);

        if (queueSize != callbackBufferQueueSize) {

            if (isMediaRecorderRecording) {
                logger.error("can't change preview callback buffer size: media recorder is recording");
                return false;
            }

            if (!use) {
                callbackBufferQueueSize = 0;
            } else {
                if (queueSize > 0)
                    callbackBufferQueueSize = queueSize;
            }

            if (isPreviewStated) {
                stopPreview();
                startPreview();
            }

            setPreviewCallback();
        }

        return true;
    }

    private int expectedCallbackBufSize = 0;

    private byte[] allocatePreviewCallbackBuffer() {

        IMAGE_FORMAT previewFormat = getCameraPreviewFormat();

        if (previewFormat == null) {
            logger.error("can't get previewFormat");
            return null;
        }

        Camera.Size previewSize = getCameraPreviewSize();

        if (previewSize == null) {
            logger.error("can't get previewSize");
            return null;
        }

        if (previewFormat != IMAGE_FORMAT.YV12) {

            expectedCallbackBufSize = previewSize.width * previewSize.height * ImageFormat.getBitsPerPixel(previewFormat.getValue()) / 8;

        } else {

            int yStride = (int) Math.ceil(previewSize.width / 16.0) * 16;
            int uvStride = (int) Math.ceil((yStride / 2) / 16.0) * 16;
            int ySize = yStride * previewSize.height;
            int uvSize = uvStride * previewSize.height / 2;
            expectedCallbackBufSize = ySize + uvSize * 2;
        }

        // logger.debug("preview callback byte buffer size: " + expectedCallbackBufSize);
        return new byte[expectedCallbackBufSize];
    }

    /**
     * maybe better invoke this after startPreview() ?
     */
    private void setPreviewCallback() {
        if (camera != null && isCameraLocked()) {
            synchronized (camera) {
                camera.setPreviewCallback(null);
                if (callbackBufferQueueSize > 0) {
                    for (int i = 0; i < callbackBufferQueueSize; i++)
                        camera.addCallbackBuffer(allocatePreviewCallbackBuffer());
                    logger.debug("setting preview callback with buffer...");
                    camera.setPreviewCallbackWithBuffer(previewCallback);
                } else {
                    expectedCallbackBufSize = 0;
                    logger.debug("setting preview callback...");
                    camera.setPreviewCallback(previewCallback);
                }
            }
        }
    }

    private CameraSurfaceHolderCallback cameraSurfaceHolderCallback;

    private class CameraSurfaceHolderCallback implements SurfaceHolder.Callback {

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            logger.debug("SurfaceHolderCallback :: surfaceCreated()");

            if (camera != null && holder != null) {

                try {
                    camera.setPreviewDisplay(holder);
                } catch (IOException e) {
                    logger.error("an IOException occurred during setPreviewDisplay()", e);
                }

                // startPreview();
            }

            isSurfaceCreated = true;

            synchronized (surfaceHolderCallbacks) {
                if (surfaceHolderCallbacks.size() > 0) {
                    for (SurfaceHolder.Callback l : surfaceHolderCallbacks) {
                        l.surfaceCreated(holder);
                    }
                }
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            logger.debug("SurfaceHolderCallback :: surfaceChanged(), format=" + format + ", width=" + width + ", height=" + height);

            surfaceWidth = width;
            surfaceHeight = height;

            if (camera != null && isCameraLocked()) {

                stopPreview();

                setCameraDisplayOrientation();

                setCameraPreviewSize(getOptimalPreviewSize(getSupportedPreviewSizes(), surfaceWidth, surfaceHeight));

                if (enableChangeSurfaceViewSize)
                    setSurfaceViewSize(isFullscreenSurfaceViewSize, 0, cameraSurfaceView);

                startPreview();
                setPreviewCallback();
            }

            synchronized (surfaceHolderCallbacks) {
                if (surfaceHolderCallbacks.size() > 0) {
                    for (SurfaceHolder.Callback l : surfaceHolderCallbacks) {
                        l.surfaceChanged(holder, format, width, height);
                    }
                }
            }

        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            logger.debug("SurfaceHolderCallback :: surfaceDestroyed()");

            surfaceWidth = 0;
            surfaceHeight = 0;

            isSurfaceCreated = false;

            stopPreview();

            if (camera != null) {
                synchronized (camera) {
                    camera.setErrorCallback(null);
                    camera.setPreviewCallback(null);
                }
            }

            synchronized (surfaceHolderCallbacks) {
                if (surfaceHolderCallbacks.size() > 0) {
                    for (SurfaceHolder.Callback l : surfaceHolderCallbacks) {
                        l.surfaceDestroyed(holder);
                    }
                }
            }
        }

    }

    private void setSurfaceViewSize(boolean fullScreen, float scale, SurfaceView surfaceView) {
        logger.debug("setSurfaceViewSize(), fullScreen=" + fullScreen + ", scale=" + scale + ", surfaceView=" + surfaceView);

        if (surfaceView == null) {
            logger.error("surfaceView is null");
            return;
        }

        final LayoutParams layoutParams = surfaceView.getLayoutParams();

        if (layoutParams == null) {
            logger.error("layoutParams is null");
            return;
        }

        final Camera.Size previewSize = getCameraPreviewSize();

        if (previewSize == null) {
            logger.error("previewSize is null");
            return;
        }

        DisplayMetrics displaymetrics = new DisplayMetrics();
        ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getMetrics(displaymetrics);
        final int screenWidth = displaymetrics.widthPixels;
        final int screenHeight = displaymetrics.heightPixels;

        boolean widthIsMax = screenWidth > screenHeight;

        RectF rectDisplay = new RectF();
        RectF rectPreview = new RectF();

        rectDisplay.set(0, 0, screenWidth, screenHeight);

        if (widthIsMax) {
            // preview in horizontal orientation
            rectPreview.set(0, 0, previewSize.width, previewSize.height);
        } else {
            // preview in vertical orientation
            rectPreview.set(0, 0, previewSize.height, previewSize.width);
        }

        Matrix matrix = new Matrix();
        if (!fullScreen) {
            matrix.setRectToRect(rectPreview, rectDisplay, Matrix.ScaleToFit.START);
        } else {
            matrix.setRectToRect(rectDisplay, rectPreview, Matrix.ScaleToFit.START);
            matrix.invert(matrix);
        }
        matrix.mapRect(rectPreview);

        if (!fullScreen) {

            if (scale <= 0) {
                scale = mContext.getResources().getDisplayMetrics().density; // = X dpi / 160 dpi
            }

            if (scale > 1) {
                layoutParams.height = (int) (rectPreview.bottom / scale);
                layoutParams.width = (int) (rectPreview.right / scale);
            } else if (scale == 1) {
                layoutParams.height = (int) (rectPreview.bottom / 2);
                layoutParams.width = (int) (rectPreview.right / 2);
            } else {
                layoutParams.height = (int) (rectPreview.bottom * scale);
                layoutParams.width = (int) (rectPreview.right * scale);
            }

        } else {
            layoutParams.height = (int) (rectPreview.bottom);
            layoutParams.width = (int) (rectPreview.right);
        }

        surfaceView.setLayoutParams(layoutParams);
    }

    public static int calculateCameraDisplayOrientation(int cameraId, Context ctx) {

        int degrees = 0;
        final int rotation = ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }
        logger.debug("display rotation degrees: " + degrees);

        CameraInfo cameraInfo = new CameraInfo();
        Camera.getCameraInfo(cameraId, cameraInfo);

        int result = 0;

        if (cameraInfo.facing == CameraInfo.CAMERA_FACING_BACK) {
            logger.debug("camera facing: back");
            result = ((360 - degrees) + cameraInfo.orientation);
        } else if (cameraInfo.facing == CameraInfo.CAMERA_FACING_FRONT) {
            logger.debug("camera facing: front");
            result = ((360 - degrees) - cameraInfo.orientation);
            result += 360;
        }
        result = result % 360;

        logger.debug("result rotation degrees: " + result);
        return result;
    }

    private void setCameraDisplayOrientation() {
        logger.debug("setCameraDisplayOrientation()");

        final int resultDegrees = calculateCameraDisplayOrientation(cameraId, mContext);

        if (camera != null && isCameraLocked()) {
            synchronized (camera) {
                camera.setDisplayOrientation(resultDegrees);

                try {
                    final Parameters params = camera.getParameters();
                    params.setRotation(resultDegrees);
                    camera.setParameters(params);
                } catch (RuntimeException e) {
                    logger.error("a RuntimeException occurred during get/set camera parameters", e);
                }
            }
        }

        if (mediaRecorder != null && !isMediaRecorderRecording) {
            mediaRecorder.setOrientationHint(resultDegrees);
        }

    }

    public static interface OnCameraErrorListener {
        void onCameraError(int error);
    }

    private final LinkedList<OnCameraErrorListener> cameraErrorListeners = new LinkedList<OnCameraErrorListener>();

    public CameraController addCameraErrorListener(OnCameraErrorListener listener) throws NullPointerException {

        if (listener == null) {
            throw new NullPointerException();
        }

        synchronized (cameraErrorListeners) {
            if (!cameraErrorListeners.contains(listener)) {
                cameraErrorListeners.add(listener);
            }
        }

        return this;
    }

    public CameraController removeCameraErrorListener(OnCameraErrorListener listener) {
        synchronized (cameraErrorListeners) {
            if (cameraErrorListeners.contains(listener)) {
                cameraErrorListeners.remove(listener);
            }
        }

        return this;
    }

    private Location lastLocation;

    public Location getLastLocation() {
        return lastLocation;
    }

    public void updateLastLocation(Location loc) {
        this.lastLocation = loc;
    }

    private Camera camera;

    private boolean createCamera(int cameraId, SurfaceView surfaceView, boolean setMaxPreviewFps, boolean setRgbPreviewFormat,
                                 boolean enableVideoStabilization) {

        if (camera != null) {
            logger.error("camera is already opened");
            return false;
        }

        if (!isCameraIdValid(cameraId)) {
            logger.error("incorrect camera id: " + cameraId);
            return false;
        }

        if (surfaceView == null) {
            logger.error("surfaceView is null");
            return false;
        }

        logger.debug("opening camera " + cameraId + "...");
        try {
            camera = Camera.open(cameraId);
        } catch (RuntimeException e) {
            logger.error("a RuntimeException occurred during open()", e);
            return false;
        }

        if (camera == null) {
            logger.error("open camera failed");
            return false;
        }

        camera.setErrorCallback(new ErrorCallback() {

            @Override
            public void onError(int error, Camera camera) {
                logger.error("onError(), error=" + error + ", camera=" + camera);

                switch (error) {
                    case Camera.CAMERA_ERROR_SERVER_DIED:
                        logger.error("camera server died");
                        break;
                    case Camera.CAMERA_ERROR_UNKNOWN:
                        logger.error("camera unknown error");
                        break;
                }

                releaseCamera();

                synchronized (cameraErrorListeners) {
                    if (cameraErrorListeners.size() > 0) {
                        for (OnCameraErrorListener l : cameraErrorListeners) {
                            l.onCameraError(error);
                        }
                    }
                }
            }
        });

        CameraController.this.cameraId = cameraId;
        CameraController.this.cameraSurfaceView = surfaceView;

        final SurfaceHolder cameraSurfaceHolder = surfaceView.getHolder();

        if (cameraSurfaceHolder == null) {
            logger.error("invalid surface, holder is null");
            return false;
        }

        if (setMaxPreviewFps)
            setCameraPreviewMaxFpsRange();

        if (setRgbPreviewFormat)
            setCameraPreviewFormat(IMAGE_FORMAT.RGB_565);

        if (enableVideoStabilization)
            setCameraVideoStabilization(true);

        // setCameraParameters(getMediumPhotoSettings());

        previewCallback.updatePreviewFormat(getCameraPreviewFormat());
        previewCallback.updatePreviewSize(getCameraPreviewSize());

        if (cameraSurfaceHolderCallback != null) {
            cameraSurfaceHolder.removeCallback(cameraSurfaceHolderCallback);
            cameraSurfaceHolderCallback = null;
        }

        cameraSurfaceHolderCallback = new CameraSurfaceHolderCallback();
        cameraSurfaceHolder.addCallback(cameraSurfaceHolderCallback);

        if (isSurfaceCreated()) {

            logger.debug("surface has been already created before!");

            setCameraDisplayOrientation();

            setCameraPreviewSize(getOptimalPreviewSize(getSupportedPreviewSizes(), surfaceWidth, surfaceHeight));

            try {
                camera.setPreviewDisplay(cameraSurfaceHolder);
            } catch (IOException e) {
                logger.error("an IOException occurred during setPreviewDisplay()", e);
            }

            startPreview();
            setPreviewCallback();
        }

        return true;
    }

    private Looper cameraLooper;
    private CameraThread cameraThread;

    private class CameraThread extends Thread {

        private boolean openResult = false;

        public boolean getOpenResult() {
            return openResult;
        }

        private void setOpenResult(boolean openResult) {

            this.openResult = openResult;

            if (lock != null) {
                lock.release();
            }

            logger.debug("entering loop...");
            Looper.loop(); // hangs on this call
            logger.debug("exiting loop...");
        }

        private final int cameraId;
        private final SurfaceView surfaceView;
        private final boolean setMaxFps;
        private final boolean setRgbPreviewFormat;
        private final boolean enableVideoStabilization;

        private final Semaphore lock;

        private CameraThread(int cameraId, SurfaceView surfaceView, boolean setMaxFps, boolean setRgbPreviewFormat,
                             boolean enableVideoStabilization, Semaphore lock) {
            this.cameraId = cameraId;
            this.surfaceView = surfaceView;
            this.setMaxFps = setMaxFps;
            this.setRgbPreviewFormat = setRgbPreviewFormat;
            this.enableVideoStabilization = enableVideoStabilization;
            this.lock = lock;
        }

        @Override
        public void run() {

            Looper.prepare();
            cameraLooper = Looper.myLooper();

            setOpenResult(createCamera(cameraId, surfaceView, setMaxFps, setRgbPreviewFormat, enableVideoStabilization));
        }
    }

    public final static int CAMERA_ID_NONE = -1;
    public final static int CAMERA_ID_BACK = Camera.CameraInfo.CAMERA_FACING_BACK;
    public final static int CAMERA_ID_FRONT = Camera.CameraInfo.CAMERA_FACING_FRONT;

    public static boolean isCameraIdValid(int cameraId) {
        // return (cameraId >= 0 && cameraId < Camera.getNumberOfCameras());

        final CameraInfo cameraInfo = new CameraInfo();

        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == cameraId) {
                return true;
            }
        }

        return false;
    }

    private int cameraId = CAMERA_ID_NONE;

    public boolean isCameraOpened() {
        return cameraId != CAMERA_ID_NONE;
    }

    public int getOpenedCameraId() {
        return cameraId;
    }

    private boolean isCameraLocked = true;

    public boolean isCameraLocked() {
        return (camera != null && isCameraLocked);
    }

    /**
     * should reconnect to camera object after lock()
     */
    private boolean lockCamera() {
        if (camera != null) {

            if (isCameraLocked())
                return true;

            synchronized (camera) {
                logger.debug("locking camera...");
                try {
                    camera.lock();
                    isCameraLocked = true;
                    return true;
                } catch (Exception e) {
                    logger.error("an Exception occurred during lock()", e);
                }
            }
        }
        return false;
    }

    /**
     * all actions with camera (e.g. getParameters() and setParameters()) will be failed after unlock()!
     */
    private boolean unlockCamera() {
        if (camera != null) {

            if (!isCameraLocked())
                return true;

            synchronized (camera) {
                logger.debug("unlocking camera...");
                try {
                    camera.unlock();
                    isCameraLocked = false;
                    return true;
                } catch (Exception e) {
                    logger.error("an Exception occurred during unlock()", e);
                }
            }
        }
        return false;
    }

    private boolean isPreviewStated = false;

    /**
     * preview callbacks in camera will be resetted
     */
    private boolean startPreview() {
        if (!isPreviewStated) {
            if (camera != null && isCameraLocked()) {
                synchronized (camera) {
                    logger.debug("starting preview...");
                    try {
                        camera.startPreview();
                        isPreviewStated = true;
                        previewCallback.notifyPreviewStarted();
                        return true;
                    } catch (RuntimeException e) {
                        logger.error("a RuntimeException occurred during startPreview()", e);
                    }
                }
            }
        } else {
            return true;
        }
        return false;
    }

    private boolean stopPreview() {
        if (isPreviewStated) {
            if (camera != null && isCameraLocked()) {
                synchronized (camera) {
                    logger.debug("stopping preview...");
                    try {
                        camera.stopPreview();
                        isPreviewStated = false;
                        // previewCallback.resetFpsCounter();
                        return true;
                    } catch (RuntimeException e) {
                        logger.error("a RuntimeException occurred during stopPreview()", e);
                    }
                }
            }
        } else {
            return true;
        }
        return false;
    }

    /**
     * for the first time must be called at onCreate() or onResume() handling surfaceCreated(), surfaceChanged() and
     * surfaceDestroyed() callbacks
     */
    public synchronized boolean openCamera(int cameraId, SurfaceView surfaceView, boolean setMaxFps, boolean setRgbPreviewFormat, boolean enableVideoStabilization) {
        logger.debug("openCamera(), cameraId=" + cameraId + ", setMaxFps=" + setMaxFps + ", setRgbPreviewFormat=" + setRgbPreviewFormat
                + ", enableVideoStabilization=" + enableVideoStabilization);

        if (cameraThread != null && cameraThread.isAlive()) {
            logger.error("cameraThread is already running");
            return false;
        }

        // can use new HandlerThread or Looper in new Thread
        // or execute open() on the main thread (but it could reduce performance, including
        // onPreviewFrame() calls)

        final Semaphore lock = new Semaphore(0);

        cameraThread = new CameraThread(cameraId, surfaceView, setMaxFps, setRgbPreviewFormat, enableVideoStabilization, lock);
        cameraThread.setName(CameraThread.class.getSimpleName());
        cameraThread.start();

        lock.acquireUninterruptibly();

        final boolean openResult = cameraThread.getOpenResult();

        if (!openResult && cameraLooper != null) {
            cameraLooper.quit();
        }

        return openResult;
        // return createCamera(cameraId, surfaceView);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final static int EXECUTOR_CALL_TIMEOUT = 10;

    public boolean releaseCamera() {
        logger.debug("releaseCamera()");

        if (camera == null) {
            logger.debug("camera is already null");
            return true;
        }

        if (isMediaRecorderRecording) {
            if (stopRecordVideo() == null) {
                logger.error("stopRecordVideo() failed");
                return false;
            }
        }

        // if (cameraSurfaceHolderCallback != null) {
        // cameraSurfaceView.getHolder().removeCallback(cameraSurfaceHolderCallback);
        // cameraSurfaceHolderCallback = null;
        // }

        synchronized (camera) {

            stopPreview();

            camera.setErrorCallback(null);
            camera.setPreviewCallback(null);
            // camera.setPreviewDisplay(null);

            logger.debug("releasing camera " + cameraId + "...");
            camera.release();

            camera = null;
            cameraId = CAMERA_ID_NONE;

            // cameraSurfaceView = null;

            if (cameraLooper != null) {
                cameraLooper.quit();
                cameraLooper = null;
            }

            previewCallback.updatePreviewFormat(null);
            previewCallback.updatePreviewSize(null);

            return true;
        }
    }

    private int previousStreamVolume = 0;

    @SuppressLint("NewApi")
    private void muteSound(boolean mute) {
        if (isMuteSoundEnabled) {
            AudioManager mgr = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            // mgr.setStreamMute(AudioManager.STREAM_SYSTEM, mute);
            if (mute) {
                previousStreamVolume = mgr.getStreamVolume(AudioManager.STREAM_SYSTEM);
                mgr.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
            } else {
                mgr.setStreamVolume(AudioManager.STREAM_SYSTEM, previousStreamVolume, 0);
            }

            if (android.os.Build.VERSION.SDK_INT >= 17) {
                try {
                    if (camera != null && isCameraLocked()) {
                        synchronized (camera) {
                            camera.enableShutterSound(!mute);
                        }
                    }
                } catch (RuntimeException e) {
                    logger.error("a RuntimeException occurred during enableShutterSound()", e);
                }
            }
        }
    }

    private boolean isMuteSoundEnabled = false;

    public void enableMuteSound(boolean enable) {
        isMuteSoundEnabled = enable;
    }

    public int getMaxZoom() {

        if (currentCameraState != CAMERA_STATE.IDLE) {
            logger.error("incorrect currentCameraState: " + currentCameraState);
            return NOT_SUPPORTED_ZOOM;
        }

        if (camera == null) {
            logger.error("camera is null");
            return NOT_SUPPORTED_ZOOM;
        }

        if (!isCameraLocked()) {
            logger.error("can't get/set parameters: camera is not locked");
            return NOT_SUPPORTED_ZOOM;
        }

        synchronized (camera) {
            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
                return NOT_SUPPORTED_ZOOM;
            }

            if (!params.isZoomSupported()) {
                logger.error("zoom is not supported");
                return NOT_SUPPORTED_ZOOM;
            }

            return params.getMaxZoom();
        }
    }

    public int getCurrentZoom() {

        if (currentCameraState != CAMERA_STATE.IDLE) {
            logger.error("incorrect currentCameraState: " + currentCameraState);
            return NOT_SUPPORTED_ZOOM;
        }

        if (camera == null) {
            logger.error("camera is null");
            return NOT_SUPPORTED_ZOOM;
        }

        if (!isCameraLocked()) {
            logger.error("can't get/set parameters: camera is not locked");
            return NOT_SUPPORTED_ZOOM;
        }

        synchronized (camera) {
            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
                return NOT_SUPPORTED_ZOOM;
            }

            if (!params.isZoomSupported()) {
                logger.error("zoom is not supported");
                return NOT_SUPPORTED_ZOOM;
            }

            return params.getZoom();
        }
    }

    public static final int NOT_SUPPORTED_ZOOM = -1;
    public static final int MIN_ZOOM = 0;

    public boolean setCurrentZoom(int zoom) {

        if (currentCameraState != CAMERA_STATE.IDLE) {
            logger.error("incorrect currentCameraState: " + currentCameraState);
            return false;
        }

        if (camera == null) {
            logger.error("camera is null");
            return false;
        }

        if (!isCameraLocked()) {
            logger.error("can't get/set parameters: camera is not locked");
            return false;
        }

        synchronized (camera) {
            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            if (zoom < 0 || zoom > params.getMaxZoom()) {
                logger.error("incorrect zoom level: " + zoom);
                return false;
            }

            params.setZoom(zoom);

            try {
                camera.setParameters(params);
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during setParameters()", e);
                return false;
            }

            return true;
        }
    }

    public List<Camera.Size> getSupportedPreviewSizes() {
        try {
            if (camera != null && isCameraLocked()) {
                synchronized (camera) {
                    return camera.getParameters().getSupportedPreviewSizes();
                }
            }
        } catch (RuntimeException e) {
            logger.error("a RuntimeException occurred during getParameters()", e);
        }
        return null;
    }

    public List<int[]> getSupportedPreviewFpsRanges() {
        try {
            if (camera != null && isCameraLocked()) {
                synchronized (camera) {
                    return camera.getParameters().getSupportedPreviewFpsRange();
                }
            }
        } catch (RuntimeException e) {
            logger.error("a RuntimeException occurred during getParameters()", e);
        }
        return null;
    }

    public List<Integer> getSupportedPreviewFormats() {
        try {
            if (camera != null && isCameraLocked()) {
                synchronized (camera) {
                    return camera.getParameters().getSupportedPreviewFormats();
                }
            }
        } catch (RuntimeException e) {
            logger.error("a RuntimeException occurred during getParameters()", e);
        }
        return null;
    }

    public List<Integer> getSupportedPictureFormats() {
        try {
            if (camera != null && isCameraLocked()) {
                synchronized (camera) {
                    return camera.getParameters().getSupportedPictureFormats();
                }
            }
        } catch (RuntimeException e) {
            logger.error("a RuntimeException occurred during getParameters()", e);
        }
        return null;
    }

    public List<Camera.Size> getSupportedPictureSizes() {
        try {
            if (camera != null && isCameraLocked()) {
                synchronized (camera) {
                    return camera.getParameters().getSupportedPictureSizes();
                }
            }
        } catch (RuntimeException e) {
            logger.error("a RuntimeException occurred during getParameters()", e);
        }
        return null;
    }

    public List<String> getSupportedFlashModes() {
        try {
            if (camera != null && isCameraLocked()) {
                synchronized (camera) {
                    return camera.getParameters().getSupportedFlashModes();
                }
            }
        } catch (RuntimeException e) {
            logger.error("a RuntimeException occurred during getParameters()", e);
        }
        return null;
    }

    public List<String> getSupportedColorEffects() {
        try {
            if (camera != null && isCameraLocked()) {
                synchronized (camera) {
                    return camera.getParameters().getSupportedColorEffects();
                }
            }
        } catch (RuntimeException e) {
            logger.error("a RuntimeException occurred during getParameters()", e);
        }
        return null;
    }

    public static Camera.Size getOptimalPreviewSize(List<Camera.Size> sizes, int w, int h) {
        final double ASPECT_TOLERANCE = 0.05;
        double targetRatio = (double) w / h;
        if (sizes == null)
            return null;

        Camera.Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        int targetHeight = h;

        // Try to find an size match aspect ratio and size
        for (Camera.Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE)
                continue;
            if (Math.abs(size.height - targetHeight) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }

        // Cannot find the one match the aspect ratio, ignore the requirement
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Camera.Size size : sizes) {
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
        return optimalSize;
    }

    public Camera.Size getCameraPreviewSize() {

        if (camera == null) {
            logger.error("camera is null");
            return null;
        }

        if (!isCameraLocked()) {
            logger.error("can't get parameters: camera is not locked");
            return null;
        }

        synchronized (camera) {
            final Parameters params;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
                return null;
            }

            return params.getPreviewSize();
        }
    }

    private boolean setCameraPreviewSize(Camera.Size size) {
        logger.debug("setCameraPreviewSize(), size=" + size);

        if (size == null) {
            logger.error("size is null");
            return false;
        }

        return setCameraPreviewSize(size.width, size.height);
    }

    private boolean setCameraPreviewSize(int width, int height) { // , boolean restartPreview
        logger.debug("setCameraPreviewSize(), width=" + width + ", height=" + height); // + ", restartPreview=" +
        // restartPreview);

        if (isMediaRecorderRecording) {
            logger.error("can't set preview size: media recorder is recording");
            return false;
        }

        if (width <= 0 || height <= 0) {
            logger.error("incorrect size: " + width + "x" + height);
            return false;
        }

        if (camera == null) {
            logger.error("camera is null");
            return false;
        }

        if (!isCameraLocked()) {
            logger.error("can't get/set parameters: camera is not locked");
            return false;
        }

        synchronized (camera) {

            final Parameters params;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            boolean isPreviewSizeSupported = false;

            final List<Size> supportedPreviewSizes = params.getSupportedPreviewSizes();

            if (supportedPreviewSizes != null && supportedPreviewSizes.size() != 0) {
                for (int i = 0; i < supportedPreviewSizes.size(); i++) {

                    if ((supportedPreviewSizes.get(i).width == width) && (supportedPreviewSizes.get(i).height == height)) {
                        isPreviewSizeSupported = true;
                        break;
                    }
                }
            } else {
                logger.error("no supported preview sizes for this camera");
            }

            if (!isPreviewSizeSupported) {
                logger.error(" _ preview size " + width + "x" + height + " is NOT supported");
                return false;

            } else {
                logger.debug(" _ preview size " + width + "x" + height + " is supported");
                params.setPreviewSize(width, height);
            }

            try {
                camera.setParameters(params);
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during setParameters()", e);
                return false;
            }

            previewCallback.updatePreviewSize(width, height);

            if (isPreviewStated) {
                // restart preview and reset callback
                stopPreview();
                startPreview();
                setPreviewCallback();
            }

            return true;
        }
    }

    public IMAGE_FORMAT getCameraPreviewFormat() {

        if (camera == null) {
            logger.error("camera is null");
            return null;
        }

        if (!isCameraLocked()) {
            logger.error("can't get parameters: camera is not locked");
            return null;
        }

        synchronized (camera) {

            final Parameters params;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
                return null;
            }

            return IMAGE_FORMAT.fromNativeValue(params.getPreviewFormat());
        }
    }

    private boolean setCameraPreviewFormat(IMAGE_FORMAT previewFormat) { // , boolean restartPreview
        logger.debug("setCameraPreviewFormat(), previewFormat=" + previewFormat); // + ", restartPreview=" +
        // restartPreview);

        if (isMediaRecorderRecording) {
            logger.error("can't set preview format: media recorder is recording");
            return false;
        }

        if (previewFormat == null) {
            logger.error("previewFormat is null");
            return false;
        }

        if (camera == null) {
            logger.error("camera is null");
            return false;
        }

        if (!isCameraLocked()) {
            logger.error("can't get/set parameters: camera is not locked");
            return false;
        }

        synchronized (camera) {

            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            boolean isPreviewFormatSupported = false;

            final List<Integer> supportedPreviewFormats = params.getSupportedPreviewFormats();
            if (supportedPreviewFormats != null && supportedPreviewFormats.size() != 0) {
                for (int i = 0; i < supportedPreviewFormats.size(); i++) {
                    if (previewFormat.getValue() == supportedPreviewFormats.get(i)) {
                        isPreviewFormatSupported = true;
                        break;
                    }
                }
            } else {
                logger.error("no supported preview formats for this camera");
            }

            if (!isPreviewFormatSupported) {
                logger.error(" _ preview format " + previewFormat + " is NOT supported");
            } else {
                logger.debug(" _ preview format " + previewFormat + " is supported");
                params.setPreviewFormat(previewFormat.getValue());
            }

            try {
                camera.setParameters(params);
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during setParameters()", e);
                return false;
            }

            previewCallback.updatePreviewFormat(previewFormat);

            if (isPreviewStated) {
                // restart preview and reset callback
                stopPreview();
                startPreview();
                setPreviewCallback();
            }

            return true;
        }
    }

    /**
     * @return range the minimum and maximum preview fps
     */
    public double[] getCameraPreviewFpsRange() {

        if (camera == null) {
            logger.error("camera is null");
            return null;
        }

        if (!isCameraLocked()) {
            logger.error("can't get parameters: camera is not locked");
            return null;
        }

        synchronized (camera) {

            final Parameters params;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
                return null;
            }

            int[] scaledRange = new int[2];
            params.getPreviewFpsRange(scaledRange);
            double[] normalRange = new double[]{scaledRange[0] / 1000, scaledRange[1] / 1000};
            return normalRange;
        }
    }

    /**
     * @param minFps required minimum preview frame rate
     * @param maxFps required maximum preview frame rate
     */
    private boolean setCameraPreviewFpsRange(double minFps, double maxFps) {
        logger.debug("setCameraPreviewFpsRange(), minFps=" + minFps + ", maxFps=" + maxFps);

        if (minFps == 0 || maxFps == 0 || minFps > maxFps) {
            logger.error("incorrect fps range: " + minFps + " .. " + maxFps);
            return false;
        }

        if (camera == null) {
            logger.error("camera is null");
            return false;
        }

        if (!isCameraLocked()) {
            logger.error("can't get/set parameters: camera is not locked");
            return false;
        }

        synchronized (camera) {

            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            final int minFpsScaled = (int) (minFps * 1000);
            final int maxFpsScaled = (int) (maxFps * 1000);

            boolean isPreviewFpsRangeSupported = false;

            final List<int[]> supportedPreviewFpsRanges = params.getSupportedPreviewFpsRange();

            if (supportedPreviewFpsRanges == null || supportedPreviewFpsRanges.isEmpty()) {
                logger.error("no supported preview fps ranges for this camera");
                return false;
            }

            for (int i = 0; i < supportedPreviewFpsRanges.size(); i++) {

                final int supportedMinFpsScaled = supportedPreviewFpsRanges.get(i)[Parameters.PREVIEW_FPS_MIN_INDEX];
                final int supportedMaxFpsScaled = supportedPreviewFpsRanges.get(i)[Parameters.PREVIEW_FPS_MAX_INDEX];

                if (minFpsScaled >= supportedMinFpsScaled && minFpsScaled <= supportedMaxFpsScaled && maxFpsScaled >= supportedMinFpsScaled
                        && maxFpsScaled <= supportedMaxFpsScaled) {
                    isPreviewFpsRangeSupported = true;
                    break;
                }
            }

            if (!isPreviewFpsRangeSupported) {
                logger.error(" _ FPS range " + minFps + " .. " + maxFps + " is NOT supported");
                return false;
            } else {
                logger.debug(" _ FPS range " + minFps + " .. " + maxFps + " is supported");
                params.setPreviewFpsRange(minFpsScaled, maxFpsScaled);
            }

            try {
                camera.setParameters(params);
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during setParameters()", e);
                return false;
            }

            if (isPreviewStated) {
                // restart preview and reset callback
                stopPreview();
                startPreview();
                setPreviewCallback();
            }

            return true;
        }
    }

    private boolean setCameraPreviewMaxFpsRange() {

        List<int[]> supportedPreviewFpsRanges = getSupportedPreviewFpsRanges();

        if (supportedPreviewFpsRanges != null && !supportedPreviewFpsRanges.isEmpty()) {
            int[] maxFpsRange = supportedPreviewFpsRanges.get(supportedPreviewFpsRanges.size() - 1);
            return setCameraPreviewFpsRange(maxFpsRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX] / 1000,
                    maxFpsRange[Camera.Parameters.PREVIEW_FPS_MAX_INDEX] / 1000);
        } else {
            logger.error("supportedPreviewFpsRanges is null or empty");
            return false;
        }
    }

    public boolean setCameraVideoStabilization(boolean toggle) {
        logger.debug("setCameraVideoStabilization(), toggle=" + toggle);

        if (camera == null) {
            logger.error("camera is null");
            return false;
        }

        if (!isCameraLocked()) {
            logger.error("can't get/set parameters: camera is not locked");
            return false;
        }

        synchronized (camera) {

            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                if (!params.isVideoStabilizationSupported()) {
                    logger.error(" _ video stabilization is NOT supported");
                    return false;
                } else {
                    logger.debug(" _ video stabilization is supported");
                    params.setVideoStabilization(toggle);
                }
            }

            try {
                camera.setParameters(params);
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during setParameters()", e);
                return false;
            }

            return true;
        }
    }

    public FLASH_MODE getCameraFlashMode() {

        if (camera == null) {
            logger.error("camera is null");
            return null;
        }

        if (!isCameraLocked()) {
            logger.error("can't get parameters: camera is not locked");
            return null;
        }

        synchronized (camera) {

            final Parameters params;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
                return null;
            }

            return FLASH_MODE.fromNativeValue(params.getFlashMode());
        }
    }

    /**
     * used to change permanent or temp flash mode for opened camera; working both for photo and video
     */
    public boolean setCameraFlashMode(FLASH_MODE flashMode) {
        logger.debug("setCameraFlashMode(), flashMode=" + flashMode);

        if (flashMode == null) {
            logger.error("flash mode is null");
            return false;
        }

        if (camera == null) {
            logger.error("camera is null");
            return false;
        }

        if (!isCameraLocked()) {
            logger.error("can't get/set parameters: camera is not locked");
            return false;
        }

        synchronized (camera) {

            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            boolean isFlashModeSupported = false;

            final List<String> supportedFlashModes = params.getSupportedFlashModes();

            if (supportedFlashModes != null && supportedFlashModes.size() != 0) {
                for (int i = 0; i < supportedFlashModes.size(); i++) {
                    if (flashMode.getValue().equals(supportedFlashModes.get(i))) {
                        isFlashModeSupported = true;
                        break;
                    }
                }
            } else {
                logger.error("no supported flash modes for this camera");
            }

            if (!isFlashModeSupported) {
                logger.error(" _ flash mode " + flashMode.getValue() + " is NOT supported");
                return false;
            } else {
                logger.debug(" _ flash mode " + flashMode.getValue() + " is supported");
                params.setFlashMode(flashMode.getValue());
            }

            try {
                camera.setParameters(params);
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during setParameters()", e);
                return false;
            }

            return true;
        }
    }

    public FOCUS_MODE getCameraFocusMode() {
        if (camera == null) {
            logger.error("camera is null");
            return null;
        }

        if (!isCameraLocked()) {
            logger.error("can't get parameters: camera is not locked");
            return null;
        }

        final Parameters params;

        try {
            params = camera.getParameters();
        } catch (RuntimeException e) {
            logger.error("a RuntimeException occurred during getParameters()", e);
            return null;
        }

        return FOCUS_MODE.fromNativeValue(params.getFocusMode());
    }

    /**
     * used to change permanent or temp focus mode for opened camera; working both for photo and video
     */
    public boolean setCameraFocus(FOCUS_MODE focusMode) {
        logger.debug("setCameraFocus(), focusMode=" + focusMode);

        if (focusMode == null) {
            logger.error("focus mode is null");
            return false;
        }

        if (camera == null) {
            logger.error("camera is null");
            return false;
        }

        if (!isCameraLocked()) {
            logger.error("can't get/set parameters: camera is not locked");
            return false;
        }

        synchronized (camera) {

            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            boolean isFocusModeSupported = false;

            final List<String> supportedFocusModes = params.getSupportedFocusModes();

            if (supportedFocusModes != null && supportedFocusModes.size() > 0) {
                for (String supportedFocusMode : supportedFocusModes) {

                    if (supportedFocusMode.equals(focusMode.getValue())) {
                        isFocusModeSupported = true;
                    }
                }
            } else {
                logger.error("no supported focus modes for this camera");
            }

            if (!isFocusModeSupported) {
                logger.error(" _ focus mode " + focusMode.getValue() + " is NOT supported");
            } else {
                logger.debug(" _ focus mode " + focusMode.getValue() + " is supported");
                params.setFocusMode(focusMode.getValue());
            }

            camera.cancelAutoFocus();

            boolean isFocusAreasSupported = false;
            boolean isMeteringAreasSupported = false;

            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                isFocusAreasSupported = params.getMaxNumFocusAreas() > 0;
//                if (isFocusAreasSupported) {
//
//                } else {
                // logger.error("focus areas aren't supported");
//                }

                isMeteringAreasSupported = params.getMaxNumMeteringAreas() > 0;
//                if (isMeteringAreasSupported) {
                // List<Camera.Area> meteringAreas = new ArrayList<Camera.Area>();

                // Rect areaRect1 = new Rect(-100, -100, 100, 100); // specify an area in center of image
                // meteringAreas.add(new Camera.Area(areaRect1, 600)); // set weight to 60%
                // Rect areaRect2 = new Rect(800, -1000, 1000, -800); // specify an area in upper right of image
                // meteringAreas.add(new Camera.Area(areaRect2, 400)); // set weight to 40%
                // params.setMeteringAreas(meteringAreas);
//                } else {
                // logger.error("metering areas aren't supported");
//                }
            }

            if (isFocusModeSupported || isFocusAreasSupported || isMeteringAreasSupported) {

                try {
                    camera.setParameters(params);
                } catch (RuntimeException e) {
                    logger.error("a RuntimeException occurred during setParameters()", e);
                    return false;
                }

                return true;
            }

            return false;
        }
    }

    public COLOR_EFFECT getCameraColorEffect() {

        if (camera == null) {
            logger.error("camera is null");
            return null;
        }

        if (!isCameraLocked()) {
            logger.error("can't get parameters: camera is not locked");
            return null;
        }

        synchronized (camera) {

            final Parameters params;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
                return null;
            }

            return COLOR_EFFECT.fromNativeValue(params.getColorEffect());
        }
    }

    /**
     * used to change permanent or temp color effect for opened camera; working both for photo and video
     */
    public boolean setCameraColorEffect(COLOR_EFFECT effect) {
        logger.debug("setCameraColorEffect(), effect=" + effect);

        if (effect == null) {
            logger.error("color effect is null");
            return false;
        }

        if (camera == null) {
            logger.error("camera is null");
            return false;
        }

        if (!isCameraLocked()) {
            logger.error("can't get/set parameters: camera is not locked");
            return false;
        }

        synchronized (camera) {

            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            boolean isColorEffectSupported = false;

            final List<String> supportedColorEffects = params.getSupportedColorEffects();

            if (supportedColorEffects != null && supportedColorEffects.size() != 0) {
                for (String supportedColorEffect : supportedColorEffects) {
                    if (supportedColorEffect.equals(effect.getValue())) {
                        isColorEffectSupported = true;
                    }
                }
            } else {
                logger.error("no supported color effects for this camera");
            }

            if (!isColorEffectSupported) {
                logger.error(" _ color effect " + effect.getValue() + " is NOT supported");
                return false;
            } else {
                logger.debug(" _ color effect " + effect.getValue() + " is supported");
                params.setColorEffect(effect.getValue());
            }

            try {
                camera.setParameters(params);
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during setParameters()", e);
                return false;
            }

            return true;
        }
    }

    /**
     * used to change permanent or temp (during taking photo or recording video) params for opened camera by given PHOTO
     * settings
     *
     * @param photoSettings incorrect parameters will be replaced with actual
     */
    public boolean setCameraParameters(PhotoSettings photoSettings) {
        logger.debug("setCameraParameters(), photoSettings=" + photoSettings);

        if (photoSettings == null) {
            logger.error("photoSettings is null");
            return false;
        }

        if (camera == null) {
            logger.error("camera is null");
            return false;
        }

        if (!isCameraLocked()) {
            logger.error("can't get/set parameters: camera is not locked");
            return false;
        }

        synchronized (camera) {

            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            boolean isPictureSizeSupported = false;

            if ((photoSettings.getPictureWidth() > 0) && (photoSettings.getPictureHeight() > 0)) {

                final List<Camera.Size> supportedPictureSizes = params.getSupportedPictureSizes();

                if (supportedPictureSizes != null && supportedPictureSizes.size() != 0) {
                    for (int i = 0; i < supportedPictureSizes.size(); i++) {

                        // logger.debug(" _ picture size " + supportedPictureSizes.get(i).width + " and height " +
                        // supportedPictureSizes.get(i).height);

                        if ((supportedPictureSizes.get(i).width == photoSettings.getPictureWidth())
                                && (supportedPictureSizes.get(i).height == photoSettings.getPictureHeight())) {
                            isPictureSizeSupported = true;
                            break;
                        }
                    }
                } else {
                    logger.error("no supported pictures sizes for this camera");
                }
            } else {
                logger.error("incorrect picture size: " + photoSettings.getPictureWidth() + "x" + photoSettings.getPictureHeight());
                photoSettings.setPictureSize(params.getPictureSize().width, params.getPictureSize().height);
            }

            if (!isPictureSizeSupported) {
                logger.error(" _ picture size " + photoSettings.getPictureWidth() + "x" + photoSettings.getPictureHeight()
                        + " is NOT supported");
                photoSettings.setPictureSize(params.getPictureSize().width, params.getPictureSize().height);
            } else {
                logger.debug(" _ picture size " + photoSettings.getPictureWidth() + "x" + photoSettings.getPictureHeight()
                        + " is supported");
                params.setPictureSize(photoSettings.getPictureWidth(), photoSettings.getPictureHeight());
            }

            boolean isPictureFormatSupported = false;

            if (photoSettings.getPictureFormat() != null) {
                final List<Integer> supportedPictureFormats = params.getSupportedPictureFormats();
                if (supportedPictureFormats != null && supportedPictureFormats.size() != 0) {
                    for (int i = 0; i < supportedPictureFormats.size(); i++) {
                        if (photoSettings.getPictureFormat().getValue() == supportedPictureFormats.get(i)) {
                            isPictureFormatSupported = true;
                            break;
                        }
                    }
                } else {
                    logger.error("no supported picture formats for this camera");
                }
            } else {
                logger.error("picture format is null");
                photoSettings.setPictureFormat(IMAGE_FORMAT.fromNativeValue(params.getPictureFormat()));
            }

            if (!isPictureFormatSupported) {
                logger.error(" _ picture format " + photoSettings.getPictureFormat() + " is NOT supported");
                photoSettings.setPictureFormat(IMAGE_FORMAT.fromNativeValue(params.getPictureFormat()));
            } else {
                logger.debug(" _ picture format " + photoSettings.getPictureFormat() + " is supported");
                params.setPictureFormat(photoSettings.getPictureFormat().getValue());
            }

            if ((photoSettings.getJpegQuality() > 0) && (photoSettings.getJpegQuality() <= 100)) {
                logger.debug(" _ JPEG quality " + photoSettings.getJpegQuality() + " is supported");
                params.setJpegQuality(photoSettings.getJpegQuality());
                params.setJpegThumbnailQuality(photoSettings.getJpegQuality());
            } else {
                logger.error("incorrect jpeg quality: " + photoSettings.getJpegQuality());
                photoSettings.setJpegQuality(params.getJpegQuality());
            }

            try {
                camera.setParameters(params);
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during setParameters()", e);
                return false;
            }

            if (!setCameraFlashMode(photoSettings.getFlashMode())) {
                photoSettings.setFlashMode(FLASH_MODE.fromNativeValue(params.getFlashMode()));
            }

            if (!setCameraFocus(photoSettings.getFocusMode())) {
                photoSettings.setFocusMode(FOCUS_MODE.fromNativeValue(params.getFocusMode()));
            }

            if (!setCameraColorEffect(photoSettings.getColorEffect())) {
                photoSettings.setColorEffect(COLOR_EFFECT.fromNativeValue(params.getColorEffect()));
            }

            return true;
        }
    }

    public interface OnPhotoReadyListener {
        void onPhotoReady(File photoFile);
    }

    private final LinkedList<OnPhotoReadyListener> photoReadyListeners = new LinkedList<>();

    public CameraController addPhotoReadyListener(OnPhotoReadyListener listener) throws NullPointerException {

        if (listener == null)
            throw new NullPointerException();

        synchronized (photoReadyListeners) {
            if (!photoReadyListeners.contains(listener)) {
                photoReadyListeners.add(listener);
            }
        }

        return this;
    }

    public CameraController removePhotoReadyListener(OnPhotoReadyListener listener) {
        synchronized (photoReadyListeners) {
            if (photoReadyListeners.contains(listener)) {
                photoReadyListeners.remove(listener);
            }
        }

        return this;
    }

    /**
     * used to restore camera parameters after taking photo
     */
    private PhotoSettings previousPhotoSettings;

    private PhotoSettings currentPhotoSettings;

    public synchronized boolean takePhoto(final PhotoSettings photoSettings, String saveDirectoryPath, String photoFileName) {
        logger.debug("takePhoto(), photoSettings=" + photoSettings + ", saveDirectoryPath=" + saveDirectoryPath + ", photoFileName=" + photoFileName);

        if (currentCameraState != CAMERA_STATE.IDLE) {
            logger.error("current camera state is not IDLE! state is " + currentCameraState);
            return currentCameraState == CAMERA_STATE.TAKING_PHOTO;
        }

        if (camera == null) {
            logger.error("camera is null");
            return false;
        }

        if (!isCameraLocked()) {
            logger.error("camera is not locked");
            return false;
        }

        if (!isSurfaceCreated()) {
            logger.error("surface is not created");
            return false;
        }

        if ((this.previousPhotoSettings = getCurrentPhotoSettings()) == null) {
            logger.error("current photo settings is null");
            return false;
        }

        if (!setCameraParameters(photoSettings)) {
            logger.error("can't set camera params by photoSettings: " + photoSettings);
            return false;
        }
        this.currentPhotoSettings = photoSettings;

        if (TextUtils.isEmpty(photoFileName)) {
            photoFileName = makeNewFileName(CAMERA_STATE.TAKING_PHOTO, new Date(System.currentTimeMillis()), new Pair<>(photoSettings.getPictureWidth(), photoSettings.getPictureHeight()), "jpg");
        } else {
            photoFileName = FileHelper.removeExtension(photoFileName) + ".jpg";
        }

        if ((this.lastPhotoFile = FileHelper.testPathNoThrow(saveDirectoryPath, photoFileName)) == null) {
            logger.error("incorrect photo path: " + saveDirectoryPath + File.separator + photoFileName);
            return false;
        }

        if (photoSettings.getFocusMode() == FOCUS_MODE.AUTO || photoSettings.getFocusMode() == FOCUS_MODE.MACRO) {

            camera.cancelAutoFocus();

            logger.info("taking photo with auto focus...");

            camera.autoFocus(new AutoFocusCallback() {

                @Override
                public void onAutoFocus(boolean success, Camera camera) {
                    logger.debug("onAutoFocus(), success=" + true);

                    muteSound(true);

                    final ShutterCallback shutterCallBack = isMuteSoundEnabled ? null : new ShutterCallback() {

                        @Override
                        public void onShutter() {
                            logger.debug("onShutter()");
                        }
                    };

                    camera.takePicture(shutterCallBack, null, pictureCallback);

                    isPreviewStated = false;

                    setCurrentCameraState(CAMERA_STATE.TAKING_PHOTO);
                }
            });

        } else {

            logger.info("taking photo without auto focus...");

            muteSound(true);

            final ShutterCallback shutterCallBack = isMuteSoundEnabled ? null : new ShutterCallback() {

                @Override
                public void onShutter() {
                    logger.debug("onShutter()");
                }
            };

            camera.takePicture(shutterCallBack, null, pictureCallback);

            isPreviewStated = false;

            setCurrentCameraState(CAMERA_STATE.TAKING_PHOTO);
        }

        return true;
    }

    private File lastPhotoFile;

    public File getLastPhotoFile() {
        return lastPhotoFile;
    }

    private final CustomPictureCallback pictureCallback = new CustomPictureCallback();

    private class CustomPictureCallback implements Camera.PictureCallback {

        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            logger.debug("onPictureTaken()");

            if (currentCameraState != CAMERA_STATE.TAKING_PHOTO) {
                throw new IllegalStateException("current camera state is not " + CAMERA_STATE.TAKING_PHOTO);
            }

            if (currentPhotoSettings != null) {

                if (lastPhotoFile != null) {
                    lastPhotoFile = FileHelper.writeBytesToFile(data, lastPhotoFile.getName(), lastPhotoFile.getParent(), false);
                    logger.debug("lastPhotoFile: " + lastPhotoFile);
                }

                if (currentPhotoSettings.isStoreLocationEnabled())
                    FileHelper.writeExifLocation(lastPhotoFile, lastLocation);

                currentPhotoSettings = null;
            }

            if (previousPhotoSettings != null) {
                if (!setCameraParameters(previousPhotoSettings)) {
                    logger.error("can't set camera params by previousPhotoSettings: " + previousPhotoSettings);
                }
                previousPhotoSettings = null;
            }

            startPreview();
            setPreviewCallback();

            setCurrentCameraState(CAMERA_STATE.IDLE);

            muteSound(false);

            synchronized (photoReadyListeners) {
                if (photoReadyListeners.size() > 0) {
                    for (OnPhotoReadyListener l : photoReadyListeners) {
                        l.onPhotoReady(lastPhotoFile);
                    }
                }
            }

        }
    }

    public List<Camera.Size> getSupportedVideoSizes() {
        try {
            if (camera != null && isCameraLocked()) {
                synchronized (camera) {
                    return camera.getParameters().getSupportedVideoSizes();
                }
            }
        } catch (RuntimeException e) {
            logger.error("a RuntimeException occurred during getParameters()", e);
        }
        return null;
    }

    private static String getFileExtensionByVideoEncoder(VIDEO_ENCODER videoEncoder) {

        if (videoEncoder == null) {
            return null;
        }

        switch (videoEncoder) {
            case DEFAULT:
            case MPEG_4_SP:
            case H264:
                return "mp4";
            case H263:
                return "3gp";
            default:
                return "";
        }
    }

    private static int getOutputFormatByVideoEncoder(VIDEO_ENCODER videoEncoder) {

        if (videoEncoder == null) {
            logger.error("videoEncoder is null");
            return -1;
        }

        switch (videoEncoder) {
            case DEFAULT:
                return MediaRecorder.OutputFormat.DEFAULT;

            case H263:
                return MediaRecorder.OutputFormat.THREE_GPP;

            case H264:
            case MPEG_4_SP:
                return MediaRecorder.OutputFormat.MPEG_4;

            default:
                return -1;
        }
    }

    /**
     * parameters flashMode, focusMode, colorEffect will be actual, other - default
     */
    public VideoSettings getCurrentVideoSettings() {

        if (camera != null) {

            if (!isCameraLocked()) {
                logger.error("can't get parameters: camera is not locked");
                return null;
            }

            final Parameters params;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()");
                return null;
            }

            try {
                return new VideoSettings(cameraId, VIDEO_QUALITY.DEFAULT, null, null, false, null, VideoSettings.DEFAULT_VIDEO_FRAME_RATE,
                        FLASH_MODE.fromNativeValue(params.getFlashMode()), FOCUS_MODE.fromNativeValue(params.getFocusMode()),
                        COLOR_EFFECT.fromNativeValue(params.getColorEffect()), false, 0);
            } catch (IllegalArgumentException e) {
                logger.error("an IllegalArgumentException occurred during fromNativeValue(): " + e.getMessage());
                return null;
            }
        }

        logger.error("camera is null");
        return null;

    }

    public VideoSettings getLowVideoSettings() {

        if (camera == null) {
            logger.error("can't get parameters: camera is null");
            return null;
        }

        if (!isCameraLocked()) {
            logger.error("can't get parameters: camera is not locked");
            return null;
        }

        final List<Camera.Size> supportedVideoSizes = getSupportedVideoSizes();

        if (supportedVideoSizes == null || supportedVideoSizes.isEmpty()) {
            logger.error("supportedVideoSizes is null or empty");
            return null;
        }

        StringBuilder videSizesStr = new StringBuilder();
        for (Size s : supportedVideoSizes)
            videSizesStr.append("[" + s.width + "x" + s.height + "]");
        logger.debug("_ supported video sizes: " + videSizesStr);

        final Size lowVideoSize = findLowSize(supportedVideoSizes);
        if (lowVideoSize != null) {
            logger.debug(" _ low VIDEO size: " + lowVideoSize.width + "x" + lowVideoSize.height);
        } else {
            logger.error(" _ low VIDEO size is null");
        }

        return new VideoSettings(cameraId, VIDEO_QUALITY.LOW, VIDEO_ENCODER.H264, AUDIO_ENCODER.AAC, false, lowVideoSize,
                VideoSettings.VIDEO_FRAME_RATE_MAX, FLASH_MODE.AUTO, FOCUS_MODE.AUTO, COLOR_EFFECT.NONE,
                VideoSettings.DEFAULT_ENABLE_MAKE_PREVIEW, VideoSettings.DEFAULT_PREVIEW_GRID_SIZE);
    }

    public VideoSettings getMediumVideoSettings() {

        if (camera == null) {
            logger.error("can't get parameters: camera is null");
            return null;
        }

        if (!isCameraLocked()) {
            logger.error("can't get parameters: camera is not locked");
            return null;
        }

        final List<Camera.Size> supportedVideoSizes = getSupportedVideoSizes();

        if (supportedVideoSizes == null || supportedVideoSizes.isEmpty()) {
            logger.error("supportedVideoSizes is null or empty");
            return null;
        }

        StringBuilder videSizesStr = new StringBuilder();
        for (Size s : supportedVideoSizes)
            videSizesStr.append("[" + s.width + "x" + s.height + "]");
        logger.debug("_ supported video sizes: " + videSizesStr);

        final Size mediumVideoSize = findMediumSize(supportedVideoSizes);
        if (mediumVideoSize != null) {
            logger.debug(" _ medium VIDEO size: " + mediumVideoSize.width + "x" + mediumVideoSize.height);
        } else {
            logger.error(" _ medium VIDEO size is null");
        }

        return new VideoSettings(cameraId, VIDEO_QUALITY.DEFAULT, VIDEO_ENCODER.H264, AUDIO_ENCODER.AAC, false, mediumVideoSize,
                VideoSettings.VIDEO_FRAME_RATE_MAX, FLASH_MODE.AUTO, FOCUS_MODE.AUTO, COLOR_EFFECT.NONE,
                VideoSettings.DEFAULT_ENABLE_MAKE_PREVIEW, VideoSettings.DEFAULT_PREVIEW_GRID_SIZE);
    }

    public VideoSettings getHighVideoSettings() {

        if (camera == null) {
            logger.error("can't get parameters: camera is null");
            return null;
        }

        if (!isCameraLocked()) {
            logger.error("can't get parameters: camera is not locked");
            return null;
        }

        final List<Camera.Size> supportedVideoSizes = getSupportedVideoSizes();

        if (supportedVideoSizes == null || supportedVideoSizes.isEmpty()) {
            logger.error("supportedVideoSizes is null or empty");
            return null;
        }

        StringBuilder videSizesStr = new StringBuilder();
        for (Size s : supportedVideoSizes)
            videSizesStr.append("[" + s.width + "x" + s.height + "]");
        logger.debug("_ supported video sizes: " + videSizesStr);

        final Size highVideoSize = findHighSize(supportedVideoSizes);
        if (highVideoSize != null) {
            logger.debug(" _ high VIDEO size: " + highVideoSize.width + "x" + highVideoSize.height);
        } else {
            logger.error(" _ high VIDEO size is null");
        }

        return new VideoSettings(cameraId, VIDEO_QUALITY.HIGH, VIDEO_ENCODER.H264, AUDIO_ENCODER.AAC, false, highVideoSize,
                VideoSettings.VIDEO_FRAME_RATE_MAX, FLASH_MODE.AUTO, FOCUS_MODE.AUTO, COLOR_EFFECT.NONE,
                VideoSettings.DEFAULT_ENABLE_MAKE_PREVIEW, VideoSettings.DEFAULT_PREVIEW_GRID_SIZE);
    }

    public boolean isVideoSizeSupported(int width, int height) {

        if (camera == null) {
            logger.error("can't get parameters: camera is null");
            return false;
        }

        if (!isCameraLocked()) {
            logger.error("can't get parameters: camera is not locked");
            return false;
        }

        boolean isVideoSizeSupported = false;
        // boolean isPreviewSizeSupported = false;

        final List<Camera.Size> supportedVideoSizes = getSupportedVideoSizes();
        // final List<Camera.Size> supportedPreviewSizes = getSupportedPreviewSizes();

        if (supportedVideoSizes != null /* && supportedPreviewSizes != null */) {

            if (width > 0 && height > 0) {

                for (int i = 0; i < supportedVideoSizes.size(); i++) {
                    if (width == supportedVideoSizes.get(i).width && height == supportedVideoSizes.get(i).height) {
                        isVideoSizeSupported = true;
                        break;
                    }
                }

                /**
                 * if (isVideoSizeSupported) {
                 *
                 * for (int i = 0; i < supportedPreviewSizes.size(); i++) { logger.debug(" ______ " +
                 * supportedPreviewSizes.get(i).width + "x" + supportedPreviewSizes.get(i).height); if (width ==
                 * supportedPreviewSizes.get(i).width && height == supportedPreviewSizes.get(i).height) {
                 * isPreviewSizeSupported = true; break; } } }
                 */
            }

        }

        return (isVideoSizeSupported /* && isPreviewSizeSupported */);
    }

    /**
     * @param profile         if is not null, then it will be setted to recorder (re-filled if necessary); otherwise single
     *                        parameters will be setted in appropriate order without profile
     * @param videoSettings   must not be null
     * @param previewFpsRange if is not null and requested fps falls within the range, it will be applied
     */
    private boolean setMediaRecorderParams(CamcorderProfile profile, VideoSettings videoSettings, double[] previewFpsRange) {
        logger.debug("setMediaRecorderParams(), profile=" + profile + ", videoSettings=" + videoSettings + ", previewFpsRange="
                + previewFpsRange);

        if (videoSettings == null) {
            logger.error("can't set media recorder parameters: videoSettings is null");
            return false;
        }

        if (mediaRecorder == null) {
            logger.error("can't set media recorder parameters: mediaRecorder is null");
            return false;
        }

        if (isMediaRecorderRecording) {
            logger.error("can't set media recorder parameters: mediaRecorder is already recording");
            return false;
        }

        int videoFrameRate = profile != null ? profile.videoFrameRate : VideoSettings.VIDEO_FRAME_RATE_MAX;

        if (previewFpsRange != null && videoSettings.getVideoFrameRate() >= previewFpsRange[0]
                && videoSettings.getVideoFrameRate() <= previewFpsRange[1]) {
            videoFrameRate = videoSettings.getVideoFrameRate();
        } else if (videoSettings.getVideoFrameRate() == VideoSettings.VIDEO_FRAME_RATE_AUTO) {

            if (previewCallback.getLastFps() == 0) {
                // wait for count
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    logger.error("an InterruptedException occurred during sleep()", e);
                    Thread.currentThread().interrupt();
                }
            }

            if (previewCallback.getLastFps() >= previewFpsRange[0] && previewCallback.getLastFps() <= previewFpsRange[1]) {
                videoFrameRate = previewCallback.getLastFps();
            } else {
                videoFrameRate = VideoSettings.VIDEO_FRAME_RATE_MAX;
            }
        } else {
            logger.error("incorrect video frame rate: " + videoSettings.getVideoFrameRate());
        }

        // set sources -> setProfile
        // OR
        // set sources -> setOutputFormat -> setVideoFrameRate -> setVideoSize -> set audio/video encoding bitrate ->
        // set encoders

        if (profile != null) {

            logger.debug("==CamcorderProfile==");
            logger.debug("quality=" + profile.quality);
            logger.debug("videoCodec=" + profile.videoCodec);
            logger.debug("audioCodec=" + profile.audioCodec);
            logger.debug("videoBitRate=" + profile.videoBitRate);
            logger.debug("audioBitRate=" + profile.audioBitRate);
            logger.debug("audioChannels=" + profile.audioChannels);
            logger.debug("audioSampleRate=" + profile.audioSampleRate);
            logger.debug("fileFormat=" + profile.fileFormat);
            logger.debug("videoFrameRate=" + profile.videoFrameRate);
            logger.debug("videoFrameWidth=" + profile.videoFrameWidth + ", videoFrameHeight=" + profile.videoFrameHeight);

            if (profile.videoCodec != videoSettings.getVideoEncoder().getValue()) {
                logger.debug("videoCodec in profile (" + profile.videoCodec + ") is not the same as in videoSettings ("
                        + videoSettings.getVideoEncoder().getValue() + "), changing...");
                profile.videoCodec = videoSettings.getVideoEncoder().getValue();
                profile.fileFormat = getOutputFormatByVideoEncoder(videoSettings.getVideoEncoder());
            }

            if (profile.audioCodec != videoSettings.getAudioEncoder().getValue()) {
                logger.debug("audioCodec in profile (" + profile.audioCodec + ") is not the same as in videoSettings ("
                        + videoSettings.getAudioEncoder().getValue() + "), changing...");
                profile.audioCodec = videoSettings.getAudioEncoder().getValue();
            }

            if (!videoSettings.isAudioDisabled()) {

                try {

                    logger.debug("setting profile " + profile.quality + "...");
                    mediaRecorder.setProfile(profile);

                } catch (RuntimeException e) {
                    logger.error("a RuntimeException occurred during setProfile()", e);
                    return false;
                }

            } else {

                logger.debug("recording audio disabled, setting parameters manually by profile " + profile.quality + "...");

                mediaRecorder.setOutputFormat(profile.fileFormat);
                mediaRecorder.setVideoFrameRate(profile.videoFrameRate);
                mediaRecorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
                mediaRecorder.setVideoEncodingBitRate(profile.videoBitRate);
                mediaRecorder.setVideoEncoder(profile.videoCodec);
            }

            if (profile.videoFrameRate != videoFrameRate) {
                logger.debug("videoFrameRate in profile (" + profile.videoFrameRate + ") is not the same as in videoSettings ("
                        + videoFrameRate + "), setting manually...");
                // profile.videoFrameRate = videoFrameRate;
                mediaRecorder.setVideoFrameRate(videoFrameRate);
            }

        } else {

            logger.debug("profile is null, setting parameters manually...");

            mediaRecorder.setOutputFormat(getOutputFormatByVideoEncoder(videoSettings.getVideoEncoder()));
            logger.debug("output format: " + getOutputFormatByVideoEncoder(videoSettings.getVideoEncoder()));

            mediaRecorder.setVideoFrameRate(videoFrameRate);
            logger.debug("video frame rate: " + videoFrameRate);

            if (videoSettings.getVideoFrameWidth() > 0 && videoSettings.getVideoFrameHeight() > 0) {
                mediaRecorder.setVideoSize(videoSettings.getVideoFrameWidth(), videoSettings.getVideoFrameHeight());
                logger.debug("video frame size: width=" + videoSettings.getVideoFrameWidth() + ", height="
                        + videoSettings.getVideoFrameHeight());
            } else {
                logger.error("incorrect video frame size: " + videoSettings.getVideoFrameWidth() + "x"
                        + videoSettings.getVideoFrameHeight());
            }

            if (!videoSettings.isAudioDisabled()) {
                mediaRecorder.setAudioEncoder(videoSettings.getAudioEncoder().getValue());
                logger.debug("audio encoder: " + videoSettings.getAudioEncoder());
            } else {
                logger.debug("recording audio disabled");
            }

            mediaRecorder.setVideoEncoder(videoSettings.getVideoEncoder().getValue());
            logger.debug("video encoder: " + videoSettings.getVideoEncoder());
        }

        return true;
    }

    private MediaRecorder mediaRecorder;
    private volatile boolean isMediaRecorderRecording = false;

    private File lastVideoFile;

    public File getLastVideoFile() {
        return lastVideoFile;
    }

    private File lastPreviewFile;

    public File getLastPreviewFile() {
        return lastPreviewFile;
    }

    public long getLastPreviewFileSize() {
        return (lastPreviewFile != null && lastPreviewFile.isFile() && lastPreviewFile.exists()) ? lastPreviewFile.length() : 0;
    }

    /**
     * must be called after setOutputFormat()
     */
    private void setVideoRecordLimit(VideoRecordLimit recLimit) {
        logger.debug("setVideoRecordLimit(), recLimit=" + recLimit);

        if (mediaRecorder == null) {
            logger.error("can't set video record limit: mediaRecorder is null");
            return;
        }

        if (isMediaRecorderRecording) {
            logger.error("can't set video record limit: mediaRecorder is already recording");
            return;
        }

        if (recLimit != null) {
            switch (recLimit.getRecordLimitWhat()) {
                case TIME:
                    if (recLimit.getRecordLimitValue() > 0) {
                        logger.debug("setting max duration " + (int) recLimit.getRecordLimitValue() + "...");
                        mediaRecorder.setMaxDuration((int) recLimit.getRecordLimitValue());
                    }
                    break;
                case SIZE:
                    if (recLimit.getRecordLimitValue() > 0) {
                        logger.debug("setting max file size " + recLimit.getRecordLimitValue() + "...");
                        mediaRecorder.setMaxFileSize(recLimit.getRecordLimitValue());
                    }
                    break;
                default:
                    break;
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private boolean setRecordingHint(boolean hint) {

        if (camera == null) {
            logger.error("camera is null");
            return false;
        }

        if (!isCameraLocked()) {
            logger.error("can't get/set parameters: camera is not locked");
            return false;
        }

        synchronized (camera) {

            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            params.setRecordingHint(hint);

            try {
                camera.setParameters(params);
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during setParameters()", e);
                return false;
            }

            return true;
        }
    }

    /**
     * used to change permanent or temp (during taking photo or recording video) params for opened camera by given VIDEO
     * settings
     *
     * @param videoSettings incorrect parameters will be replaced with actual
     */
    public boolean setCameraParameters(VideoSettings videoSettings) {
        logger.debug("setCameraParameters(), videoSettings=" + videoSettings);

        if (videoSettings == null) {
            logger.error("videoSettings is null");
            return false;
        }

        if (camera == null) {
            logger.error("camera is null");
            return false;
        }

        if (!isCameraLocked()) {
            logger.error("can't get/set parameters: camera is not locked");
            return false;
        }

        synchronized (camera) {

            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            if (!setCameraFlashMode(videoSettings.getFlashMode())) {
                videoSettings.setFlashMode(FLASH_MODE.fromNativeValue(params.getFlashMode()));
            }

            if (!setCameraFocus(videoSettings.getFocusMode())) {
                videoSettings.setFocusMode(FOCUS_MODE.fromNativeValue(params.getFocusMode()));
            }

            if (!setCameraColorEffect(videoSettings.getColorEffect())) {
                videoSettings.setColorEffect(COLOR_EFFECT.fromNativeValue(params.getColorEffect()));
            }

            try {
                camera.setParameters(params);
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during setParameters()", e);
                return false;
            }

            return true;
        }
    }

    public static interface OnMediaRecorderErrorListener {
        void onMediaRecorderError(int error);
    }

    private final LinkedList<OnMediaRecorderErrorListener> mediaRecorderErrorListeners = new LinkedList<OnMediaRecorderErrorListener>();

    public CameraController addMediaRecorderErrorListener(OnMediaRecorderErrorListener listener) throws NullPointerException {

        if (listener == null) {
            throw new NullPointerException();
        }

        synchronized (mediaRecorderErrorListeners) {
            if (!mediaRecorderErrorListeners.contains(listener)) {
                mediaRecorderErrorListeners.add(listener);
            }
        }

        return this;
    }

    public CameraController removeMediaRecorderErrorListener(OnMediaRecorderErrorListener listener) {
        synchronized (mediaRecorderErrorListeners) {
            if (mediaRecorderErrorListeners.contains(listener)) {
                mediaRecorderErrorListeners.remove(listener);
            }
        }
        return this;
    }

    private synchronized boolean prepareMediaRecorder(VideoSettings videoSettings, VideoRecordLimit recLimit, String saveDirectoryPath, String fileName) {

        final long startPrepareTime = System.currentTimeMillis();

        if (camera == null) {
            logger.error("camera is null");
            return false;
        }

        if (!isSurfaceCreated()) {
            logger.error("surface is not created");
            return false;
        }

        if (isMediaRecorderRecording) {
            logger.error("mediaRecorder is already recording");
            return false;
        }

        if ((this.previousVideoSettings = getCurrentVideoSettings()) == null) {
            logger.error("current video settings is null");
            return false;
        }

        if (!setCameraParameters(videoSettings)) {
            logger.error("can't set camera params by videoSettings: " + videoSettings);
            return false;
        }
        this.currentVideoSettings = videoSettings;

        final CamcorderProfile profile;

        // for video file name
        final Pair<Integer, Integer> resolution;

        if (videoSettings.getQuality() == VIDEO_QUALITY.DEFAULT) {

            profile = null;

            if (isVideoSizeSupported(videoSettings.getVideoFrameWidth(), videoSettings.getVideoFrameHeight())) {

                logger.debug("VIDEO size " + videoSettings.getVideoFrameWidth() + "x" + videoSettings.getVideoFrameHeight()
                        + " is supported");

            } else {
                logger.error("VIDEO size " + videoSettings.getVideoFrameWidth() + "x" + videoSettings.getVideoFrameHeight()
                        + " is NOT supported, getting medium...");

                final VideoSettings mediumVideoSettings = getMediumVideoSettings();
                videoSettings.setVideoFrameSize(mediumVideoSettings.getVideoFrameWidth(), mediumVideoSettings.getVideoFrameHeight());
            }

            resolution = new Pair<>(videoSettings.getVideoFrameWidth(), videoSettings.getVideoFrameHeight());

        } else {

            if (CamcorderProfile.hasProfile(cameraId, videoSettings.getQuality().getValue())) {

                logger.info("profile " + videoSettings.getQuality().getValue() + " is supported for camera with id " + cameraId
                        + ", getting...");
                profile = CamcorderProfile.get(cameraId, videoSettings.getQuality().getValue());

                resolution = null;

            } else {

                logger.error("profile " + videoSettings.getQuality().getValue() + " is NOT supported, changing quality to default...");
                videoSettings.setQuality(cameraId, VIDEO_QUALITY.DEFAULT);
                profile = null;

                if (isVideoSizeSupported(videoSettings.getVideoFrameWidth(), videoSettings.getVideoFrameHeight())) {

                    logger.debug("VIDEO size " + videoSettings.getVideoFrameWidth() + "x" + videoSettings.getVideoFrameHeight()
                            + " is supported");

                } else {
                    logger.error("VIDEO size " + videoSettings.getVideoFrameWidth() + "x" + videoSettings.getVideoFrameHeight()
                            + " is NOT supported, getting medium...");

                    final VideoSettings mediumVideoSettings = getMediumVideoSettings();
                    videoSettings.setVideoFrameSize(mediumVideoSettings.getVideoFrameWidth(), mediumVideoSettings.getVideoFrameHeight());
                }

                resolution = new Pair<>(videoSettings.getVideoFrameWidth(), videoSettings.getVideoFrameHeight());
            }

        }

        final double[] previewFpsRange = getCameraPreviewFpsRange();

        stopPreview();

        if (callbackBufferQueueSize > 0)
            setRecordingHint(true);

        if (!unlockCamera()) {
            logger.error("unlocking camera failed");
            return false;
        }

        mediaRecorder = new MediaRecorder();

        mediaRecorder.setOnErrorListener(new OnErrorListener() {

            @Override
            public void onError(MediaRecorder mediaRecorder, int what, int extra) {
                logger.error("onError(), what=" + what + ", extra=" + extra);

                // invokes on UI thread

                final File videoFile = stopRecordVideo();

                synchronized (recordLimitReachedListeners) {
                    if (recordLimitReachedListeners.size() > 0) {
                        for (OnRecordLimitReachedListener l : recordLimitReachedListeners) {
                            l.onRecordLimitReached(videoFile);
                        }
                    }
                }
            }
        });

        mediaRecorder.setOnInfoListener(new OnInfoListener() {

            @Override
            public void onInfo(MediaRecorder mediaRecorder, int what, int extra) {

                switch (what) {
                    case MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED:
                    case MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED:
                        logger.debug("onInfo(), what=" + what + ", extra=" + extra);

                        // invokes on UI thread

                        final File videoFile = stopRecordVideo();

                        synchronized (recordLimitReachedListeners) {
                            if (recordLimitReachedListeners.size() > 0) {
                                for (OnRecordLimitReachedListener l : recordLimitReachedListeners) {
                                    l.onRecordLimitReached(videoFile);
                                }
                            }
                        }

                        break;
                }
            }
        });

        mediaRecorder.setCamera(camera);

        if (!videoSettings.isAudioDisabled())
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);

        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

        if (videoSettings.isStoreLocationEnabled() && lastLocation != null
                && (Double.compare(lastLocation.getLatitude(), 0.0d) != 0 || Double.compare(lastLocation.getLongitude(), 0.0d) != 0)) {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                logger.debug("set video location: latitude " + lastLocation.getLatitude() + " longitude " + lastLocation.getLongitude()
                        + " accuracy " + lastLocation.getAccuracy());
                mediaRecorder.setLocation((float) lastLocation.getLatitude(), (float) lastLocation.getLongitude());
            }
        }

        if (!setMediaRecorderParams(profile, videoSettings, previewFpsRange)) {
            logger.error("setMediaRecorderParams() failed");

            releaseMediaRecorder();
            return false;
        }

        setVideoRecordLimit(recLimit);

        if (TextUtils.isEmpty(fileName)) {
            fileName = makeNewFileName(CAMERA_STATE.RECORDING_VIDEO, new Date(System.currentTimeMillis()), resolution, getFileExtensionByVideoEncoder(videoSettings.getVideoEncoder()));
        } else {
            fileName = FileHelper.removeExtension(fileName) + "." + getFileExtensionByVideoEncoder(videoSettings.getVideoEncoder());
        }

        lastVideoFile = FileHelper.createNewFile(fileName, saveDirectoryPath);
        logger.info("lastVideoFile: " + lastVideoFile);

        if (lastVideoFile == null) {
            logger.error("can't create video file");
            return false;
        }

        mediaRecorder.setOutputFile(lastVideoFile.getAbsolutePath());

        mediaRecorder.setPreviewDisplay(cameraSurfaceView.getHolder().getSurface());
        setCameraDisplayOrientation();

        try {
            mediaRecorder.prepare();
        } catch (Exception e) {
            logger.error("an Exception occurred during prepare()", e);
            releaseMediaRecorder();
            return false;
        }

        logger.debug("media recorder prepare time: " + (System.currentTimeMillis() - startPrepareTime) + " ms");
        return true;
    }

    private synchronized boolean releaseMediaRecorder() {
        logger.debug("releaseMediaRecorder()");

        try {
            return executor.submit(new Callable<Boolean>() {

                @Override
                public Boolean call() throws Exception {
                    if (mediaRecorder != null) {

                        final long startReleaseTime = System.currentTimeMillis();

                        mediaRecorder.setOnErrorListener(null);
                        mediaRecorder.setOnInfoListener(null);

                        mediaRecorder.reset();
                        mediaRecorder.release();

                        logger.debug("media recorder release time: " + (System.currentTimeMillis() - startReleaseTime) + " ms");
                        return true;
                    }
                    return false;
                }
            }).get(EXECUTOR_CALL_TIMEOUT, TimeUnit.SECONDS);

        } catch (Exception e) {
            logger.error("an Exception occurred during get()", e);

            return false;

        } finally {

            mediaRecorder = null;

            if (!lockCamera()) {
                logger.error("locking camera failed");
            }

            synchronized (camera) {
                try {
                    camera.reconnect();
                } catch (IOException e) {
                    logger.error("an IOException occures during reconnect()", e);
                    return false;
                }
            }

            if (callbackBufferQueueSize > 0)
                setRecordingHint(false);

            startPreview();
            setPreviewCallback();

            if (previousVideoSettings != null) {
                if (!setCameraParameters(previousVideoSettings)) {
                    logger.error("can't set camera params by previousVideoSettings: " + previousVideoSettings);
                }
                previousVideoSettings = null;
            }
        }

    }

    public static interface OnRecordLimitReachedListener {
        void onRecordLimitReached(File videoFile);
    }

    private final LinkedList<OnRecordLimitReachedListener> recordLimitReachedListeners = new LinkedList<OnRecordLimitReachedListener>();

    public CameraController addRecordLimitReachedListener(OnRecordLimitReachedListener listener) throws NullPointerException {
        if (listener == null)
            throw new NullPointerException();

        synchronized (recordLimitReachedListeners) {
            if (!recordLimitReachedListeners.contains(listener)) {
                recordLimitReachedListeners.add(listener);
            }
        }

        return this;
    }

    public CameraController removeRecordLimitReachedListener(OnRecordLimitReachedListener listener) {
        synchronized (recordLimitReachedListeners) {
            if (recordLimitReachedListeners.contains(listener)) {
                recordLimitReachedListeners.remove(listener);
            }
        }
        return this;
    }

    /**
     * used to restore camera parameters after recording video
     */
    private VideoSettings previousVideoSettings;

    private VideoSettings currentVideoSettings;

    public synchronized boolean startRecordVideo(VideoSettings videoSettings, VideoRecordLimit recLimit, String saveDirectoryPath, String fileName) {
        logger.debug("startRecordVideo(), videoSettings=" + videoSettings + "deleteTempVideoFiles="
                + ", recLimit=" + recLimit + ", saveDirectoryPath=" + saveDirectoryPath + ", fileName=" + fileName);

        if (currentCameraState != CAMERA_STATE.IDLE) {
            logger.error("current camera state is not IDLE! state is " + currentCameraState);
            return currentCameraState == CAMERA_STATE.RECORDING_VIDEO;
        }

        if (prepareMediaRecorder(videoSettings, recLimit, saveDirectoryPath, fileName)) {

            final long startStartingTime = System.currentTimeMillis();
            logger.info("starting record video...");

            muteSound(true);

            try {
                mediaRecorder.start();
            } catch (IllegalStateException e) {
                logger.error("an IllegalStateException occurred during start()", e);
                releaseMediaRecorder();
                return false;
            }
            logger.debug("video recording has been started (" + (System.currentTimeMillis() - startStartingTime) + " ms)");

            muteSound(false);

            setCurrentCameraState(CAMERA_STATE.RECORDING_VIDEO);

            isMediaRecorderRecording = true;

            return true;
        }

        releaseMediaRecorder();

        return false;
    }

    public synchronized File stopRecordVideo() {
        logger.debug("stopRecordVideo()");

        if (isMediaRecorderRecording) {

            muteSound(true);

            try {
                executor.submit(new Callable<Boolean>() {

                    @Override
                    public Boolean call() throws Exception {
                        if (mediaRecorder != null) {

                            final long startStoppingTime = System.currentTimeMillis();
                            logger.info("stopping record video...");
                            mediaRecorder.stop();
                            logger.debug("video recording has been stopped (" + (System.currentTimeMillis() - startStoppingTime) + " ms)");

                            return true;
                        } else {
                            return false;
                        }
                    }
                }).get(EXECUTOR_CALL_TIMEOUT, TimeUnit.SECONDS);

            } catch (Exception e) {
                logger.error("an Exception occurred during get()", e);
                mediaRecorder = null;
            }

            muteSound(false);

            isMediaRecorderRecording = false;

            setCurrentCameraState(CAMERA_STATE.IDLE);

        } else {
            logger.error("mediaRecorder is not recording");
            return null;
        }

        releaseMediaRecorder();

        if (makePreviewThreadPoolExecutor != null) {
            try {
                makePreviewThreadPoolExecutor.execute(new MakePreviewRunnable(new MakePreviewRunnableInfo(lastVideoFile.getName(), currentVideoSettings, lastVideoFile)));
            } catch (NullPointerException e) {
                logger.error("a NullPointerException occurred during getName(): " + e.getMessage());
            }
        } else {
            logger.error("makePreviewThreadPoolExecutor is null");
        }

        currentVideoSettings = null;

        return lastVideoFile;
    }

    /**
     * preview will be created asynchronously (if it's allowed), result will be delivered to onVideoPreviewReady()
     * callback
     */
    public interface OnVideoPreviewReadyListener {
        /**
         * preview for its video file is ready; invokes from the other thread
         */
        void onVideoPreviewReady(File previewFile, Bitmap firstFrame, Bitmap lastFrame, File videoFile);
    }

    private final LinkedList<OnVideoPreviewReadyListener> videoPreviewReadyListeners = new LinkedList<OnVideoPreviewReadyListener>();

    public CameraController addVideoPreviewReadyListener(OnVideoPreviewReadyListener listener) throws NullPointerException {
        if (listener == null)
            throw new NullPointerException();

        synchronized (videoPreviewReadyListeners) {
            if (!videoPreviewReadyListeners.contains(listener)) {
                videoPreviewReadyListeners.add(listener);
            }
        }

        return this;
    }

    public CameraController removeVideoPreviewReadyListener(OnVideoPreviewReadyListener listener) {
        synchronized (videoPreviewReadyListeners) {
            if (videoPreviewReadyListeners.contains(listener)) {
                videoPreviewReadyListeners.remove(listener);
            }
        }
        return this;
    }

    private final static int MAKE_PREVIEW_POOL_SIZE = 5;

    /**
     * used for storing and running runnables to save preview for given video
     */
    private MakePreviewThreadPoolExecutor makePreviewThreadPoolExecutor;

    public void initMakePreviewThreadPoolExecutor(boolean syncWorkQueue, String workQueuesPath) {
        logger.debug("initMakePreviewThreadPoolExecutor(), syncWorkQueue=" + syncWorkQueue + ", workQueuesPath=" + workQueuesPath);

        releaseMakePreviewThreadPoolExecutor();

        makePreviewThreadPoolExecutor = new MakePreviewThreadPoolExecutor(MAKE_PREVIEW_POOL_SIZE, syncWorkQueue,
                workQueuesPath != null ? workQueuesPath + File.separator + "make_preview" : null);
    }

    public void releaseMakePreviewThreadPoolExecutor() {

        if (makePreviewThreadPoolExecutor == null) {
            return;
        }

        logger.debug("releaseMakePreviewThreadPoolExecutor()");

        makePreviewThreadPoolExecutor.shutdown();
        makePreviewThreadPoolExecutor = null;
    }

    public class MakePreviewRunnable extends TaskRunnable<MakePreviewRunnableInfo> {

        private final MakePreviewRunnableInfo rInfo;

        public MakePreviewRunnable(MakePreviewRunnableInfo rInfo) {
            super(rInfo);

            this.rInfo = rInfo;
        }

        @Override
        protected boolean checkArgs() {
            return false;
        }

        @Override
        public void run() {
            doMakePreview();
        }

        private void doMakePreview() {
            logger.debug("doMakePreview()");

            if (!FileHelper.isFileCorrect(rInfo.videoFile) || !FileHelper.isVideo(FileHelper.getFileExtension(rInfo.videoFile.getName()))) {
                logger.error("incorrect video file: " + rInfo.videoFile + ", size: "
                        + (rInfo.videoFile != null ? (rInfo.videoFile.length() / 1024) : 0) + " kB, " + ", exists: "
                        + (rInfo.videoFile != null && rInfo.videoFile.exists()));

                synchronized (videoPreviewReadyListeners) {
                    if (videoPreviewReadyListeners.size() > 0) {
                        for (OnVideoPreviewReadyListener l : videoPreviewReadyListeners) {
                            l.onVideoPreviewReady(null, null, null, rInfo.videoFile);
                        }
                    }
                }

                return;
            }

            final Bitmap firstFrame = GraphicUtils.getVideoFrameAtPosition(rInfo.videoFile, 1);
            final Bitmap lastFrame = GraphicUtils.getVideoFrameAtPosition(rInfo.videoFile,
                    (long) (GraphicUtils.getVideoDuration(rInfo.videoFile) * 0.95));

            if (rInfo.videoSettings == null || !rInfo.videoSettings.isMakePreviewEnabled()) {
                logger.warn("making preview is NOT enabled");

                lastPreviewFile = null;

            } else {
                logger.debug("making preview is enabled");

                Bitmap previewBitmap = GraphicUtils.makePreviewFromVideoFile(rInfo.videoFile, rInfo.videoSettings.getPreviewGridSize(), true);
                if (!GraphicUtils.isBitmapCorrect(previewBitmap)) {
                    logger.error("incorrect preview bitmap: " + previewBitmap);
                    return;
                }
                lastPreviewFile = new File(rInfo.videoFile.getParentFile(), rInfo.videoFile.getName() + GraphicUtils.getFileExtByCompressFormat(Bitmap.CompressFormat.PNG));
                GraphicUtils.writeCompressedBitmapToFile(lastPreviewFile, previewBitmap, Bitmap.CompressFormat.PNG);
            }

            synchronized (videoPreviewReadyListeners) {
                if (videoPreviewReadyListeners.size() > 0) {
                    for (OnVideoPreviewReadyListener l : videoPreviewReadyListeners) {
                        l.onVideoPreviewReady(lastPreviewFile, firstFrame, lastFrame, rInfo.videoFile);
                    }
                }
            }

        }
    }

    public static final boolean DEFAULT_ALLOW_FPS_LOGGING = true;

    public void enableFpsLogging(boolean enable) {
        previewCallback.setAllowLogging(enable);
    }

    private final CustomPreviewCallback previewCallback = new CustomPreviewCallback(DEFAULT_ALLOW_FPS_LOGGING);

    private class CustomPreviewCallback implements Camera.PreviewCallback {

        private boolean allowLogging;

        private long startPreviewTime = 0;
        private int sumFps, averageFps;

        public void notifyPreviewStarted() {
            startPreviewTime = System.currentTimeMillis();
            sumFps = 0;
            averageFps = 0;
        }

        private long startTimeLog = 0;
        private int frames, lastFps;

        public CustomPreviewCallback(boolean allowLogging) {
            setAllowLogging(allowLogging);
        }

        public void setAllowLogging(boolean allow) {
            allowLogging = allow;
        }

        public void logFrame() {
            if (allowLogging) {
                frames++;
                if (System.nanoTime() - startTimeLog >= 1000000000) {

                    lastFps = frames;
                    logger.debug("lastFps=" + lastFps);
                    frames = 0;
                    startTimeLog = System.nanoTime();

                    sumFps += lastFps;
                    int time = ((int) (System.currentTimeMillis() - startPreviewTime) / 1000);
                    averageFps = time != 0 ? (int) Math.round(((double) sumFps / (double) time)) : lastFps;
                    logger.debug("averageFps=" + averageFps);
                }
            }
        }

        @SuppressWarnings("unused")
        public int getAverageFps() {
            return averageFps != 0 ? averageFps : (averageFps = lastFps);
        }

        public int getLastFps() {
            return lastFps;
        }

        @SuppressWarnings("unused")
        public void resetFpsCounter() {
            startTimeLog = 0;
            frames = 0;
            lastFps = 0;
        }

        private long recorderInterval = 0;

        private boolean allowRecord(double targetFps) {
            if (System.nanoTime() - recorderInterval >= (1000000000d / targetFps)) {
                recorderInterval = System.nanoTime();
                return true;
            }
            return false;
        }

        private IMAGE_FORMAT previewFormat;

        public void updatePreviewFormat(IMAGE_FORMAT previewFormat) {
            this.previewFormat = previewFormat;
        }

        private int previewWidth = -1;
        private int previewHeight = -1;

        public void updatePreviewSize(Size previewSize) {
            if (previewSize != null) {
                previewWidth = previewSize.width;
                previewHeight = previewSize.height;
            }
        }

        public void updatePreviewSize(int width, int height) {
            this.previewWidth = width;
            this.previewHeight = height;
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onPreviewFrame(final byte[] data, final Camera camera) {
//            logger.debug("onPreviewFrame(), data (length)=" + (data != null ? data.length : 0));

            if (data == null) {
                logger.error("data is null");
                return;
            }

            if (data.length != expectedCallbackBufSize && expectedCallbackBufSize > 0) {
                logger.warn("frame data size (" + data.length + ") is not equal expected (" + expectedCallbackBufSize + ")");
            }

            logFrame();

            if (camera != null && isCameraLocked() && callbackBufferQueueSize > 0) {
                camera.addCallbackBuffer(data);
            }
        }
    }

    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.getDefault());

    private static String makeNewFileName(CAMERA_STATE state, Date date, Pair<Integer, Integer> resolution, String ext) {
        StringBuilder name = new StringBuilder();
        if (state != null) {
            switch (state) {
                case TAKING_PHOTO:
                    name.append("IMG");
                    break;
                case RECORDING_VIDEO:
                    name.append("VID");
                    break;
                default:
                    break;
            }
            if (date != null) {
                if (name.length() > 0) {
                    name.append("_");
                }
                name.append(dateFormat.format(date));
            }
            if (resolution != null && resolution.first != null && resolution.first != 0 && resolution.second != null && resolution.second != 0) {
                if (name.length() > 0) {
                    name.append("_");
                }
                name.append(String.valueOf(resolution.first));
                name.append("x");
                name.append(String.valueOf(resolution.second));
            }
            if (!TextUtils.isEmpty(ext)) {
                name.append(".");
                name.append(ext);
            }
        }
        return name.toString();
    }

}