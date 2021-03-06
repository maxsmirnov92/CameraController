package net.maxsmr.cameracontroller.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.ShutterCallback;
import android.hardware.Camera.Size;
import android.hardware.SensorManager;
import android.location.Location;
import android.media.AudioManager;
import android.media.CamcorderProfile;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup.LayoutParams;

import androidx.core.util.Pair;

import net.maxsmr.cameracontroller.camera.settings.ColorEffect;
import net.maxsmr.cameracontroller.camera.settings.FlashMode;
import net.maxsmr.cameracontroller.camera.settings.FocusMode;
import net.maxsmr.cameracontroller.camera.settings.WhiteBalance;
import net.maxsmr.cameracontroller.camera.settings.photo.CameraSettings;
import net.maxsmr.cameracontroller.camera.settings.photo.ImageFormat;
import net.maxsmr.cameracontroller.camera.settings.video.AudioEncoder;
import net.maxsmr.cameracontroller.camera.settings.video.VideoEncoder;
import net.maxsmr.cameracontroller.camera.settings.video.VideoQuality;
import net.maxsmr.cameracontroller.camera.settings.video.record.VideoRecordLimit;
import net.maxsmr.cameracontroller.camera.settings.video.record.VideoSettings;
import net.maxsmr.cameracontroller.frame.FrameCalculator;
import net.maxsmr.cameracontroller.frame.stats.IFrameStatsListener;
import net.maxsmr.commonutils.android.gui.OrientationIntervalListener;
import net.maxsmr.commonutils.android.gui.progressable.Progressable;
import net.maxsmr.commonutils.android.hardware.SimpleGestureListener;
import net.maxsmr.commonutils.android.media.MetadataRetriever;
import net.maxsmr.commonutils.data.CompareUtils;
import net.maxsmr.commonutils.data.FileHelper;
import net.maxsmr.commonutils.data.Observable;
import net.maxsmr.commonutils.graphic.GraphicUtils;
import net.maxsmr.commonutils.logger.BaseLogger;
import net.maxsmr.commonutils.logger.holder.BaseLoggerHolder;
import net.maxsmr.tasksutils.CustomHandlerThread;
import net.maxsmr.tasksutils.handler.HandlerRunnable;
import net.maxsmr.tasksutils.storage.ids.IdHolder;
import net.maxsmr.tasksutils.storage.sync.AbstractSyncStorage;
import net.maxsmr.tasksutils.taskexecutor.TaskRunnable;
import net.maxsmr.tasksutils.taskexecutor.TaskRunnableExecutor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static net.maxsmr.cameracontroller.camera.settings.photo.CameraSettings.DEFAULT_IMAGE_FORMAT;
import static net.maxsmr.cameracontroller.camera.settings.photo.CameraSettings.DEFAULT_PREVIEW_FORMAT;
import static net.maxsmr.commonutils.android.gui.GuiUtils.getCorrectedDisplayRotation;
import static net.maxsmr.commonutils.android.gui.GuiUtils.getCurrentDisplayOrientation;
import static net.maxsmr.commonutils.android.gui.OrientationIntervalListener.ROTATION_NOT_SPECIFIED;

@SuppressWarnings({"deprecation", "unused", "WeakerAccess", "UnusedReturnValue"})
public class CameraController {

    private static final BaseLogger logger = BaseLoggerHolder.getInstance().getLogger(CameraController.class);

    private static final SimpleDateFormat fileNameDateFormat = new SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.getDefault());

    public static final int DEFAULT_PREVIEW_CALLBACK_BUFFER_QUEUE_SIZE = 3;

    public static final int CAMERA_ID_NONE = -1;
    public static final int CAMERA_ID_BACK = Camera.CameraInfo.CAMERA_FACING_BACK;
    public static final int CAMERA_ID_FRONT = Camera.CameraInfo.CAMERA_FACING_FRONT;

    public static final int CAMERA_ERROR_UNKNOWN = -1;

    public static final int ZOOM_MIN = 0;
    public static final int ZOOM_MAX = -1;
    public static final int ZOOM_NOT_SPECIFIED = -2;

    private static final float ZOOM_GESTURE_SCALER = 1.5f;
    public static final boolean DEFAULT_ENABLE_STORE_LOCATION = true;

    public static final boolean DEFAULT_ENABLE_GESTURE_SCALING = true;

    private static final int DEFAULT_MAKE_PREVIEW_POOL_SIZE = 5;

    private static final int EXECUTOR_CALL_TIMEOUT = 10;

    private static final long AUTO_FOCUS_TIMEOUT = TimeUnit.SECONDS.toMillis(3);

    private final Object sync = new Object();

    private final SurfaceCallbackObservable surfaceHolderCallbacks = new SurfaceCallbackObservable();

    private final CameraStateObservable cameraStateListeners = new CameraStateObservable();

    private final CameraErrorObservable cameraErrorListeners = new CameraErrorObservable();

    private final MediaRecorderErrorObservable mediaRecorderErrorListeners = new MediaRecorderErrorObservable();

    private final PhotoReadyObservable photoReadyListeners = new PhotoReadyObservable();

    private final RecordLimitReachedObservable recordLimitReachedListeners = new RecordLimitReachedObservable();

    private final VideoPreviewObservable videoPreviewListeners = new VideoPreviewObservable();

    private final CustomPreviewCallback previewCallback = new CustomPreviewCallback();

    private final PreviewFrameObservable previewFrameListeners = new PreviewFrameObservable();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ShutterCallback shutterCallbackStub = () -> logger.d("onShutter()");

    private final IdHolder videoPreviewIdsHolder = new IdHolder(0);

    private boolean isReleased = false;

    private Context context;

    private int callbackBufferQueueSize = DEFAULT_PREVIEW_CALLBACK_BUFFER_QUEUE_SIZE;

    private SurfaceView cameraSurfaceView;

    private CameraSurfaceHolderCallback cameraSurfaceHolderCallback;

    private SimpleGestureListener surfaceGestureListener;

    private final OrientationListener orientationListener;

    private boolean isOrientationListened = false;

    private boolean isOrientationListenerEnabled = true;

    // flag is useless because callbacks may not be called (when camera is not opened, for e.g)
//    private boolean isSurfaceCreated = false;

    private boolean enableChangeSurfaceViewSize = false;
    private boolean isFullscreenSurfaceViewSize = false;

    private int surfaceWidth = 0;
    private int surfaceHeight = 0;

    private boolean isPreviewStated = false;

    private Runnable autoFocusResetRunnable;

    @Nullable
    private Handler callbackHandler;

    private Camera camera;

    private int cameraId = CAMERA_ID_NONE;

    private CameraInfo cameraInfo;

    @NotNull
    private CameraState currentCameraState = CameraState.IDLE;

    private boolean isCameraLocked = true;

    private CameraThread cameraThread;

    private boolean isMuteSoundEnabled = false;

    private int previousStreamVolume = -1;

    private Location lastLocation;

    @Nullable
    private VideoSettings currentVideoSettings;

    private long lastTakePhotoStartTime;

    @Nullable
    private File lastPhotoFile;

    @Nullable
    private File lastPreviewFile;

    @Nullable
    private File lastVideoFile;

    @Nullable
    private Progressable progressable;

    private volatile boolean isMediaRecorderRecording = false;

    @Nullable
    private MediaRecorder mediaRecorder;

    private int expectedCallbackBufSize = 0;

    private boolean enableStoreLocation = DEFAULT_ENABLE_STORE_LOCATION;

    private boolean enableGestureScaling = DEFAULT_ENABLE_GESTURE_SCALING;

    private int lastCameraRotation = ROTATION_NOT_SPECIFIED;

    private int lastCameraDisplayRotation = ROTATION_NOT_SPECIFIED;

    /**
     * used for storing and running runnables to save preview for given video
     */
    private TaskRunnableExecutor<MakePreviewRunnableInfo, Void, Void, MakePreviewRunnable> makePreviewThreadPoolExecutor;

    public CameraController(@NotNull Context context, boolean enableFpsLogging) {
        this.context = context;
        enableFpsLogging(enableFpsLogging);
        orientationListener = new OrientationListener(context);
    }

    public boolean isReleased() {
        return isReleased;
    }

    private void checkReleased() {
        if (isReleased) {
            throw new IllegalStateException(CameraController.class.getSimpleName() + " is released");
        }
    }

    public void releaseCameraController() {

        checkReleased();

        releaseMakePreviewThreadPoolExecutor();

        if (isCameraOpened()) {
            releaseCamera();
        }

        surfaceHolderCallbacks.unregisterAll();

        cameraStateListeners.unregisterAll();

        cameraErrorListeners.unregisterAll();

        mediaRecorderErrorListeners.unregisterAll();

        photoReadyListeners.unregisterAll();

        recordLimitReachedListeners.unregisterAll();

        videoPreviewListeners.unregisterAll();

        context = null;

        isReleased = true;
    }

    public Observable<SurfaceHolder.Callback> getSurfaceHolderCallbacks() {
        return surfaceHolderCallbacks;
    }

    public Observable<ICameraStateChangeListener> getCameraStateListeners() {
        return cameraStateListeners;
    }

    public Observable<ICameraErrorListener> getCameraErrorListeners() {
        return cameraErrorListeners;
    }

    public Observable<IMediaRecorderErrorListener> getMediaRecorderErrorListeners() {
        return mediaRecorderErrorListeners;
    }

    public Observable<IPhotoReadyListener> getPhotoReadyListeners() {
        return photoReadyListeners;
    }

    public Observable<IRecordLimitReachedListener> getRecordLimitReachedListeners() {
        return recordLimitReachedListeners;
    }

    public Observable<IVideoPreviewListener> getVideoPreviewListeners() {
        return videoPreviewListeners;
    }

    public Observable<IPreviewFrameListener> getPreviewFrameListeners() {
        return previewFrameListeners;
    }

    public Observable<IFrameStatsListener> getFrameStatsListeners() {
        return previewCallback.getFrameStatsObservable();
    }

    @Nullable
    public Progressable getProgressable() {
        return progressable;
    }

    public void setProgressable(@Nullable Progressable progressable) {
        this.progressable = progressable;
    }

    @NotNull
    public CameraState getCurrentCameraState() {
        return currentCameraState;
    }

    private void setCurrentCameraState(@NotNull CameraState state) {
        synchronized (sync) {
            if (state != currentCameraState) {
                currentCameraState = state;
                logger.i("STATE : " + currentCameraState);
                cameraStateListeners.notifyStateChanged(currentCameraState);
                Runnable run = () -> {
                    if (progressable != null) {
                        if (currentCameraState == CameraState.IDLE) {
                            progressable.onStop();
                        } else {
                            progressable.onStart();
                        }
                    }
                };
                run(run);
            }
        }
    }

    public boolean isCameraBusy() {
        return currentCameraState != CameraState.IDLE;
    }

    private boolean isCameraThreadRunning() {
        return cameraThread != null && cameraThread.isAlive();
    }

    public boolean isStoreLocationEnabled() {
        return enableStoreLocation;
    }

    public void enableStoreLocation(boolean enable) {
        this.enableStoreLocation = enable;
    }

    public boolean isGestureScalingEnabled() {
        return enableGestureScaling;
    }

    public void enableGestureScaling(boolean enable) {
        enableGestureScaling = enable;
    }

    @Nullable
    public CameraSettings getCurrentCameraSettings() {

        if (!isCameraLocked()) {
            logger.e("can't get parameters: camera is not locked");
            return null;
        }

        final Camera.Parameters params;

        try {
            params = camera.getParameters();
        } catch (RuntimeException e) {
            logger.e("a RuntimeException occurred during getParameters()");
            return null;
        }

        Pair<Integer, Integer> previewFpsRange = getCameraPreviewFpsRange();
        return new CameraSettings(ImageFormat.fromValue(params.getPreviewFormat()), ImageFormat.fromValue(params.getPictureFormat()),
                params.getPictureSize(), params.getJpegQuality(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1 && params.getVideoStabilization(),
                previewFpsRange != null && previewFpsRange.first != null ? previewFpsRange.first : 0);
    }

    @Nullable
    public CameraSettings getLowCameraSettings() {

        if (!isCameraLocked()) {
            logger.e("can't get parameters: camera is not locked");
            return null;
        }

        final List<Size> supportedPictureSizes = getSupportedPictureSizes();

        if (supportedPictureSizes == null || supportedPictureSizes.isEmpty()) {
            logger.e("supportedPictureSizes is null or empty");
            return null;
        }


        return new CameraSettings(DEFAULT_IMAGE_FORMAT, DEFAULT_PREVIEW_FORMAT, findLowHighSize(supportedPictureSizes, true), 50,
                CameraSettings.DEFAULT_ENABLE_VIDEO_STABILIZATION, CameraSettings.DEFAULT_PREVIEW_FRAME_RATE);
    }

    @Nullable
    public CameraSettings getMediumCameraSettings() {

        if (!isCameraLocked()) {
            logger.e("can't get parameters: camera is not locked");
            return null;
        }

        final List<Camera.Size> supportedPictureSizes = getSupportedPictureSizes();

        if (supportedPictureSizes == null || supportedPictureSizes.isEmpty()) {
            logger.e("supportedPictureSizes is null or empty");
            return null;
        }

        return new CameraSettings(DEFAULT_IMAGE_FORMAT, DEFAULT_PREVIEW_FORMAT, findMediumSize(supportedPictureSizes), 85,
                CameraSettings.DEFAULT_ENABLE_VIDEO_STABILIZATION, CameraSettings.DEFAULT_PREVIEW_FRAME_RATE);
    }

    @Nullable
    public CameraSettings getHighCameraSettings() {

        if (!isCameraLocked()) {
            logger.e("can't get parameters: camera is not locked");
            return null;
        }

        final List<Camera.Size> supportedPictureSizes = getSupportedPictureSizes();

        if (supportedPictureSizes == null || supportedPictureSizes.isEmpty()) {
            logger.e("supportedPictureSizes is null or empty");
            return null;
        }

        return new CameraSettings(DEFAULT_IMAGE_FORMAT, DEFAULT_PREVIEW_FORMAT, findLowHighSize(supportedPictureSizes, false), 100,
                CameraSettings.DEFAULT_ENABLE_VIDEO_STABILIZATION, CameraSettings.DEFAULT_PREVIEW_FRAME_RATE);
    }

    @Nullable
    public Handler getCallbackHandler() {
        return callbackHandler;
    }

    public void setCallbackHandler(@Nullable Handler callbackHandler) {
        this.callbackHandler = callbackHandler;
    }

    public SurfaceView getCameraSurfaceView() {
        return cameraSurfaceView;
    }


    public void enableChangeSurfaceViewSize(boolean enable, boolean fullscreen /* , Handler uiHandler */) {
        // this.uiHandler = uiHandler;

        enableChangeSurfaceViewSize = enable;
        isFullscreenSurfaceViewSize = fullscreen;
    }

    /**
     * indicates whether surface is fully initialized
     */
    public boolean isSurfaceCreated() {
        return (cameraSurfaceView != null /*&& isSurfaceCreated*/ && !cameraSurfaceView.getHolder().isCreating() && cameraSurfaceView.getHolder().getSurface() != null);
    }

    public boolean isPreviewCallbackBufferUsing() {
        return callbackBufferQueueSize > 0;
    }

    public int getPreviewCallbackBufferQueueSize() {
        return callbackBufferQueueSize;
    }

    public boolean setPreviewCallbackWithBuffer(int queueSize) {
        logger.d("setPreviewCallbackWithBuffer(), queueSize=" + queueSize);

        if (queueSize < 0) {
            logger.e("incorrect queueSize: " + queueSize);
            return false;
        }

        synchronized (sync) {
            if (queueSize != callbackBufferQueueSize) {
                callbackBufferQueueSize = queueSize;
                if (isPreviewStated) {
                    restartPreview();
                }
            }
        }
        return true;
    }

    private byte[] allocatePreviewCallbackBuffer() {

        ImageFormat previewFormat = getCameraPreviewFormat();

        if (previewFormat == null) {
            logger.e("can't get previewFormat");
            return null;
        }

        Camera.Size previewSize = getCameraPreviewSize();

        if (previewSize == null) {
            logger.e("can't get previewSize");
            return null;
        }

        if (previewFormat != ImageFormat.YV12) {

            expectedCallbackBufSize = previewSize.width * previewSize.height * android.graphics.ImageFormat.getBitsPerPixel(previewFormat.getValue()) / 8;

        } else {

            int yStride = (int) Math.ceil(previewSize.width / 16.0) * 16;
            int uvStride = (int) Math.ceil((yStride / 2.0) / 16.0) * 16;
            int ySize = yStride * previewSize.height;
            int uvSize = uvStride * previewSize.height / 2;
            expectedCallbackBufSize = ySize + uvSize * 2;
        }

        // logger.d("preview callback byte buffer size: " + expectedCallbackBufSize);
        return new byte[expectedCallbackBufSize];
    }

    private void setPreviewCallback() {
        synchronized (sync) {
            if (isCameraLocked()) {
                camera.setPreviewCallback(null);
                if (callbackBufferQueueSize > 0) {
                    for (int i = 0; i < callbackBufferQueueSize; i++)
                        camera.addCallbackBuffer(allocatePreviewCallbackBuffer());
                    logger.d("setting preview callback with buffer...");
                    camera.setPreviewCallbackWithBuffer(previewCallback);
                } else {
                    expectedCallbackBufSize = 0;
                    logger.d("setting preview callback...");
                    camera.setPreviewCallback(previewCallback);
                }
            }
        }
    }


    private class CameraSurfaceHolderCallback implements SurfaceHolder.Callback {

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            logger.d("SurfaceHolderCallback :: surfaceCreated()");

            if (isCameraOpened() && holder != null && holder.getSurface() != null) { // isSurfaceCreated() check fails here

                synchronized (sync) {
                    try {
                        camera.setPreviewDisplay(holder);
                    } catch (IOException e) {
                        logger.e("an IOException occurred during setPreviewDisplay()", e);
                    }
                }

                // startPreview();
            }

//            isSurfaceCreated = true;

            surfaceHolderCallbacks.notifySurfaceCreated(holder);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            logger.d("SurfaceHolderCallback :: surfaceChanged(), format=" + format + ", width=" + width + ", height=" + height);

            surfaceWidth = width;
            surfaceHeight = height;

            setupPreview();

            surfaceHolderCallbacks.notifySurfaceChanged(holder, format, width, height);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            logger.d("SurfaceHolderCallback :: surfaceDestroyed()");

            surfaceWidth = 0;
            surfaceHeight = 0;

//            isSurfaceCreated = false;

            stopPreview();

            // optional
            synchronized (sync) {
                try {
                    camera.setPreviewDisplay(null);
                } catch (IOException e) {
                    logger.e("an IOException occurred during setPreviewDisplay()", e);
                }
            }

            surfaceHolderCallbacks.notifySurfaceDestroyed(holder);
        }

    }

    @SuppressWarnings("SuspiciousNameCombination")
    private void setSurfaceViewSize(boolean fullScreen, float scale, SurfaceView surfaceView) {
        logger.d("setSurfaceViewSize(), fullScreen=" + fullScreen + ", scale=" + scale + ", surfaceView=" + surfaceView);

        if (surfaceView == null) {
            logger.e("surfaceView is null");
            return;
        }

        final LayoutParams layoutParams = surfaceView.getLayoutParams();

        if (layoutParams == null) {
            logger.e("layoutParams is null");
            return;
        }

        final Camera.Size previewSize = getCameraPreviewSize();

        if (previewSize == null) {
            logger.e("previewSize is null");
            return;
        }

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        boolean widthIsMax = dm.widthPixels > dm.heightPixels;

        RectF rectDisplay = new RectF();
        RectF rectPreview = new RectF();

        rectDisplay.set(0, 0, dm.widthPixels, dm.heightPixels);

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
                scale = dm.density; // = X dpi / 160 dpi
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

    public boolean setCameraDisplayOrientation(int displayDegrees) {
        logger.d("setCameraDisplayOrientation(), displayDegrees=" + displayDegrees);

        boolean result = false;

        if (displayDegrees >= 0 && displayDegrees < 360) {

            synchronized (sync) {

                if (isCameraOpened() && isCameraLocked()) {

                    int resultDegrees = calculateCameraDisplayOrientation(displayDegrees);

                    try {
                        camera.setDisplayOrientation(resultDegrees);
                        lastCameraDisplayRotation = resultDegrees;
                        result = true;
                    } catch (RuntimeException e) {
                        logger.e("a RuntimeException occurred during setDisplayOrientation()", e);
                    }
                }
            }
        } else {
            logger.e("incorrect displayDegrees: " + displayDegrees);
        }

        return result;
    }

    public boolean setCameraRotation(int displayDegrees) {
        logger.d("setCameraRotation(), displayDegrees=" + displayDegrees);

        boolean result = false;

        if (displayDegrees >= 0 && displayDegrees < 360) {

            synchronized (sync) {

                if (isCameraOpened() && isCameraLocked()) {

                    final int resultDegrees = calculateCameraRotation(displayDegrees);

                    int previousRotation = getLastCameraRotation();

                    if (previousRotation == ROTATION_NOT_SPECIFIED || previousRotation != resultDegrees) {
                        logger.d("camera rotation displayDegrees: " + resultDegrees);
                        try {
                            final Parameters params = camera.getParameters();
                            params.setRotation(resultDegrees);
                            camera.setParameters(params);
                            lastCameraRotation = resultDegrees;
                            if (isOrientationListened) {
                                orientationListener.setLastCorrectedRotation(resultDegrees);
                            }
                            result = true;
                        } catch (RuntimeException e) {
                            logger.e("a RuntimeException occurred during get/set camera parameters", e);
                        }
                    }
                }
            }
        } else {
            logger.e("incorrect displayDegrees: " + displayDegrees);
        }

        return result;
    }

    /**
     * @param displayDegrees [0,360)
     * @return 0, 90, 180, 270
     */
    public int calculateCameraDisplayOrientation(int displayDegrees) {

        int result = 0;

        int rotation = getCorrectedDisplayRotation(displayDegrees);

        if (rotation != ROTATION_NOT_SPECIFIED) {
            synchronized (sync) {

                if (isCameraOpened()) {

                    initCameraInfo();

                    if (cameraInfo != null) {
                        if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                            result = (cameraInfo.orientation + rotation) % 360;
                            result = (360 - result) % 360;  // compensate the mirror
                        } else {  // back-facing
                            result = (cameraInfo.orientation - rotation + 360) % 360;
                        }
                    }
                }
            }
        }

        return result;
    }

    public int calculateCameraRotation(int displayDegrees) {
        int result = 0;

        int rotation = getCorrectedDisplayRotation(displayDegrees);

        if (rotation != ROTATION_NOT_SPECIFIED) {
            rotation = (rotation + 45) / 90 * 90;

            synchronized (sync) {

                if (isCameraOpened()) {

                    initCameraInfo();

                    if (cameraInfo != null) {
                        if (cameraInfo.facing == CameraInfo.CAMERA_FACING_FRONT) {
                            result = (cameraInfo.orientation - rotation + 360) % 360;
                        } else {  // back-facing camera
                            result = (cameraInfo.orientation + rotation) % 360;
                        }
                    }
                }
            }
        }

        return result;
    }

    private void listenOrientationChanges() {
        if (orientationListener != null) {
            if (isOrientationListenerEnabled && !isOrientationListened && isCameraOpened()) {
                try {
                    orientationListener.enable();
                    isOrientationListened = true;
                } catch (Exception e) {
                    logger.e("an Exception occurred", e);
                }
            }
        }
    }

    private void unlistenOrientationChanges() {
        if (orientationListener != null) {
            if (isOrientationListened) {
                try {
                    orientationListener.disable();
                } catch (Exception e) {
                    logger.e("an Exception occurred", e);
                }
                isOrientationListened = false;
            }
        }
    }

    public Location getLastLocation() {
        return lastLocation;
    }

    public void updateLastLocation(Location loc) {
        lastLocation = loc;
    }

    private boolean createCamera(int cameraId, @NotNull SurfaceView surfaceView, @Nullable CameraSettings cameraSettings) {

        synchronized (sync) {

            if (isCameraOpened()) {
                logger.e("camera is already opened");
                return false;
            }

            if (!isCameraIdValid(cameraId)) {
                logger.e("incorrect camera id: " + cameraId);
                return false;
            }

            long startOpenTime = System.currentTimeMillis();

            logger.d("opening camera " + cameraId + "...");
            try {
                camera = Camera.open(cameraId);
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during open()", e);
                return false;
            }

            if (camera == null) {
                logger.e("open camera failed");
                return false;
            }

            camera.setErrorCallback((error, camera) -> {
                logger.e("onError(), error=" + error + ", camera=" + camera);

                switch (error) {
                    case Camera.CAMERA_ERROR_SERVER_DIED:
                        logger.e("camera server died");
                        break;
                    case Camera.CAMERA_ERROR_UNKNOWN:
                        logger.e("camera unknown error");
                        break;
                }

                releaseCamera();

                cameraErrorListeners.notifyCameraError(error);
            });

            CameraController.this.cameraId = cameraId;
            cameraSurfaceView = surfaceView;

            int maxZoom = getMaxZoom();
            if (maxZoom != ZOOM_NOT_SPECIFIED) {
                cameraSurfaceView.setOnTouchListener(surfaceGestureListener = new SimpleGestureListener(surfaceView.getContext(), ZOOM_GESTURE_SCALER * (float) maxZoom));
                surfaceGestureListener.addScaleFactorChangeListener(new ScaleFactorChangeListener());
            }

            final SurfaceHolder cameraSurfaceHolder = surfaceView.getHolder();

            if (cameraSurfaceHolder == null) {
                logger.e("invalid surface, holder is null");
                return false;
            }

            setCameraSettings(cameraSettings);
            int currentRotation = getLastCameraRotation();
            setCameraRotation(currentRotation != ROTATION_NOT_SPECIFIED ? currentRotation : getCurrentDisplayOrientation(context));

            cameraSurfaceHolderCallback = new CameraSurfaceHolderCallback();
            cameraSurfaceHolder.addCallback(cameraSurfaceHolderCallback);

            listenOrientationChanges();

            if (isSurfaceCreated()) {

                logger.d("surface already created, setting preview...");

                try {
                    camera.setPreviewDisplay(cameraSurfaceHolder);
                } catch (IOException e) {
                    logger.e("an IOException occurred during setPreviewDisplay()", e);
                }

                setupPreview();
            }

            logger.d("camera open time: " + (System.currentTimeMillis() - startOpenTime) + " ms");

            return true;
        }
    }

    private void initCameraInfo() {
        synchronized (sync) {
            if (cameraInfo == null) {
                if (isCameraOpened()) {
                    cameraInfo = new CameraInfo();
                    try {
                        Camera.getCameraInfo(cameraId, cameraInfo);
                    } catch (RuntimeException e) {
                        logger.e("a RuntimeException occurred during getCameraInfo()", e);
                        cameraInfo = null;
                    }
                }
            }
        }
    }

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

    @Nullable
    public Camera getOpenedCameraInstance() {
        return isCameraOpened() ? camera : null;
    }

    public int getOpenedCameraId() {
        return cameraId;
    }

    public boolean isCameraOpened() {
        return camera != null && cameraId != CAMERA_ID_NONE;
    }

    public boolean isCameraLocked() {
        return (camera != null && isCameraLocked);
    }

    /**
     * should reconnect to camera object after lock()
     */
    private boolean lockCamera() {
        synchronized (sync) {
            if (camera != null) {

                if (isCameraLocked())
                    return true;

                logger.d("locking camera...");
                try {
                    camera.lock();
                    isCameraLocked = true;
                    return true;
                } catch (Exception e) {
                    logger.e("an Exception occurred during lock()", e);
                }
            }
            return false;
        }
    }

    /**
     * all actions with camera (e.g. getParameters() and setParameters()) will be failed after unlock()!
     */
    private boolean unlockCamera() {
        synchronized (sync) {
            if (camera != null) {

                if (!isCameraLocked())
                    return true;
                logger.d("unlocking camera...");
                try {
                    camera.unlock();
                    isCameraLocked = false;
                    return true;
                } catch (Exception e) {
                    logger.e("an Exception occurred during unlock()", e);
                }
            }
            return false;
        }
    }

    public boolean isPreviewStated() {
        return isPreviewStated;
    }

    /**
     * preview callbacks in camera will be resetted
     */
    private boolean startPreview() {
        synchronized (sync) {
            boolean result = true;
            if (!isPreviewStated) {
                result = false;
                if (isCameraLocked()) {
                    logger.d("starting preview...");
                    try {
                        camera.startPreview();
                        isPreviewStated = true;
                        previewCallback.notifySteamStarted();
                        result = true;
                    } catch (RuntimeException e) {
                        logger.e("a RuntimeException occurred during startPreview()", e);
                    }
                }
            }
            return result;
        }
    }

    private boolean stopPreview() {
        synchronized (sync) {
            boolean result = true;
            if (isPreviewStated) {
                result = false;
                if (isCameraLocked()) {
                    logger.d("stopping preview...");
                    try {
                        camera.stopPreview();
                        isPreviewStated = false;
                        // previewCallback.resetFpsCounter();
                        result = true;
                    } catch (RuntimeException e) {
                        logger.e("a RuntimeException occurred during stopPreview()", e);
                    }
                }
            }
            return result;
        }
    }

    /**
     * restart preview and reset callback
     */
    public boolean restartPreview() {
        boolean result = true;
        if (isPreviewStated) {
            result = stopPreview();
        }
        if (result) {
            result = startPreview();
            if (result) {
                setPreviewCallback();
            }
        }
        return result;
    }

    private boolean setupPreview() {

        if (!isCameraLocked()) {
            logger.e("can't setup preview: camera is not locked");
            return false;
        }

        if (cameraSurfaceView == null) {
            logger.e("can't setup preview: surface is not initialized");
            return false;
        }

        stopPreview();

//        int rotation = orientationListener.getLastRotation();
        setCameraDisplayOrientation(/*rotation != ROTATION_NOT_SPECIFIED &&
                android.provider.Settings.System.getInt(context.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1?
                rotation : */getCurrentDisplayOrientation(context));

        setCameraPreviewSize(getOptimalPreviewSize(getSupportedPreviewSizes(), surfaceWidth, surfaceHeight));

        if (enableChangeSurfaceViewSize)
            setSurfaceViewSize(isFullscreenSurfaceViewSize, 0, cameraSurfaceView);

        if (startPreview()) {
            setPreviewCallback();
        }

        return true;
    }

    public boolean reopenCamera(int cameraId, @NotNull SurfaceView surfaceView, @Nullable CameraSettings cameraSettings, Handler callbackHandler) {
        releaseCamera();
        return openCamera(cameraId, surfaceView, cameraSettings, callbackHandler);
    }

    /**
     * for the first time must be called at onCreate() or onResume() handling surfaceCreated(), surfaceChanged() and
     * surfaceDestroyed() callbacks
     */
    public boolean openCamera(int cameraId, @NotNull SurfaceView surfaceView, @Nullable CameraSettings cameraSettings, Handler callbackHandler) {
        logger.d("openCamera(), cameraId=" + cameraId + ", setMaxFps=" + cameraSettings);

        checkReleased();

        setCallbackHandler(callbackHandler);

        if (isCameraThreadRunning()) {
            logger.e(CameraThread.class.getSimpleName() + " is already running");
            return isCameraOpened();
        }

        // can use new HandlerThread or Looper in new Thread
        // or execute open() on the main thread (but it could reduce performance, including
        // onPreviewFrame() calls)

        final CountDownLatch latch = new CountDownLatch(1);

        cameraThread = new CameraThread(cameraId, surfaceView, cameraSettings, latch);
        cameraThread.setName(CameraThread.class.getSimpleName());
        cameraThread.start();

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            e.printStackTrace();
        }

        final boolean openResult = cameraThread.getOpenResult();

        if (!openResult) {
            cameraThread.quit();
            cameraThread = null;
        }

        return openResult;
        // return createCamera(cameraId, surfaceView, cameraSettings);
    }

    public void releaseCamera() {
        logger.d("releaseCamera()");

        checkReleased();

        synchronized (sync) {

            if (!isCameraOpened()) {
                return;
            }

            if (isMediaRecorderRecording) {
                if (stopRecordVideo() == null) {
                    logger.e("stopRecordVideo() failed");
                }
            }

            setFlashMode(FlashMode.OFF);

            unlistenOrientationChanges();

            setCurrentCameraState(CameraState.IDLE);

            if (cameraSurfaceView != null) {
                if (cameraSurfaceHolderCallback != null) {
                    cameraSurfaceView.getHolder().removeCallback(cameraSurfaceHolderCallback);
                    cameraSurfaceHolderCallback = null;
                }
                if (surfaceGestureListener != null) {
                    cameraSurfaceView.setOnTouchListener(surfaceGestureListener = null);
                }
                cameraSurfaceView = null;
            }

            stopPreview();

            camera.setErrorCallback(null);
            camera.setPreviewCallback(null);
            // camera.setPreviewDisplay(null);

            logger.d("releasing camera " + cameraId + "...");
            camera.release();

            camera = null;
            cameraId = CAMERA_ID_NONE;
            cameraInfo = null;

            lastCameraRotation = ROTATION_NOT_SPECIFIED;
            lastCameraDisplayRotation = ROTATION_NOT_SPECIFIED;

            // cameraSurfaceView = null;

            if (isCameraThreadRunning()) {
                cameraThread.quit();
                cameraThread = null;
            }

            previewCallback.updatePreviewFormat(null);
            previewCallback.updatePreviewSize(null);
        }
    }

    private void muteSound(boolean mute) {
        if (isMuteSoundEnabled) {
            if (android.os.Build.VERSION.SDK_INT >= 17) {
                try {
                    if (isCameraLocked()) {
                        synchronized (sync) {
                            camera.enableShutterSound(!mute);
                        }
                    }
                } catch (RuntimeException e) {
                    logger.e("a RuntimeException occurred during enableShutterSound()", e);
                }
            } else {
                AudioManager mgr = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (mgr != null) {
                    // mgr.setStreamMute(AudioManager.STREAM_SYSTEM, mute);
                    if (mute) {
                        previousStreamVolume = mgr.getStreamVolume(AudioManager.STREAM_SYSTEM);
                        mgr.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
                    } else if (previousStreamVolume >= 0) {
                        mgr.setStreamVolume(AudioManager.STREAM_SYSTEM, previousStreamVolume, 0);
                    }
                }
            }
        }
    }

    public void enableMuteSound(boolean toggle) {
        isMuteSoundEnabled = toggle;
    }

    public void enableOrientationListener(boolean toggle) {
        isOrientationListenerEnabled = toggle;
        if (isOrientationListened != isOrientationListenerEnabled) {
            if (toggle) {
                listenOrientationChanges();
            } else {
                unlistenOrientationChanges();
            }
        }
    }

    /** */
    public int getLastCameraRotation() {
        return isOrientationListened ? orientationListener.getLastCorrectedRotation() : lastCameraRotation;
    }

    public int getLastCameraDisplayRotation() {
        return lastCameraDisplayRotation;
    }

    public List<Camera.Size> getSupportedPreviewSizes() {
        synchronized (sync) {
            try {
                if (isCameraLocked()) {
                    return camera.getParameters().getSupportedPreviewSizes();
                }
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
            }
            return null;
        }
    }

    public List<Camera.Size> getSupportedPictureSizes() {
        synchronized (sync) {
            try {
                if (isCameraLocked()) {
                    return camera.getParameters().getSupportedPictureSizes();
                }
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
            }
            return null;
        }
    }

    public List<Integer> getSupportedPreviewFormats() {
        synchronized (sync) {
            try {
                if (isCameraLocked()) {
                    return camera.getParameters().getSupportedPreviewFormats();
                }
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
            }
            return null;
        }
    }

    public List<Integer> getSupportedPictureFormats() {
        synchronized (sync) {
            try {
                if (isCameraLocked()) {
                    return camera.getParameters().getSupportedPictureFormats();
                }
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
            }
            return null;
        }
    }

    public List<String> getSupportedFlashModes() {
        synchronized (sync) {
            try {
                if (isCameraLocked()) {
                    return camera.getParameters().getSupportedFlashModes();
                }
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
            }
            return null;
        }
    }

    public List<String> getSupportedColorEffects() {
        synchronized (sync) {
            try {
                if (isCameraLocked()) {
                    return camera.getParameters().getSupportedColorEffects();
                }
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
            }
            return null;
        }
    }

    public List<String> getSupportedFocusModes() {
        synchronized (sync) {
            try {
                if (isCameraLocked()) {
                    return camera.getParameters().getSupportedFocusModes();
                }
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
            }
            return null;
        }
    }

    public Camera.Size getCameraPreviewSize() {

        synchronized (sync) {

            if (!isCameraLocked()) {
                logger.e("can't get parameters: camera is not locked");
                return null;
            }
            final Parameters params;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return null;
            }

            return params.getPreviewSize();
        }
    }

    public boolean setCameraPreviewSize(Camera.Size size) {
        logger.d("setCameraPreviewSize(), size=" + size);

        if (size == null) {
            logger.e("size is null");
            return false;
        }

        return setCameraPreviewSize(size.width, size.height);
    }

    public boolean setCameraPreviewSize(int width, int height) {
        logger.d("setCameraPreviewSize(), width=" + width + ", height=" + height);

        synchronized (sync) {

            if (isMediaRecorderRecording) {
                logger.e("can't set preview size: media recorder is recording");
                return false;
            }

            if (width <= 0 || height <= 0) {
                logger.e("incorrect size: " + width + "x" + height);
                return false;
            }

            if (!isCameraLocked()) {
                logger.e("can't get/set parameters: camera is not locked");
                return false;
            }

            final Parameters params;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            boolean isPreviewSizeChanged = false;

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
                logger.e("no supported preview sizes");
            }

            if (!isPreviewSizeSupported) {
                logger.e(" _ preview size " + width + "x" + height + " is NOT supported");
                return false;

            } else {
                logger.d(" _ preview size " + width + "x" + height + " is supported");
                Size currentSize = params.getPreviewSize();
                if (currentSize.width != width || currentSize.height != height) {
                    params.setPreviewSize(width, height);
                    isPreviewSizeChanged = true;
                }
            }

            if (isPreviewSizeChanged) {
                try {
                    camera.setParameters(params);
                } catch (RuntimeException e) {
                    logger.e("a RuntimeException occurred during setParameters()", e);
                    return false;
                }

                previewCallback.updatePreviewSize(width, height);

                if (isPreviewStated) {
                    // restart preview and reset callback
                    restartPreview();
                }
            }

            return true;
        }
    }

    public ImageFormat getCameraPreviewFormat() {

        synchronized (sync) {

            if (!isCameraLocked()) {
                logger.e("can't get parameters: camera is not locked");
                return null;
            }

            final Parameters params;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return null;
            }

            return ImageFormat.fromValue(params.getPreviewFormat());
        }
    }

    private boolean setCameraPreviewFormat(ImageFormat previewFormat) {
        logger.d("setCameraPreviewFormat(), previewFormat=" + previewFormat);

        synchronized (sync) {


            if (previewFormat == null) {
                logger.e("previewFormat is null");
                return false;
            }

            if (!isCameraLocked()) {
                logger.e("can't get/set parameters: camera is not locked");
                return false;
            }

            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return false;
            }


            try {
                camera.setParameters(params);
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during setParameters()", e);
                return false;
            }


            return true;
        }
    }

    /**
     * @return range the minimum and maximum preview fps
     */
    @Nullable
    public Pair<Integer, Integer> getCameraPreviewFpsRange() {

        synchronized (sync) {

            if (!isCameraLocked()) {
                logger.e("can't get parameters: camera is not locked");
                return null;
            }

            final Parameters params;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return null;
            }

            int[] scaledRange = new int[2];
            params.getPreviewFpsRange(scaledRange);
            return new Pair<>((int) (scaledRange[0] / 1000d), (int) (scaledRange[1] / 1000d));
        }
    }

    /**
     * used to change permanent or temp (during taking photo or recording video) params for opened camera by given PHOTO
     * settings
     *
     * @param cameraSettings incorrect parameters will be replaced with actual
     */
    public boolean setCameraSettings(CameraSettings cameraSettings) {
        logger.d("setCameraSettings(), cameraSettings=" + cameraSettings);

        synchronized (sync) {

            if (cameraSettings == null) {
                return false;
            }

            if (!isCameraLocked()) {
                logger.e("can't get parameters: camera is not locked");
                return false;
            }

//            if (isMediaRecorderRecording) {
//                logger.e("can't set parameters: media recorder is recording");
//                return false;
//            }

            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            boolean isPictureSizeSupported = false;

            int pictureWidth = cameraSettings.getPictureWidth();
            int pictureHeight = cameraSettings.getPictureHeight();

            if (pictureWidth > 0 && pictureHeight > 0) {
                final List<Camera.Size> supportedPictureSizes = params.getSupportedPictureSizes();
                if (supportedPictureSizes != null && supportedPictureSizes.size() != 0) {
                    for (int i = 0; i < supportedPictureSizes.size(); i++) {
                        if ((supportedPictureSizes.get(i).width == pictureWidth)
                                && (supportedPictureSizes.get(i).height == pictureHeight)) {
                            isPictureSizeSupported = true;
                            break;
                        }
                    }
                } else {
                    logger.e("no supported pictures sizes");
                }
            }

            if (isPictureSizeSupported) {
                logger.d(" _ picture size " + pictureWidth + "x" + pictureHeight
                        + " is supported");
                params.setPictureSize(pictureWidth, pictureHeight);
            } else {
                logger.e(" _ picture size " + pictureWidth + "x" + pictureHeight
                        + " is NOT supported");

            }

            boolean isPreviewFormatChanged = false;

            boolean isPreviewFormatSupported = false;

            ImageFormat previewFormat = cameraSettings.getPreviewFormat();

            if (previewFormat != null) {
                final List<Integer> supportedPreviewFormats = params.getSupportedPreviewFormats();
                if (supportedPreviewFormats != null && supportedPreviewFormats.size() != 0) {
                    for (int i = 0; i < supportedPreviewFormats.size(); i++) {
                        if (previewFormat.getValue() == supportedPreviewFormats.get(i)) {
                            isPreviewFormatSupported = true;
                            break;
                        }
                    }
                } else {
                    logger.e("no supported preview formats");
                }
            }

            if (isPreviewFormatSupported) {
                logger.d(" _ preview format " + previewFormat + " is supported");
                if (params.getPreviewFormat() != previewFormat.getValue()) {
                    params.setPreviewFormat(previewFormat.getValue());
                    isPreviewFormatChanged = true;
                }
            } else {
                logger.e(" _ preview format " + previewFormat + " is NOT supported");
            }

            boolean isPictureFormatSupported = false;

            ImageFormat pictureFormat = cameraSettings.getPictureFormat();

            if (pictureFormat != null) {
                final List<Integer> supportedPictureFormats = params.getSupportedPictureFormats();
                if (supportedPictureFormats != null && supportedPictureFormats.size() != 0) {
                    for (int i = 0; i < supportedPictureFormats.size(); i++) {
                        if (pictureFormat.getValue() == supportedPictureFormats.get(i)) {
                            isPictureFormatSupported = true;
                            break;
                        }
                    }
                } else {
                    logger.e("no supported picture formats for");
                }
            }

            if (isPictureFormatSupported) {
                logger.d(" _ picture format " + pictureFormat + " is supported");
                params.setPictureFormat(pictureFormat.getValue());
            } else {
                logger.e(" _ picture format " + pictureFormat + " is NOT supported");
            }

            int jpegQuality = cameraSettings.getJpegQuality();

            if (jpegQuality > 0 && jpegQuality <= 100) {
                logger.d(" _ JPEG quality " + jpegQuality + " is supported");
                params.setJpegQuality(jpegQuality);
                params.setJpegThumbnailQuality(jpegQuality);
            } else {
                logger.e("incorrect jpeg quality: " + jpegQuality);
            }

            boolean isPreviewFpsRangeChanged = false;

            int previewFps = cameraSettings.getPreviewFrameRate();
            int[] fpsRange;

            List<int[]> supportedPreviewFpsRanges = camera.getParameters().getSupportedPreviewFpsRange();

            if (supportedPreviewFpsRanges != null && !supportedPreviewFpsRanges.isEmpty()) {

                if (previewFps == CameraSettings.PREVIEW_FRAME_RATE_AUTO) {
                    fpsRange = findLowHighRange(supportedPreviewFpsRanges, false);
                } else {
                    fpsRange = new int[]{previewFps * 1000, previewFps * 1000};
                }

                boolean isPreviewFpsRangeSupported = false;

                if (fpsRange != null) {
                    for (int i = 0; i < supportedPreviewFpsRanges.size(); i++) {

                        final int supportedMinFpsScaled = supportedPreviewFpsRanges.get(i)[Parameters.PREVIEW_FPS_MIN_INDEX];
                        final int supportedMaxFpsScaled = supportedPreviewFpsRanges.get(i)[Parameters.PREVIEW_FPS_MAX_INDEX];

                        if (fpsRange[0] >= supportedMinFpsScaled && fpsRange[0] <= supportedMaxFpsScaled
                                && fpsRange[1] >= supportedMinFpsScaled && fpsRange[1] <= supportedMaxFpsScaled) {
                            isPreviewFpsRangeSupported = true;
                            break;
                        }
                    }
                }

                if (isPreviewFpsRangeSupported) {
                    logger.d(" _ FPS range " + fpsRange[0] / 1000 + " .. " + fpsRange[1] / 1000 + " is supported");
                    int[] currentRange = new int[2];
                    params.getPreviewFpsRange(currentRange);
                    if (currentRange[0] != fpsRange[0] || currentRange[1] != fpsRange[1]) {
                        params.setPreviewFpsRange(fpsRange[0], fpsRange[1]);
                        isPreviewFpsRangeChanged = true;
                    }
                } else {
                    logger.e(" _ FPS range " +
                            (fpsRange != null ? fpsRange[0] / 1000 + " .. " + fpsRange[1] / 1000 : null) + " is NOT supported");
                }

            } else {
                logger.e("no supported preview fps ranges");
            }


            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                if (params.isVideoStabilizationSupported()) {
                    logger.d(" _ video stabilization is supported");
                    params.setVideoStabilization(cameraSettings.isVideoStabilizationEnabled());
                } else {
                    logger.e(" _ video stabilization is NOT supported");
                }
            }

            try {
                camera.setParameters(params);
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during setParameters()", e);
                return false;
            }

            if (isPreviewFormatChanged || isPreviewFpsRangeChanged) {
                if (isPreviewFormatChanged) {
                    previewCallback.updatePreviewFormat(previewFormat);
                }
                if (isPreviewStated) {
                    restartPreview();
                }
            }

            return true;
        }
    }

    public boolean setColorEffect(@NotNull ColorEffect colorEffect) {

        synchronized (sync) {

            if (!isCameraLocked()) {
                logger.e("can't get parameters: camera is not locked");
                return false;
            }

            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            boolean isColorEffectSupported = false;

            final List<String> supportedColorEffects = params.getSupportedColorEffects();

            if (supportedColorEffects != null && !supportedColorEffects.isEmpty()) {
                for (String supportedColorEffect : supportedColorEffects) {
                    if (CompareUtils.stringsEqual(colorEffect.getValue(), supportedColorEffect, false)) {
                        isColorEffectSupported = true;
                    }
                }
            } else {
                logger.e("no supported color effects");
            }

            boolean changed = false;

            if (isColorEffectSupported) {
                logger.d(" _ color effect " + colorEffect.getValue() + " is supported");
                if (!CompareUtils.stringsEqual(params.getColorEffect(), colorEffect.getValue(), false)) {
                    params.setColorEffect(colorEffect.getValue());
                    changed = true;
                }
            } else {
                logger.e(" _ color effect " + colorEffect.getValue() + " is NOT supported");
                return false;
            }

            if (changed) {
                try {
                    camera.setParameters(params);
                } catch (RuntimeException e) {
                    logger.e("a RuntimeException occurred during setParameters()", e);
                    return false;
                }
            }

            return true;
        }
    }

    @Nullable
    public FocusMode getFocusMode() {
        synchronized (sync) {

            if (!isCameraLocked()) {
                logger.e("can't get parameters: camera is not locked");
                return null;
            }

            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return null;
            }

            return FocusMode.fromValue(params.getFocusMode());
        }
    }

    public boolean setFocusMode(@NotNull FocusMode focusMode) {

        synchronized (sync) {

            if (!isCameraLocked()) {
                logger.e("can't get parameters: camera is not locked");
                return false;
            }

            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            boolean isFocusModeSupported = false;

            final List<String> supportedFocusModes = params.getSupportedFocusModes();

            if (supportedFocusModes != null && !supportedFocusModes.isEmpty()) {
                for (String supportedFocusMode : supportedFocusModes) {
                    if (CompareUtils.stringsEqual(focusMode.getValue(), supportedFocusMode, true)) {
                        isFocusModeSupported = true;
                        break;
                    }
                }
            } else {
                logger.e("no supported focus modes");
            }

            boolean changed = false;

            if (!isFocusModeSupported) {
                logger.d(" _ focus mode " + focusMode.getValue() + " is supported");
                if (!CompareUtils.stringsEqual(params.getFocusMode(), focusMode.getValue(), false)) {
                    params.setFocusMode(focusMode.getValue());
                    changed = true;
                }
            } else {
                logger.e(" _ focus mode " + focusMode.getValue() + " is NOT supported");
                return false;
            }

            if (changed) {
                try {
                    camera.setParameters(params);
                } catch (RuntimeException e) {
                    logger.e("a RuntimeException occurred during setParameters()", e);
                    return false;
                }
            }

            return changed;
        }
    }

    @Nullable
    public FlashMode getFlashMode() {

        synchronized (sync) {

            if (!isCameraLocked()) {
                logger.e("can't get parameters: camera is not locked");
                return null;
            }

            Camera.Parameters params;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return null;
            }

            return FlashMode.fromValue(params.getFlashMode());
        }
    }

    public boolean setFlashMode(@NotNull FlashMode flashMode) {

        synchronized (sync) {

            if (!isCameraLocked()) {
                logger.e("can't get parameters: camera is not locked");
                return false;
            }

            Camera.Parameters params;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            boolean isFlashModeSupported = false;

            final List<String> supportedFlashModes = params.getSupportedFlashModes();

            if (supportedFlashModes != null && !supportedFlashModes.isEmpty()) {
                for (int i = 0; i < supportedFlashModes.size(); i++) {
                    if (CompareUtils.stringsEqual(flashMode.getValue(), supportedFlashModes.get(i), false)) {
                        isFlashModeSupported = true;
                        break;
                    }
                }
            } else {
                logger.e("no supported flash modes");
            }

            boolean changed = false;

            if (isFlashModeSupported) {
                logger.d(" _ flash mode " + flashMode.getValue() + " is supported");
                if (!CompareUtils.stringsEqual(params.getFlashMode(), flashMode.getValue(), false)) {
                    params.setFlashMode(flashMode.getValue());
                    changed = true;
                }
            } else {
                logger.e(" _ flash mode " + flashMode.getValue() + " is NOT supported");
                return false;
            }

            if (changed) {
                try {
                    camera.setParameters(params);
                } catch (RuntimeException e) {
                    logger.e("a RuntimeException occurred during setParameters()", e);
                    return false;
                }
            }

            return true;
        }
    }

    public boolean enableFlash(boolean enable) {
        boolean result;
        if (enable) {
            result = setFlashMode(FlashMode.TORCH);
            if (!result) {
                result = setFlashMode(FlashMode.ON);
            }
        } else {
            result = setFlashMode(FlashMode.OFF);
        }
        return result;
    }

    private static boolean isExposureCompensationSupported(@Nullable Pair<Integer, Integer> range) {
        Integer first = null;
        Integer second = null;
        if (range != null) {
            first = range.first;
            second = range.second;
        }
        return first != null && first != 0 && second != null && second != 0;
    }

    public boolean isExposureCompensationSupported() {
        return isExposureCompensationSupported(getMinMaxExposureCompensation());
    }

    @Nullable
    public Pair<Integer, Integer> getMinMaxExposureCompensation() {

        synchronized (sync) {

            if (!isCameraLocked()) {
                logger.e("can't get parameters: camera is not locked");
                return null;
            }

            final Camera.Parameters params;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return null;
            }

            return new Pair<>(params.getMinExposureCompensation(), params.getMaxExposureCompensation());
        }
    }

    public int getExposureCompensation() {

        synchronized (sync) {

            if (!isCameraLocked()) {
                logger.e("can't get parameters: camera is not locked");
                return 0;
            }

            final Camera.Parameters params;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return 0;
            }

            return params.getExposureCompensation();
        }
    }

    @SuppressWarnings("ConstantConditions")
    public boolean setExposureCompensation(int value) {

        synchronized (sync) {

            if (!isCameraLocked()) {
                logger.e("can't get parameters: camera is not locked");
                return false;
            }

            final Camera.Parameters params;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            Pair<Integer, Integer> range = getMinMaxExposureCompensation();

            if (!isExposureCompensationSupported(range)) {
                logger.e("exposure compensation is not supported");
                return false;
            }

            if (value < range.first || value > range.second) {
                logger.e("incorrect exposure compensation value: " + value);
                return false;
            }

            boolean changed = false;

            if (params.getExposureCompensation() != value) {
                params.setExposureCompensation(value);
                changed = true;
            }

            if (changed) {
                try {
                    camera.setParameters(params);
                } catch (RuntimeException e) {
                    logger.e("a RuntimeException occurred during setParameters()", e);
                    return false;
                }
            }

            return true;
        }
    }

    @NotNull
    public static Pair<WhiteBalance, WhiteBalance> getMinMaxWhiteBalance() {
        return new Pair<>(WhiteBalance.getMinMaxValue(true), WhiteBalance.getMinMaxValue(false));
    }

    @SuppressWarnings("ConstantConditions")
    @NotNull
    public static Pair<Integer, Integer> getMinMaxWhiteBalanceId() {
        Pair<WhiteBalance, WhiteBalance> pair = getMinMaxWhiteBalance();
        return new Pair<>(pair.first.getId(), pair.second.getId());
    }

    @Nullable
    public WhiteBalance getWhiteBalance() {

        synchronized (sync) {

            if (!isCameraLocked()) {
                logger.e("can't get parameters: camera is not locked");
                return null;
            }

            final Camera.Parameters params;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return null;
            }

            return WhiteBalance.fromValue(params.getWhiteBalance());
        }
    }

    public boolean setWhiteBalance(@NotNull WhiteBalance whiteBalance) {

        synchronized (sync) {

            if (!isCameraLocked()) {
                logger.e("can't get parameters: camera is not locked");
                return false;
            }


            final Camera.Parameters params;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            boolean changed = false;

            if (!CompareUtils.stringsEqual(params.getWhiteBalance(), whiteBalance.getValue(), false)) {
                params.setWhiteBalance(whiteBalance.getValue());
                changed = true;
            }

            if (changed) {
                try {
                    camera.setParameters(params);
                } catch (RuntimeException e) {
                    logger.e("a RuntimeException occurred during setParameters()", e);
                    return false;
                }
            }

            return true;
        }
    }

    public int getMaxZoom() {
        synchronized (sync) {
            if (!isCameraLocked()) {
                logger.e("can't get parameters: camera is not locked");
                return ZOOM_NOT_SPECIFIED;
            }

            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return ZOOM_NOT_SPECIFIED;
            }

            return params.isZoomSupported() ? params.getMaxZoom() : ZOOM_NOT_SPECIFIED;
        }
    }


    public int getZoom() {
        synchronized (sync) {
            if (!isCameraLocked()) {
                logger.e("can't get parameters: camera is not locked");
                return ZOOM_NOT_SPECIFIED;
            }

            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return ZOOM_NOT_SPECIFIED;
            }

            return params.isZoomSupported() ? params.getZoom() : ZOOM_NOT_SPECIFIED;
        }
    }

    public boolean setZoom(int zoom) {

        synchronized (sync) {

            if (!isCameraLocked()) {
                logger.e("can't get parameters: camera is not locked");
                return false;
            }

            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            boolean changed = false;

            if (zoom != ZOOM_NOT_SPECIFIED) {
                if (zoom > 0 || zoom == ZOOM_MIN || zoom == ZOOM_MAX) {
                    if (params.isZoomSupported()) {
                        logger.d("zoom is supported");
                        int maxZoom = params.getMaxZoom();
                        zoom = zoom == ZOOM_MAX ? maxZoom : zoom;
                        if (zoom <= maxZoom || zoom == ZOOM_MIN) {
                            if (zoom != params.getZoom()) {
                                params.setZoom(zoom);
                                changed = true;
                            }
                        } else {
                            logger.e("incorrect zoom level: " + zoom);
                        }
                    } else {
                        logger.e(" _ zoom is NOT supported");
                        return false;
                    }
                } else {
                    logger.e("incorrect zoom level: " + zoom);
                    return false;
                }
            }

            if (changed) {
                try {
                    camera.setParameters(params);
                } catch (RuntimeException e) {
                    logger.e("a RuntimeException occurred during setParameters()", e);
                    return false;
                }
            }
        }

        return true;
    }

    public boolean takePhoto(String photoDirectoryPath, String photoFileName, final boolean writeToFile) {
        logger.d("takePhoto(), photoDirectoryPath=" + photoDirectoryPath + ", photoFileName=" + photoFileName + ", writeToFile=" + writeToFile);

        checkReleased();

        if (!TextUtils.isEmpty(photoFileName) && photoFileName.contains(File.separator)) {
            logger.e("photo file name " + photoFileName + " contains path separators!");
            return false;
        }

        synchronized (sync) {

            if (currentCameraState != CameraState.IDLE) {
                logger.e("current camera state is not IDLE! state is " + currentCameraState);
                return currentCameraState == CameraState.TAKING_PHOTO;
            }

            if (!isCameraLocked()) {
                logger.e("camera is not locked");
                return false;
            }

            if (!isSurfaceCreated()) {
                logger.e("surface is not created");
                return false;
            }

            lastTakePhotoStartTime = System.currentTimeMillis();

            CameraSettings currentCameraSettings = getCurrentCameraSettings();

            if (currentCameraSettings == null) {
                logger.e("can't retrieve current camera settings");
                return false;
            }

            Camera.Parameters params = null;

            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
            }

            FocusMode focusMode = null;

            if (params != null) {
                focusMode = FocusMode.fromValue(params.getFocusMode());
            }

            if (writeToFile) {
                if (TextUtils.isEmpty(photoFileName)) {
                    photoFileName = makeNewFileName(CameraState.TAKING_PHOTO, new Date(System.currentTimeMillis()), new Pair<>(currentCameraSettings.getPictureWidth(), currentCameraSettings.getPictureHeight()), "jpg");
                } else {
                    photoFileName = FileHelper.removeExtension(photoFileName) + ".jpg";
                }

                if ((this.lastPhotoFile = FileHelper.checkPathNoThrow(photoDirectoryPath, photoFileName)) == null) {
                    logger.e("incorrect photo path: " + photoDirectoryPath + File.separator + photoFileName);
                    return false;
                }
            } else {
                this.lastPhotoFile = null;
            }

            setCurrentCameraState(CameraState.TAKING_PHOTO);

            if (focusMode == FocusMode.AUTO) {

                cancelResetAutoFocusCallback();

                camera.cancelAutoFocus();

                cameraThread.addTask(autoFocusResetRunnable = () -> {
                    logger.e("auto focus callback not triggered");
                    synchronized (sync) {
                        autoFocusResetRunnable = null;
                        if (isCameraLocked()) {
                            camera.cancelAutoFocus();
                            takePhotoInternal(writeToFile);
                        }
                    }
                }, AUTO_FOCUS_TIMEOUT);

                logger.i("taking photo with auto focus...");
                camera.autoFocus((success, camera) -> {
                    logger.d("onAutoFocus(), success=" + true);
                    cancelResetAutoFocusCallback();
                    takePhotoInternal(writeToFile);
                });

            } else {
                logger.i("taking photo without auto focus...");
                takePhotoInternal(writeToFile);
            }

            return true;
        }
    }

    private void takePhotoInternal(final boolean writeToFile) {
        muteSound(true);
        isPreviewStated = false;
        try {
            executor.submit(() -> {
                camera.takePicture(isMuteSoundEnabled ? null : shutterCallbackStub,
                        new PictureRawCallback(), new PictureReadyCallback(writeToFile));
                return true;
            }).get(EXECUTOR_CALL_TIMEOUT, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.e("an Exception occurred during get()", e);
            reopenCamera(cameraId, cameraSurfaceView, getCurrentCameraSettings(), callbackHandler);
        }
    }

    private void cancelResetAutoFocusCallback() {
        if (autoFocusResetRunnable != null) {
            cameraThread.removeTask(autoFocusResetRunnable);
            autoFocusResetRunnable = null;
        }
    }

    private class PictureRawCallback implements Camera.PictureCallback {

        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            logger.d(PictureRawCallback.class.getSimpleName() + " :: onPictureTaken()");

            if (isReleased()) {
                return;
            }

            synchronized (sync) {

                if (currentCameraState != CameraState.TAKING_PHOTO) {
                    throw new IllegalStateException("current camera state is not " + CameraState.TAKING_PHOTO);
                }

                if (data != null) {
                    photoReadyListeners.notifyRawDataReady(data);
                }
            }
        }
    }

    private class PictureReadyCallback implements Camera.PictureCallback {

        private final boolean writeToFile;

        PictureReadyCallback(boolean writeToFile) {
            this.writeToFile = writeToFile;
        }

        @Override
        public void onPictureTaken(final byte[] data, Camera camera) {
            logger.d(PictureReadyCallback.class.getSimpleName() + " :: onPictureTaken()");

            if (isReleased()) {
                return;
            }

            synchronized (sync) {

                if (currentCameraState != CameraState.TAKING_PHOTO) {
                    throw new IllegalStateException("current camera state is not " + CameraState.TAKING_PHOTO);
                }

                File lastPhotoFile = writeToFile ? CameraController.this.lastPhotoFile : null;
                final Location lastLocation = CameraController.this.lastLocation;

                logger.d("last photo file: " + lastPhotoFile);
                if (lastPhotoFile != null) {
                    if (FileHelper.writeBytesToFile(lastPhotoFile, data, false)) {
                        if (isStoreLocationEnabled() && lastLocation != null)
                            if (!FileHelper.writeExifLocation(lastPhotoFile, lastLocation)) {
                                logger.e("can't write location to exif");
                            }
                    } else {
                        logger.e("can't write picture data to file");
                        lastPhotoFile = null;
                    }
                }

                if (startPreview()) {
                    setPreviewCallback();
                }

                setCurrentCameraState(CameraState.IDLE);

                muteSound(false);

                final long currentTime = System.currentTimeMillis();
                final long elapsedTime = lastTakePhotoStartTime >= 0 && lastTakePhotoStartTime <= currentTime ? currentTime - lastTakePhotoStartTime : 0;

                if (lastPhotoFile != null) {
                    photoReadyListeners.notifyPhotoFileReady(lastPhotoFile, elapsedTime);
                } else {
                    if (data != null) {
                        photoReadyListeners.notifyPhotoDataReady(data, elapsedTime);
                    }
                }
            }

        }
    }

    public List<Camera.Size> getSupportedVideoSizes() {
        synchronized (sync) {
            try {
                if (isCameraLocked()) {
                    return camera.getParameters().getSupportedVideoSizes();
                }
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
            }
            return null;
        }
    }

    public VideoSettings getLowVideoSettings() {

        if (!isCameraLocked()) {
            logger.e("can't get parameters: camera is not locked");
            return null;
        }

        final Size lowVideoSize = findLowHighSize(getSupportedVideoSizes(), true);
        if (lowVideoSize != null) {
            logger.d(" _ low VIDEO size: " + lowVideoSize.width + "x" + lowVideoSize.height);
        } else {
            logger.e(" _ low VIDEO size is null");
        }

        return new VideoSettings(cameraId, VideoQuality.LOW, VideoEncoder.H264, AudioEncoder.AAC, false,
                lowVideoSize, VideoSettings.VIDEO_FRAME_RATE_MAX,
                VideoSettings.DEFAULT_ENABLE_MAKE_PREVIEW, VideoSettings.DEFAULT_PREVIEW_GRID_SIZE);
    }

    public VideoSettings getMediumVideoSettings() {

        if (!isCameraLocked()) {
            logger.e("can't get parameters: camera is not locked");
            return null;
        }

        final Size mediumVideoSize = findMediumSize(getSupportedVideoSizes());
        if (mediumVideoSize != null) {
            logger.d(" _ medium VIDEO size: " + mediumVideoSize.width + "x" + mediumVideoSize.height);
        } else {
            logger.e(" _ medium VIDEO size is null");
        }

        return new VideoSettings(cameraId, VideoQuality.DEFAULT, VideoEncoder.H264, AudioEncoder.AAC, false,
                mediumVideoSize, VideoSettings.VIDEO_FRAME_RATE_MAX,
                VideoSettings.DEFAULT_ENABLE_MAKE_PREVIEW, VideoSettings.DEFAULT_PREVIEW_GRID_SIZE);
    }

    public VideoSettings getHighVideoSettings() {

        if (!isCameraLocked()) {
            logger.e("can't get parameters: camera is not locked");
            return null;
        }

        final Size highVideoSize = findLowHighSize(getSupportedVideoSizes(), false);
        if (highVideoSize != null) {
            logger.d(" _ high VIDEO size: " + highVideoSize.width + "x" + highVideoSize.height);
        } else {
            logger.e(" _ high VIDEO size is null");
        }

        return new VideoSettings(cameraId, VideoQuality.HIGH, VideoEncoder.H264, AudioEncoder.AAC, false,
                highVideoSize, VideoSettings.VIDEO_FRAME_RATE_MAX,
                VideoSettings.DEFAULT_ENABLE_MAKE_PREVIEW, VideoSettings.DEFAULT_PREVIEW_GRID_SIZE);
    }

    public boolean isVideoSizeSupported(int width, int height) {

        if (!isCameraLocked()) {
            logger.e("can't get parameters: camera is not locked");
            return false;
        }

        boolean isVideoSizeSupported = false;
        final List<Camera.Size> supportedVideoSizes = getSupportedVideoSizes();
        if (supportedVideoSizes != null) {
            if (width > 0 && height > 0) {
                for (int i = 0; i < supportedVideoSizes.size(); i++) {
                    if (width == supportedVideoSizes.get(i).width && height == supportedVideoSizes.get(i).height) {
                        isVideoSizeSupported = true;
                        break;
                    }
                }
            }
        }
        return isVideoSizeSupported;
    }

    /**
     * @param profile       if is not null, it will be setted to recorder (re-filled if necessary); otherwise single
     *                      parameters will be setted in appropriate order without profile
     * @param videoSettings must not be null
     * @param fpsRange      if is not null and requested fps falls within the range, it will be applied
     */
    private boolean setMediaRecorderParams(CamcorderProfile profile, VideoSettings videoSettings, @Nullable Pair<Integer, Integer> fpsRange) {
        logger.d("setMediaRecorderParams(), profile=" + profile + ", videoSettings=" + videoSettings + ", fpsRange=" + fpsRange);

        if (videoSettings == null) {
            logger.e("can't set media recorder parameters: videoSettings is null");
            return false;
        }

        if (mediaRecorder == null) {
            logger.e("can't set media recorder parameters: mediaRecorder is null");
            return false;
        }

        if (isMediaRecorderRecording) {
            logger.e("can't set media recorder parameters: mediaRecorder is already recording");
            return false;
        }

        int videoFrameRate;

        if (profile == null) {
            videoFrameRate = videoSettings.getVideoFrameRate();
            if ((fpsRange == null || fpsRange.first == null || fpsRange.second == null || videoFrameRate < fpsRange.first || videoFrameRate > fpsRange.second) ||
                    videoFrameRate == VideoSettings.VIDEO_FRAME_RATE_AUTO) {

                if (previewCallback.allowLogging) {
                    if (Double.compare(previewCallback.getLastFps(), 0) == 0 && Thread.currentThread() != cameraThread) {
                        // wait for count
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            logger.e("an InterruptedException occurred during sleep()", e);
                            Thread.currentThread().interrupt();
                        }
                    }
                    videoFrameRate = (int) previewCallback.getLastFps();
                }

                if (videoFrameRate == 0) {
                    videoFrameRate = VideoSettings.VIDEO_FRAME_RATE_MAX;
                }
            }
        } else {
            videoFrameRate = profile.videoFrameRate;
        }

        // set sources -> setProfile
        // OR
        // set sources -> setOutputFormat -> setVideoFrameRate -> setVideoSize -> set audio/video encoding bitrate ->
        // set encoders

        if (profile != null) {

            logger.d(camcorderProfileToString(profile));

            if (videoSettings.getVideoEncoder() != VideoEncoder.DEFAULT && profile.videoCodec != videoSettings.getVideoEncoder().getValue()) {
                logger.d("videoCodec in profile (" + profile.videoCodec + ") is not the same as in videoSettings ("
                        + videoSettings.getVideoEncoder().getValue() + "), changing...");
                profile.videoCodec = videoSettings.getVideoEncoder().getValue();
                profile.fileFormat = getOutputFormatByVideoEncoder(videoSettings.getVideoEncoder());
            }
            videoSettings.setVideoEncoder(VideoEncoder.fromValue(profile.videoCodec));

            if (videoSettings.getAudioEncoder() != AudioEncoder.DEFAULT && profile.audioCodec != videoSettings.getAudioEncoder().getValue()) {
                logger.d("audioCodec in profile (" + profile.audioCodec + ") is not the same as in videoSettings ("
                        + videoSettings.getAudioEncoder().getValue() + "), changing...");
                profile.audioCodec = videoSettings.getAudioEncoder().getValue();
            }
            videoSettings.setAudioEncoder(AudioEncoder.fromValue(profile.audioCodec));

            if (!videoSettings.isAudioDisabled()) {

                try {

                    logger.d("setting profile " + profile.quality + "...");
                    mediaRecorder.setProfile(profile);

                } catch (RuntimeException e) {
                    logger.e("a RuntimeException occurred during setProfile()", e);
                    return false;
                }

            } else {

                logger.d("recording audio disabled, setting parameters manually by profile " + profile.quality + "...");

                mediaRecorder.setOutputFormat(profile.fileFormat);
                mediaRecorder.setVideoFrameRate(profile.videoFrameRate);
                mediaRecorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
                mediaRecorder.setVideoEncodingBitRate(profile.videoBitRate);
                mediaRecorder.setVideoEncoder(profile.videoCodec);
                mediaRecorder.setVideoFrameRate(videoFrameRate);
            }

        } else {

            logger.d("profile is null, setting parameters manually...");

            mediaRecorder.setOutputFormat(getOutputFormatByVideoEncoder(videoSettings.getVideoEncoder()));
            logger.d("output format: " + getOutputFormatByVideoEncoder(videoSettings.getVideoEncoder()));

            mediaRecorder.setVideoFrameRate(videoFrameRate);
            logger.d("video frame rate: " + videoFrameRate);

            if (videoSettings.getVideoFrameWidth() > 0 && videoSettings.getVideoFrameHeight() > 0) {
                mediaRecorder.setVideoSize(videoSettings.getVideoFrameWidth(), videoSettings.getVideoFrameHeight());
                logger.d("video frame size: width=" + videoSettings.getVideoFrameWidth() + ", height="
                        + videoSettings.getVideoFrameHeight());
            } else {
                logger.e("incorrect video frame size: " + videoSettings.getVideoFrameWidth() + "x"
                        + videoSettings.getVideoFrameHeight());
            }

            if (!videoSettings.isAudioDisabled()) {
                mediaRecorder.setAudioEncoder(videoSettings.getAudioEncoder().getValue());
                logger.d("audio encoder: " + videoSettings.getAudioEncoder());
            } else {
                logger.d("recording audio disabled");
            }

            mediaRecorder.setVideoEncoder(videoSettings.getVideoEncoder().getValue());
            logger.d("video encoder: " + videoSettings.getVideoEncoder());
        }

        return true;
    }

    public boolean hasLastPhotoFile() {
        return GraphicUtils.canDecodeImage(lastPhotoFile);
    }

    public boolean hasLastVideoFile() {
        return FileHelper.isFileCorrect(lastVideoFile);
    }

    public boolean hasLastPreviewFile() {
        return GraphicUtils.canDecodeImage(lastPreviewFile);
    }

    @Nullable
    public File getLastPhotoFile() {
        return lastPhotoFile;
    }

    @Nullable
    public File getLastVideoFile() {
        return lastVideoFile;
    }

    @Nullable
    public File getLastPreviewFile() {
        return lastPreviewFile;
    }

    /**
     * must be called after setOutputFormat()
     */
    private void setVideoRecordLimit(VideoRecordLimit recLimit) {
        logger.d("setVideoRecordLimit(), recLimit=" + recLimit);

        if (mediaRecorder == null) {
            logger.e("can't set video record limit: mediaRecorder is null");
            return;
        }

        if (isMediaRecorderRecording) {
            logger.e("can't set video record limit: mediaRecorder is already recording");
            return;
        }

        if (recLimit != null) {
            switch (recLimit.getRecordLimitWhat()) {
                case TIME:
                    if (recLimit.getRecordLimitValue() > 0) {
                        logger.d("setting max duration " + (int) recLimit.getRecordLimitValue() + "...");
                        mediaRecorder.setMaxDuration((int) recLimit.getRecordLimitValue());
                    }
                    break;
                case SIZE:
                    if (recLimit.getRecordLimitValue() > 0) {
                        logger.d("setting max file size " + recLimit.getRecordLimitValue() + "...");
                        mediaRecorder.setMaxFileSize(recLimit.getRecordLimitValue());
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private boolean setRecordingHint(boolean hint) {

        synchronized (sync) {

            if (!isCameraLocked()) {
                logger.e("can't get/set parameters: camera is not locked");
                return false;
            }

            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            params.setRecordingHint(hint);

            boolean result = false;

            try {
                camera.setParameters(params);
                result = true;
            } catch (RuntimeException e) {
                logger.e("a RuntimeException occurred during setParameters()", e);
            }

            if (result && isPreviewStated) {
                restartPreview();
            }

            return result;
        }
    }

    private boolean prepareMediaRecorder(@NotNull VideoSettings videoSettings, @NotNull VideoRecordLimit recLimit, String saveDirectoryPath, String fileName) {

        synchronized (sync) {

            final long startPrepareTime = System.currentTimeMillis();

            if (!isCameraOpened()) {
                logger.e("camera is not opened");
                return false;
            }

            if (!isSurfaceCreated()) {
                logger.e("surface is not created");
                return false;
            }

            if (isMediaRecorderRecording) {
                logger.e("mediaRecorder is already recording");
                return false;
            }

            this.currentVideoSettings = videoSettings;

            CamcorderProfile profile = null;

            // for video file name
            Pair<Integer, Integer> resolution = null;

            if (videoSettings.getQuality() != VideoQuality.DEFAULT) {

                if (CamcorderProfile.hasProfile(cameraId, videoSettings.getQuality().getValue())) {
                    logger.i("profile " + videoSettings.getQuality().getValue() + " is supported for camera with id " + cameraId
                            + ", getting...");
                    profile = CamcorderProfile.get(cameraId, videoSettings.getQuality().getValue());
                } else {
                    logger.e("profile " + videoSettings.getQuality().getValue() + " is NOT supported, changing quality to default...");
                    videoSettings.setQuality(cameraId, VideoQuality.DEFAULT);
                }
            }


            if (profile == null) {
                if (isVideoSizeSupported(videoSettings.getVideoFrameWidth(), videoSettings.getVideoFrameHeight())) {
                    logger.d("VIDEO size " + videoSettings.getVideoFrameWidth() + "x" + videoSettings.getVideoFrameHeight()
                            + " is supported");
                } else {
                    logger.e("VIDEO size " + videoSettings.getVideoFrameWidth() + "x" + videoSettings.getVideoFrameHeight()
                            + " is NOT supported, getting medium...");
                    final VideoSettings mediumVideoSettings = getMediumVideoSettings();
                    videoSettings.setVideoFrameSize(mediumVideoSettings.getVideoFrameWidth(), mediumVideoSettings.getVideoFrameHeight());
                }
                resolution = new Pair<>(videoSettings.getVideoFrameWidth(), videoSettings.getVideoFrameHeight());
            }

            final Pair<Integer, Integer> previewFpsRange = getCameraPreviewFpsRange();

            final boolean wasStarted = isPreviewStated;

            stopPreview();

            setRecordingHint(true);

            if (!unlockCamera()) {
                logger.e("unlocking camera failed");
                if (wasStarted) {
                    if (startPreview()) {
                        setPreviewCallback();
                    }
                }
                return false;
            }

            mediaRecorder = new MediaRecorder();

            mediaRecorder.setOnErrorListener((mediaRecorder, what, extra) -> {
                logger.e("onError(), what=" + what + ", extra=" + extra);
                if (isReleased()) {
                    return;
                }
                stopRecordVideo();
                mediaRecorderErrorListeners.notifyMediaRecorderError(what, extra);
            });

            mediaRecorder.setOnInfoListener((mediaRecorder, what, extra) -> {

                switch (what) {
                    case MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED:
                    case MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED:
                        logger.d("onInfo(), what=" + what + ", extra=" + extra);
                        if (isReleased()) {
                            return;
                        }
                        recordLimitReachedListeners.notifyRecordLimitReached(stopRecordVideo());
                        break;
                }
            });

            mediaRecorder.setCamera(camera);

            if (!videoSettings.isAudioDisabled())
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);

            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

            if (isStoreLocationEnabled() && lastLocation != null) {
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                    logger.d("set video location: latitude " + lastLocation.getLatitude() + " longitude " + lastLocation.getLongitude()
                            + " accuracy " + lastLocation.getAccuracy());
                    mediaRecorder.setLocation((float) lastLocation.getLatitude(), (float) lastLocation.getLongitude());
                }
            }

            if (!setMediaRecorderParams(profile, videoSettings, previewFpsRange)) {
                logger.e("setMediaRecorderParams() failed");

                releaseMediaRecorder();
                return false;
            }

            setVideoRecordLimit(recLimit);

            if (TextUtils.isEmpty(fileName)) {
                fileName = makeNewFileName(CameraState.RECORDING_VIDEO, new Date(System.currentTimeMillis()), resolution, getFileExtensionByVideoEncoder(videoSettings.getVideoEncoder()));
            } else {
                fileName = FileHelper.removeExtension(fileName) + "." + getFileExtensionByVideoEncoder(videoSettings.getVideoEncoder());
            }

            lastVideoFile = FileHelper.createNewFile(fileName, saveDirectoryPath);
            logger.i("lastVideoFile: " + lastVideoFile);

            if (lastVideoFile == null) {
                logger.e("can't create video file");
                return false;
            }

            mediaRecorder.setOutputFile(lastVideoFile.getAbsolutePath());

            mediaRecorder.setPreviewDisplay(cameraSurfaceView.getHolder().getSurface());

            int currentRotation = getLastCameraRotation();
            mediaRecorder.setOrientationHint(currentRotation != ROTATION_NOT_SPECIFIED ? currentRotation : calculateCameraRotation(getCurrentDisplayOrientation(context)));

            try {
                mediaRecorder.prepare();
            } catch (Exception e) {
                logger.e("an Exception occurred during prepare()", e);
                releaseMediaRecorder();
                return false;
            }

            logger.d("media recorder prepare time: " + (System.currentTimeMillis() - startPrepareTime) + " ms");
            return true;
        }
    }

    private boolean releaseMediaRecorder() {
        logger.d("releaseMediaRecorder()");

        synchronized (sync) {

            try {
                return executor.submit(() -> {
                    if (mediaRecorder != null) {

                        final long startReleaseTime = System.currentTimeMillis();

                        mediaRecorder.setOnErrorListener(null);
                        mediaRecorder.setOnInfoListener(null);

                        mediaRecorder.reset();
                        mediaRecorder.release();

                        logger.d("media recorder release time: " + (System.currentTimeMillis() - startReleaseTime) + " ms");
                        return true;
                    }
                    return false;
                }).get(EXECUTOR_CALL_TIMEOUT, TimeUnit.SECONDS);

            } catch (Exception e) {
                logger.e("an Exception occurred during get()", e);

                return false;

            } finally {

                mediaRecorder = null;

                if (!lockCamera()) {
                    logger.e("locking camera failed");
                }

                try {
                    camera.reconnect();
                } catch (IOException e) {
                    logger.e("an IOException occures during reconnect()", e);
                }

                if (callbackBufferQueueSize > 0)
                    setRecordingHint(false);

                if (startPreview()) {
                    setPreviewCallback();
                }
            }
        }
    }

    public boolean startRecordVideo(@NotNull VideoSettings videoSettings, @NotNull VideoRecordLimit recLimit, String saveDirectoryPath, String fileName) {
        logger.d("startRecordVideo(), videoSettings=" + videoSettings + "deleteTempVideoFiles="
                + ", recLimit=" + recLimit + ", saveDirectoryPath=" + saveDirectoryPath + ", fileName=" + fileName);

        checkReleased();

        synchronized (sync) {

            if (currentCameraState != CameraState.IDLE) {
                logger.e("current camera state is not IDLE! state is " + currentCameraState);
                return currentCameraState == CameraState.RECORDING_VIDEO;
            }

            if (prepareMediaRecorder(videoSettings, recLimit, saveDirectoryPath, fileName)) {

                final long startStartingTime = System.currentTimeMillis();
                logger.i("starting record video...");

                muteSound(true);

                try {
                    mediaRecorder.start();
                } catch (IllegalStateException e) {
                    logger.e("an IllegalStateException occurred during start()", e);
                    releaseMediaRecorder();
                    return false;
                }
                logger.d("video recording has been started (" + (System.currentTimeMillis() - startStartingTime) + " ms)");

                muteSound(false);

                setCurrentCameraState(CameraState.RECORDING_VIDEO);

                isMediaRecorderRecording = true;

                return true;
            }

            releaseMediaRecorder();

            return false;
        }
    }

    public File stopRecordVideo() {
        logger.d("stopRecordVideo()");

        checkReleased();

        synchronized (sync) {

            if (isMediaRecorderRecording) {

                muteSound(true);

                try {
                    executor.submit(() -> {
                        if (mediaRecorder != null) {

                            final long startStoppingTime = System.currentTimeMillis();
                            logger.i("stopping record video...");
                            mediaRecorder.stop();
                            logger.d("video recording has been stopped (" + (System.currentTimeMillis() - startStoppingTime) + " ms)");

                            return true;
                        } else {
                            return false;
                        }
                    }).get(EXECUTOR_CALL_TIMEOUT, TimeUnit.SECONDS);

                } catch (Exception e) {
                    logger.e("an Exception occurred during get()", e);
                    mediaRecorder = null;
                }

                muteSound(false);

                isMediaRecorderRecording = false;

                setCurrentCameraState(CameraState.IDLE);

            } else {
                logger.e("mediaRecorder is not recording");
                return null;
            }

            releaseMediaRecorder();

            if (makePreviewThreadPoolExecutor != null && lastVideoFile != null) {
                try {
                    makePreviewThreadPoolExecutor.execute(new MakePreviewRunnable(new MakePreviewRunnableInfo(videoPreviewIdsHolder.incrementAndGet(), lastVideoFile.getName(), currentVideoSettings, lastVideoFile)));
                } catch (NullPointerException e) {
                    logger.e("a NullPointerException occurred during getName(): " + e.getMessage());
                }
            } else {
                logger.e("makePreviewThreadPoolExecutor is null");
            }

            currentVideoSettings = null;

            return lastVideoFile;
        }
    }

    public void initMakePreviewThreadPoolExecutor(int poolSize,
                                                  TaskRunnable.ITaskResultValidator<MakePreviewRunnableInfo, Void, Void, MakePreviewRunnable> validator,
                                                  AbstractSyncStorage<MakePreviewRunnableInfo> storage,
                                                  TaskRunnable.ITaskRestorer<MakePreviewRunnableInfo, Void, Void, MakePreviewRunnable> restorer,
                                                  Handler callbackHandler
    ) {
        logger.d("initMakePreviewThreadPoolExecutor(), poolSize=" + poolSize);

        releaseMakePreviewThreadPoolExecutor();

        makePreviewThreadPoolExecutor = new TaskRunnableExecutor<>(DEFAULT_MAKE_PREVIEW_POOL_SIZE, 1, TaskRunnableExecutor.DEFAULT_KEEP_ALIVE_TIME, TimeUnit.SECONDS, "MakePreviewThread",
                validator, storage, callbackHandler);
        if (restorer != null) {
            makePreviewThreadPoolExecutor.restoreQueueByRestorer(restorer);
        }
    }

    public void releaseMakePreviewThreadPoolExecutor() {

        if (makePreviewThreadPoolExecutor == null) {
            return;
        }

        logger.d("releaseMakePreviewThreadPoolExecutor()");

        makePreviewThreadPoolExecutor.shutdown();
        makePreviewThreadPoolExecutor = null;
    }

    public void enableFpsLogging(boolean enable) {
        previewCallback.setAllowLogging(enable);
    }

    @NotNull
    public FrameCalculator getFrameCalculator() {
        return previewCallback;
    }

    private void run(Runnable run) {
        if (run != null) {
            if (callbackHandler != null) {
                callbackHandler.post(run);
            } else {
                run.run();
            }
        }
    }

    @Nullable
    public static Size findLowHighSize(@Nullable List<Size> sizeList, boolean isLow) {

        if (sizeList == null || sizeList.isEmpty()) {
            return null;
        }

        Size result = sizeList.get(0);
        for (Size size : sizeList) {
            if (size != null) {
                if (result == null || (isLow ? size.width < result.width : size.width > result.width)) {
                    result = size;
                }
            }
        }
        return result;
    }

    @Nullable
    public static Size findMediumSize(@Nullable List<Size> sizeList) {

        if (sizeList == null || sizeList.isEmpty()) {
            return null;
        }

        Size lowPreviewSize = findLowHighSize(sizeList, true);
        Size highPreviewSize = findLowHighSize(sizeList, false);

        if (lowPreviewSize == null || highPreviewSize == null) {
            return highPreviewSize != null ? highPreviewSize : lowPreviewSize;
        }

        int mediumWidth = (lowPreviewSize.width + highPreviewSize.width) / 2;

        Size mediumSize = sizeList.get(0);
        int mediumDiff = Math.abs(mediumSize.width - mediumWidth);

        int diff;
        for (Size size : sizeList) {
            if (size != null) {
                diff = Math.abs(size.width - mediumWidth);
                if (diff < mediumDiff) {
                    mediumDiff = diff;
                    mediumSize = size;
                }
            }
        }

        return mediumSize;
    }

    @Nullable
    public static int[] findLowHighRange(@Nullable List<int[]> ranges, boolean isLow) {
        int[] result = null;
        if (ranges != null) {
            for (int[] range : ranges) {
                if (range != null && range.length == 2) {
                    if (result == null || (isLow ? range[0] < result[0] : range[0] > result[0])) {
                        result = range;
                    }
                }
            }
        }
        return result;
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

    private static String getFileExtensionByVideoEncoder(VideoEncoder videoEncoder) {
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

    private static int getOutputFormatByVideoEncoder(VideoEncoder videoEncoder) {
        int format = MediaRecorder.OutputFormat.DEFAULT;
        if (videoEncoder != null) {
            switch (videoEncoder) {
                case H263:
                    format = MediaRecorder.OutputFormat.THREE_GPP;
                    break;
                case H264:
                case MPEG_4_SP:
                    format = MediaRecorder.OutputFormat.MPEG_4;
                    break;
            }
        }
        return format;
    }

    private static String makeNewFileName(CameraState state, Date date, Pair<Integer, Integer> resolution, String ext) {
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
                name.append(fileNameDateFormat.format(date));
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

    private static String camcorderProfileToString(CamcorderProfile profile) {
        List<String> result = new ArrayList<>();
        result.add("quality=" + profile.quality);
        result.add("videoCodec=" + profile.videoCodec);
        result.add("audioCodec=" + profile.audioCodec);
        result.add("videoBitRate=" + profile.videoBitRate);
        result.add("audioBitRate=" + profile.audioBitRate);
        result.add("audioChannels=" + profile.audioChannels);
        result.add("audioSampleRate=" + profile.audioSampleRate);
        result.add("fileFormat=" + profile.fileFormat);
        result.add("videoFrameRate=" + profile.videoFrameRate);
        result.add("videoFrameWidth=" + profile.videoFrameWidth + ", videoFrameHeight=" + profile.videoFrameHeight);
        return "==CamcorderProfile=="
                + System.getProperty("line.separator")
                + TextUtils.join(", ", result);
    }

    public class MakePreviewRunnable extends TaskRunnable<MakePreviewRunnableInfo, Void, Void> {

        private final MakePreviewRunnableInfo rInfo;

        public MakePreviewRunnable(MakePreviewRunnableInfo rInfo) {
            super(rInfo);
            this.rInfo = rInfo;
        }

        @Override
        public Void doWork() {
            doMakePreview();
            return null;
        }

        private void doMakePreview() {
            logger.d("doMakePreview()");

            if (!FileHelper.isFileCorrect(rInfo.videoFile)) {
                logger.e("incorrect video file: " + rInfo.videoFile + ", size: " + rInfo.videoFile.length() / 1024 + " kB, " + ", exists: " + (rInfo.videoFile != null && rInfo.videoFile.exists()));

                videoPreviewListeners.notifyPreviewFailed(rInfo.videoFile);

                return;
            }

            MediaMetadataRetriever retriever = MetadataRetriever.createMediaMetadataRetriever(context, Uri.fromFile(rInfo.videoFile), null);
            final Bitmap firstFrame = MetadataRetriever.extractFrameAtPosition(retriever, 1, false);
            final Bitmap lastFrame = MetadataRetriever.extractFrameAtPosition(retriever,
                    (long) (MetadataRetriever.extractMediaDuration(retriever, false) * 0.95), true);

            if (rInfo.videoSettings == null || !rInfo.videoSettings.isMakePreviewEnabled()) {
                logger.w("making preview is NOT enabled");

                lastPreviewFile = null;

            } else {
                logger.d("making preview is enabled");

                Bitmap previewBitmap = GraphicUtils.makePreviewFromVideoFile(rInfo.videoFile, rInfo.videoSettings.getPreviewGridSize(), true);
                if (!GraphicUtils.isBitmapCorrect(previewBitmap)) {
                    logger.e("incorrect preview bitmap: " + previewBitmap);
                    return;
                }
                lastPreviewFile = new File(rInfo.videoFile.getParentFile(), rInfo.videoFile.getName() + GraphicUtils.getFileExtByCompressFormat(Bitmap.CompressFormat.PNG));
                lastPreviewFile = GraphicUtils.compressBitmapToFile(lastPreviewFile, previewBitmap, Bitmap.CompressFormat.PNG, 100);
            }

            if (lastPreviewFile != null) {
                videoPreviewListeners.notifyPreviewReady(lastPreviewFile, firstFrame, lastFrame, rInfo.videoFile);
            }
        }
    }

    private class CameraThread extends CustomHandlerThread {

        private final int cameraId;
        @NotNull
        private final SurfaceView surfaceView;
        @Nullable
        private final CameraSettings cameraSettings;
        @Nullable
        private final CountDownLatch latch;

        private boolean openResult = false;

        private CreateCameraRunnable runnable;

        CameraThread(final int cameraId, @NotNull final SurfaceView surfaceView, @Nullable CameraSettings cameraSettings, @Nullable final CountDownLatch latch) {
            super(CameraThread.class.getSimpleName());
            this.cameraId = cameraId;
            this.surfaceView = surfaceView;
            this.cameraSettings = cameraSettings;
            this.latch = latch;
        }

        boolean getOpenResult() {
            return openResult;
        }

        boolean isCreateCameraRunning() {
            return runnable.getStatus() == AsyncTask.Status.RUNNING;
        }

        @Override
        protected void onLooperPrepared() {
            super.onLooperPrepared();
            addTask(runnable = new CreateCameraRunnable(this));
        }
    }

    private class CreateCameraRunnable extends HandlerRunnable<Boolean> {

        final CameraThread cameraThread;

        public CreateCameraRunnable(CameraThread cameraThread) {
            this.cameraThread = cameraThread;
        }

        @Override
        protected Boolean doWork() {
            cameraThread.openResult = createCamera(cameraThread.cameraId, cameraThread.surfaceView, cameraThread.cameraSettings);
            if (cameraThread.latch != null) {
                cameraThread.latch.countDown();
            }
            return cameraThread.openResult;
        }
    }

    protected class CustomPreviewCallback extends FrameCalculator implements Camera.PreviewCallback {

        private boolean allowLogging;

        private long recorderInterval = 0;

        private ImageFormat previewFormat;

        private int previewWidth = -1;
        private int previewHeight = -1;

        CustomPreviewCallback() {
            super(callbackHandler != null ? callbackHandler.getLooper() : Looper.getMainLooper());
        }

        public void setAllowLogging(boolean allow) {
            allowLogging = allow;
        }

        /**
         * for network streaming
         */
        private boolean allowRecord(double targetFps) {
            if (System.nanoTime() - recorderInterval >= (1000000000d / targetFps)) {
                recorderInterval = System.nanoTime();
                return true;
            }
            return false;
        }

        public void updatePreviewFormat(ImageFormat previewFormat) {
            this.previewFormat = previewFormat;
        }

        public void updatePreviewSize(Size previewSize) {
            if (previewSize != null) {
                updatePreviewSize(previewSize.width, previewSize.height);
            }
        }

        public void updatePreviewSize(int width, int height) {
            this.previewWidth = width;
            this.previewHeight = height;
        }

        @Override
        public void notifySteamStarted() {
            super.notifySteamStarted();
            previewFrameListeners.notifyPreviewStarted();
        }

        @Override
        public void notifyStreamFinished() {
            super.notifyStreamFinished();
            previewFrameListeners.notifyPreviewFinished();
        }

        @Override
        public void onPreviewFrame(final byte[] data, final Camera camera) {
//            logger.d("onPreviewFrame(), data (length)=" + (data != null ? data.length : 0));

            if (isReleased()) {
                return;
            }

            if (data == null) {
                logger.e("data is null");
                return;
            }

            synchronized (sync) {

                final long frameTime;

                if (data.length != expectedCallbackBufSize && expectedCallbackBufSize > 0) {
                    logger.w("frame data size (" + data.length + ") is not equal expected (" + expectedCallbackBufSize + ")");
                }

                if (allowLogging) {
                    frameTime = onFrame();
                } else {
                    frameTime = System.nanoTime();
                }

                if (isCameraLocked() && callbackBufferQueueSize > 0) {
                    camera.addCallbackBuffer(data);
                }

                previewFrameListeners.notifyPreviewFrame(data, frameTime);
            }
        }
    }

    protected class ScaleFactorChangeListener implements SimpleGestureListener.ScaleFactorChangeListener {

        @Override
        public void onScaleFactorChanged(double from, double to) {
            logger.d("onScaleFactorChanged(), from=" + from + ", to=" + to);

            if (!enableGestureScaling) {
                return;
            }

            if (!isCameraOpened()) {
                return;
            }

            double scaleDiff;
            int zoomDiff;

            final int maxZoom = getMaxZoom();
            final int currentZoom = getZoom();
            int newZoom;

            if (maxZoom == ZOOM_NOT_SPECIFIED || currentZoom == ZOOM_NOT_SPECIFIED) {
                logger.e("zooming is not supported");
                return;
            }

            logger.d("current zoom: " + currentZoom);

            final int compare = Double.compare(from, to);
            if (compare > 0) { // zoom out
                scaleDiff = from - to;
                zoomDiff = (int) (scaleDiff / ZOOM_GESTURE_SCALER);
                if (currentZoom > ZOOM_MIN) {
                    newZoom = currentZoom - zoomDiff;
                    newZoom = newZoom < ZOOM_MIN ? ZOOM_MIN : newZoom;
                } else {
                    newZoom = 0;
                }
                logger.d("zooming out (" + newZoom + ") ...");
            } else if (compare < 0) { // zoom in
                scaleDiff = to - from;
                zoomDiff = (int) (scaleDiff / ZOOM_GESTURE_SCALER);
                if (currentZoom < maxZoom) {
                    newZoom = currentZoom + zoomDiff;
                    newZoom = newZoom > maxZoom ? maxZoom : newZoom;
                } else {
                    newZoom = maxZoom;
                }
                logger.d("zooming in (" + newZoom + ") ...");
            } else {
                scaleDiff = 0;
                zoomDiff = 0;
                newZoom = currentZoom;
                logger.d("zoom unchanged (" + newZoom + ") ...");
            }

            logger.d("scale diff: " + scaleDiff + ", zoom diff: " + zoomDiff);

            if (newZoom != currentZoom && (newZoom >= ZOOM_MIN && newZoom <= maxZoom)) {
                logger.d("setting new zoom: " + newZoom);
                if (setZoom(newZoom)) {
                    if (newZoom == ZOOM_MIN) {
                        surfaceGestureListener.setTo(SimpleGestureListener.MIN_SCALE_FACTOR);
                    } else if (newZoom == maxZoom) {
                        surfaceGestureListener.setTo((float) surfaceGestureListener.getThresholdValue());
                    }
                } else {
                    logger.e("cant' set camera zoom: " + newZoom);
                }
            }

        }
    }

    protected class OrientationListener extends OrientationIntervalListener {

        public OrientationListener(Context context) {
            super(context, SensorManager.SENSOR_DELAY_NORMAL, OrientationListener.NOTIFY_INTERVAL_NOT_SPECIFIED, ROTATION_NOT_SPECIFIED);
        }

        @Override
        protected void doAction(int orientation) {
//            if (!isCameraBusy()) {
            setCameraRotation(orientation);
//            }
        }
    }

    protected class SurfaceCallbackObservable extends Observable<SurfaceHolder.Callback> {

        void notifySurfaceCreated(final SurfaceHolder surfaceHolder) {
            Runnable run = () -> {
                synchronized (observers) {
                    for (SurfaceHolder.Callback l : observers) {
                        l.surfaceCreated(surfaceHolder);
                    }
                }
            };
            run(run);
        }

        void notifySurfaceChanged(final SurfaceHolder surfaceHolder, final int format, final int width, final int height) {
            Runnable run = () -> {
                synchronized (observers) {
                    for (SurfaceHolder.Callback l : observers) {
                        l.surfaceChanged(surfaceHolder, format, width, height);
                    }
                }
            };
            run(run);
        }

        void notifySurfaceDestroyed(final SurfaceHolder surfaceHolder) {
            Runnable run = () -> {
                synchronized (observers) {
                    for (SurfaceHolder.Callback l : observers) {
                        l.surfaceDestroyed(surfaceHolder);
                    }
                }
            };
            run(run);
        }
    }

    protected class CameraStateObservable extends Observable<ICameraStateChangeListener> {

        void notifyStateChanged(@NotNull final CameraState state) {
            Runnable run = () -> {
                synchronized (observers) {
                    for (ICameraStateChangeListener l : observers) {
                        l.onCameraStateChanged(state);
                    }
                }
            };
            run(run);
        }
    }

    protected class CameraErrorObservable extends Observable<ICameraErrorListener> {

        void notifyCameraError(final int error) {
            Runnable run = () -> {
                synchronized (observers) {
                    for (ICameraErrorListener l : observers) {
                        l.onCameraError(error);
                    }
                }
            };
            run(run);
        }
    }

    protected class MediaRecorderErrorObservable extends Observable<IMediaRecorderErrorListener> {

        void notifyMediaRecorderError(final int error, final int extra) {
            Runnable run = () -> {
                synchronized (observers) {
                    for (IMediaRecorderErrorListener l : observers) {
                        l.onMediaRecorderError(error, extra);
                    }
                }
            };
            run(run);
        }
    }

    protected class PhotoReadyObservable extends Observable<IPhotoReadyListener> {

        void notifyRawDataReady(@NotNull final byte[] rawData) {
            Runnable run = () -> {
                synchronized (observers) {
                    for (IPhotoReadyListener l : observers) {
                        l.onRawDataReady(rawData);
                    }
                }
            };
            run(run);
        }

        void notifyPhotoFileReady(@NotNull final File photoFile, final long elapsedTime) {
            Runnable run = () -> {
                synchronized (observers) {
                    for (IPhotoReadyListener l : observers) {
                        l.onPhotoFileReady(photoFile, elapsedTime);
                    }
                }
            };
            run(run);
        }

        void notifyPhotoDataReady(@NotNull final byte[] photoData, final long elapsedTime) {
            Runnable run = () -> {
                synchronized (observers) {
                    for (IPhotoReadyListener l : observers) {
                        l.onPhotoDataReady(photoData, elapsedTime);
                    }
                }
            };
            run(run);
        }
    }

    protected class RecordLimitReachedObservable extends Observable<IRecordLimitReachedListener> {

        void notifyRecordLimitReached(final File videoFile) {
            Runnable run = () -> {
                synchronized (observers) {
                    for (IRecordLimitReachedListener l : observers) {
                        l.onRecordLimitReached(videoFile);
                    }
                }
            };
            run(run);
        }

    }

    protected class VideoPreviewObservable extends Observable<IVideoPreviewListener> {

        void notifyPreviewFailed(final File videoFile) {
            Runnable run = () -> {
                synchronized (observers) {
                    for (IVideoPreviewListener l : observers) {
                        l.onVideoPreviewFailed(videoFile);
                    }
                }
            };
            run(run);
        }

        void notifyPreviewReady(@NotNull final File previewFile, @Nullable final Bitmap firstFrame, @Nullable final Bitmap lastFrame, @NotNull final File videoFile) {
            Runnable run = () -> {
                synchronized (observers) {
                    for (IVideoPreviewListener l : observers) {
                        l.onVideoPreviewReady(previewFile, firstFrame, lastFrame, videoFile);
                    }
                }
            };
            run(run);
        }
    }

    protected class PreviewFrameObservable extends Observable<IPreviewFrameListener> {

        void notifyPreviewStarted() {
            Runnable run = () -> {
                synchronized (observers) {
                    for (IPreviewFrameListener l : observers) {
                        l.onPreviewStarted();
                    }
                }
            };
            run(run);
        }

        void notifyPreviewFinished() {
            Runnable run = () -> {
                synchronized (observers) {
                    for (IPreviewFrameListener l : observers) {
                        l.onPreviewFinished();
                    }
                }
            };
            run(run);
        }

        void notifyPreviewFrame(@Nullable final byte[] data, final long time) {
            Runnable run = () -> {
                if (data != null && data.length > 0) {
                    synchronized (observers) {
                        for (IPreviewFrameListener l : observers) {
                            l.onPreviewFrame(data, time);
                        }
                    }
                }
            };
            run(run);
        }
    }

    public interface ICameraStateChangeListener {

        void onCameraStateChanged(@NotNull CameraState currentState);
    }

    public interface ICameraErrorListener {

        void onCameraError(int error);
    }

    public interface IMediaRecorderErrorListener {

        void onMediaRecorderError(int error, int extra);
    }

    public interface IPhotoReadyListener {

        void onRawDataReady(@NotNull byte[] rawData);

        void onPhotoFileReady(@NotNull File photoFile, long time);

        void onPhotoDataReady(@NotNull byte[] photoData, long time);
    }

    public interface IRecordLimitReachedListener {

        void onRecordLimitReached(@NotNull File videoFile);
    }

    /**
     * preview will be created asynchronously (if it's allowed), result will be delivered to onVideoPreviewReady()
     * callback
     */
    public interface IVideoPreviewListener {

        void onVideoPreviewFailed(@NotNull File videoFile);

        /**
         * preview for its video file is ready; invokes from the other thread
         */
        void onVideoPreviewReady(@NotNull File previewFile, @Nullable Bitmap firstFrame, @Nullable Bitmap lastFrame, @NotNull File videoFile);
    }

    public interface IPreviewFrameListener {

        void onPreviewStarted();

        void onPreviewFinished();

        /**
         * @param time frame time in ns
         */
        void onPreviewFrame(@NotNull byte[] data, long time);
    }
}