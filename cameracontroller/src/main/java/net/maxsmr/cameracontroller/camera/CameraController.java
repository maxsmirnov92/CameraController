package net.maxsmr.cameracontroller.camera;

import android.content.Context;
import android.graphics.Bitmap;
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
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.media.MediaRecorder.OnErrorListener;
import android.media.MediaRecorder.OnInfoListener;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;

import net.maxsmr.cameracontroller.executors.IdsHolder;
import net.maxsmr.cameracontroller.frame.FrameCalculator;
import net.maxsmr.cameracontroller.frame.stats.IFrameStatsListener;
import net.maxsmr.cameracontroller.logger.base.Logger;
import net.maxsmr.commonutils.android.media.MetadataRetriever;
import net.maxsmr.commonutils.data.FileHelper;
import net.maxsmr.commonutils.data.Observable;
import net.maxsmr.commonutils.graphic.GraphicUtils;
import net.maxsmr.tasksutils.CustomHandlerThread;
import net.maxsmr.tasksutils.taskexecutor.TaskRunnable;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import net.maxsmr.cameracontroller.executors.MakePreviewThreadPoolExecutor;
import net.maxsmr.cameracontroller.executors.info.MakePreviewRunnableInfo;
import net.maxsmr.cameracontroller.camera.settings.ColorEffect;
import net.maxsmr.cameracontroller.camera.settings.FlashMode;
import net.maxsmr.cameracontroller.camera.settings.FocusMode;
import net.maxsmr.cameracontroller.camera.settings.photo.ImageFormat;
import net.maxsmr.cameracontroller.camera.settings.photo.PhotoSettings;
import net.maxsmr.cameracontroller.camera.settings.video.AudioEncoder;
import net.maxsmr.cameracontroller.camera.settings.video.VideoEncoder;
import net.maxsmr.cameracontroller.camera.settings.video.VideoQuality;
import net.maxsmr.cameracontroller.camera.settings.video.record.VideoRecordLimit;
import net.maxsmr.cameracontroller.camera.settings.video.record.VideoSettings;


@SuppressWarnings({"deprecation"})
public class CameraController {

    private static final SimpleDateFormat fileNameDateFormat = new SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.getDefault());

    public static final int DEFAULT_PREVIEW_CALLBACK_BUFFER_QUEUE_SIZE = 3;

    public static final int CAMERA_ID_NONE = -1;
    public static final int CAMERA_ID_BACK = Camera.CameraInfo.CAMERA_FACING_BACK;
    public static final int CAMERA_ID_FRONT = Camera.CameraInfo.CAMERA_FACING_FRONT;

    public static final int NOT_SUPPORTED_ZOOM = -1;
    public static final int MIN_ZOOM = 0;

    private static final int MAKE_PREVIEW_POOL_SIZE = 5;

    private static final int EXECUTOR_CALL_TIMEOUT = 10;

    private final Object sync = new Object();

    private final SurfaceCallbackObservable surfaceHolderCallbacks = new SurfaceCallbackObservable();

    private final CameraErrorObservable cameraErrorListeners = new CameraErrorObservable();

    private final MediaRecorderErrorObservable mediaRecorderErrorListeners = new MediaRecorderErrorObservable();

    private final PhotoReadyObservable photoReadyListeners = new PhotoReadyObservable();

    private final RecordLimitReachedObservable recordLimitReachedListeners = new RecordLimitReachedObservable();

    private final VideoPreviewObservable videoPreviewListeners = new VideoPreviewObservable();

    private final CustomPreviewCallback previewCallback = new CustomPreviewCallback();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ShutterCallback shutterCallbackStub = new ShutterCallback() {

        @Override
        public void onShutter() {
            logger.debug("onShutter()");
        }
    };

    private final Context context;

    private final Logger logger;

    private final IdsHolder previewIdsHolder = new IdsHolder(0);

    private CameraSurfaceHolderCallback cameraSurfaceHolderCallback;

    private int callbackBufferQueueSize = DEFAULT_PREVIEW_CALLBACK_BUFFER_QUEUE_SIZE;

    private SurfaceView cameraSurfaceView;

    private boolean isSurfaceCreated = false;

    private boolean enableChangeSurfaceViewSize = false;
    private boolean isFullscreenSurfaceViewSize = false;

    private int surfaceWidth = 0;
    private int surfaceHeight = 0;

    private boolean isPreviewStated = false;

    private Camera camera;

    private int cameraId = CAMERA_ID_NONE;

    private CameraState currentCameraState = CameraState.IDLE;

    private boolean isCameraLocked = true;

    private CameraThread cameraThread;

    /**
     * used to restore camera parameters after taking photo
     */
    private PhotoSettings previousPhotoSettings;

    private PhotoSettings currentPhotoSettings;

    /**
     * used to restore camera parameters after recording video
     */
    private VideoSettings previousVideoSettings;

    private VideoSettings currentVideoSettings;

    private boolean isMuteSoundEnabled = false;

    private int previousStreamVolume = -1;

    private Location lastLocation;

    private File lastPhotoFile;

    private File lastPreviewFile;

    private File lastVideoFile;

    private volatile boolean isMediaRecorderRecording = false;

    private MediaRecorder mediaRecorder;

    private int expectedCallbackBufSize = 0;

    /**
     * used for storing and running runnables to save preview for given video
     */
    private MakePreviewThreadPoolExecutor makePreviewThreadPoolExecutor;

    public CameraController(@NonNull Context context, @Nullable Logger logger, boolean enableFpsLogging) {
        this.context = context;
        this.logger = logger != null ? logger : new Logger.Stub();
        enableFpsLogging(enableFpsLogging);
        initMakePreviewThreadPoolExecutor(false, null); // TODO доработать executor
    }

    public void releaseCameraController() {

        releaseMakePreviewThreadPoolExecutor();

        if (cameraId != CAMERA_ID_NONE) {
            releaseCamera();
        }

        surfaceHolderCallbacks.unregisterAll();

        cameraErrorListeners.unregisterAll();

        mediaRecorderErrorListeners.unregisterAll();

        photoReadyListeners.unregisterAll();

        recordLimitReachedListeners.unregisterAll();

        videoPreviewListeners.unregisterAll();
    }

    public Observable<SurfaceHolder.Callback> getSurfaceHolderCallbacks() {
        return surfaceHolderCallbacks;
    }

    public Observable<OnCameraErrorListener> getCameraErrorListeners() {
        return cameraErrorListeners;
    }

    public Observable<OnMediaRecorderErrorListener> getMediaRecorderErrorListeners() {
        return mediaRecorderErrorListeners;
    }

    public Observable<OnPhotoReadyListener> getPhotoReadyListeners() {
        return photoReadyListeners;
    }

    public Observable<OnRecordLimitReachedListener> getRecordLimitReachedListeners() {
        return recordLimitReachedListeners;
    }

    public Observable<OnVideoPreviewListener> getVideoPreviewListeners() {
        return videoPreviewListeners;
    }

    public Observable<IFrameStatsListener> getFrameStatsListeners() {
        return previewCallback.getFrameStatsObservable();
    }

    public CameraState getCurrentCameraState() {
        return currentCameraState;
    }

    private void setCurrentCameraState(CameraState state) {
        if (state != null && state != currentCameraState) {
            currentCameraState = state;
            logger.info("STATE : " + currentCameraState);
        }
    }

    public boolean isCameraBusy() {
        return currentCameraState != CameraState.IDLE;
    }

    private boolean isCameraThreadRunning() {
        return cameraThread != null && cameraThread.isAlive();
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
                return new PhotoSettings(ImageFormat.fromValue(params
                        .getPictureFormat()), params.getPictureSize(), params.getJpegQuality(),
                        FlashMode.fromValue(params.getFlashMode()), FocusMode.fromValue(params.getFocusMode()),
                        ColorEffect.fromValue(params.getColorEffect()));
            } catch (IllegalArgumentException e) {
                logger.error("an IllegalArgumentException occurred during fromValue(): " + e.getMessage());
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

        return new PhotoSettings(/* ImageFormat.NV21, */ImageFormat.JPEG, /* optimalPreviewSize, */lowPictureSize, 50, FlashMode.AUTO,
                FocusMode.AUTO, ColorEffect.NONE);
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

        return new PhotoSettings(/* ImageFormat.NV21, */ImageFormat.JPEG, /* optimalPreviewSize, */mediumPictureSize, 85,
                FlashMode.AUTO, FocusMode.AUTO, ColorEffect.NONE);
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

        return new PhotoSettings(/* ImageFormat.NV21, */ImageFormat.JPEG, /* optimalPreviewSize, */highPictureSize, 100, FlashMode.AUTO,
                FocusMode.AUTO, ColorEffect.NONE);
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
        return (cameraSurfaceView != null && isSurfaceCreated && !cameraSurfaceView.getHolder().isCreating() && cameraSurfaceView.getHolder().getSurface() != null);
    }

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

    private byte[] allocatePreviewCallbackBuffer() {

        ImageFormat previewFormat = getCameraPreviewFormat();

        if (previewFormat == null) {
            logger.error("can't get previewFormat");
            return null;
        }

        Camera.Size previewSize = getCameraPreviewSize();

        if (previewSize == null) {
            logger.error("can't get previewSize");
            return null;
        }

        if (previewFormat != ImageFormat.YV12) {

            expectedCallbackBufSize = previewSize.width * previewSize.height * android.graphics.ImageFormat.getBitsPerPixel(previewFormat.getValue()) / 8;

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
        synchronized (sync) {
            if (isCameraLocked()) {
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

            surfaceHolderCallbacks.notifySurfaceCreated(holder);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            logger.debug("SurfaceHolderCallback :: surfaceChanged(), format=" + format + ", width=" + width + ", height=" + height);

            surfaceWidth = width;
            surfaceHeight = height;

            if (isCameraLocked()) {

                stopPreview();

                setCameraDisplayOrientation();

                setCameraPreviewSize(getOptimalPreviewSize(getSupportedPreviewSizes(), surfaceWidth, surfaceHeight));

                if (enableChangeSurfaceViewSize)
                    setSurfaceViewSize(isFullscreenSurfaceViewSize, 0, cameraSurfaceView);

                startPreview();
                setPreviewCallback();
            }

            surfaceHolderCallbacks.notifySurfaceChanged(holder, format, width, height);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            logger.debug("SurfaceHolderCallback :: surfaceDestroyed()");

            surfaceWidth = 0;
            surfaceHeight = 0;

            isSurfaceCreated = false;

            stopPreview();

            if (camera != null) {
                synchronized (sync) {
                    camera.setErrorCallback(null);
                    camera.setPreviewCallback(null);
                }
            }

            surfaceHolderCallbacks.notifySurfaceDestroyed(holder);
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
        ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getMetrics(displaymetrics);
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
                scale = context.getResources().getDisplayMetrics().density; // = X dpi / 160 dpi
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

    private void setCameraDisplayOrientation() {
        logger.debug("setCameraDisplayOrientation()");

        synchronized (sync) {

            final int resultDegrees = calculateCameraDisplayOrientation(cameraId, context);
            logger.debug("camera rotation degrees: " + resultDegrees);

            if (isCameraLocked()) {
                camera.setDisplayOrientation(resultDegrees);

                try {
                    final Parameters params = camera.getParameters();
                    params.setRotation(resultDegrees);
                    camera.setParameters(params);
                } catch (RuntimeException e) {
                    logger.error("a RuntimeException occurred during get/set camera parameters", e);
                }
            }

            if (mediaRecorder != null && !isMediaRecorderRecording) {
                mediaRecorder.setOrientationHint(resultDegrees);
            }

        }
    }

    public Location getLastLocation() {
        return lastLocation;
    }

    public void updateLastLocation(Location loc) {
        this.lastLocation = loc;
    }

    private boolean createCamera(int cameraId, SurfaceView surfaceView, boolean setMaxPreviewFps, boolean setRgbPreviewFormat,
                                 boolean enableVideoStabilization) {

        synchronized (sync) {

            if (isCameraOpened()) {
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

                    cameraErrorListeners.notifyCameraError(error);
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
                setCameraPreviewFormat(ImageFormat.RGB_565);

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

                logger.debug("locking camera...");
                try {
                    camera.lock();
                    isCameraLocked = true;
                    return true;
                } catch (Exception e) {
                    logger.error("an Exception occurred during lock()", e);
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
                logger.debug("unlocking camera...");
                try {
                    camera.unlock();
                    isCameraLocked = false;
                    return true;
                } catch (Exception e) {
                    logger.error("an Exception occurred during unlock()", e);
                }
            }
            return false;
        }
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
                    logger.debug("starting preview...");
                    try {
                        camera.startPreview();
                        isPreviewStated = true;
                        previewCallback.notifyPreviewStarted();
                        result = true;
                    } catch (RuntimeException e) {
                        logger.error("a RuntimeException occurred during startPreview()", e);
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
                    logger.debug("stopping preview...");
                    try {
                        camera.stopPreview();
                        isPreviewStated = false;
                        // previewCallback.resetFpsCounter();
                        result = true;
                    } catch (RuntimeException e) {
                        logger.error("a RuntimeException occurred during stopPreview()", e);
                    }
                }
            }
            return result;
        }
    }

    /**
     * for the first time must be called at onCreate() or onResume() handling surfaceCreated(), surfaceChanged() and
     * surfaceDestroyed() callbacks
     */
    public boolean openCamera(int cameraId, SurfaceView surfaceView, boolean setMaxFps, boolean setRgbPreviewFormat, boolean enableVideoStabilization) {
        logger.debug("openCamera(), cameraId=" + cameraId + ", setMaxFps=" + setMaxFps + ", setRgbPreviewFormat=" + setRgbPreviewFormat
                + ", enableVideoStabilization=" + enableVideoStabilization);

        if (isCameraThreadRunning()) {
            logger.error("cameraThread is already running");
            return false;
        }

        // can use new HandlerThread or Looper in new Thread
        // or execute open() on the main thread (but it could reduce performance, including
        // onPreviewFrame() calls)

        final CountDownLatch latch = new CountDownLatch(1);

        cameraThread = new CameraThread(cameraId, surfaceView, setMaxFps, setRgbPreviewFormat, enableVideoStabilization, latch);
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
        // return createCamera(cameraId, surfaceView);
    }

    public boolean releaseCamera() {
        logger.debug("releaseCamera()");

        synchronized (sync) {

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

            stopPreview();

            camera.setErrorCallback(null);
            camera.setPreviewCallback(null);
            // camera.setPreviewDisplay(null);

            logger.debug("releasing camera " + cameraId + "...");
            camera.release();

            camera = null;
            cameraId = CAMERA_ID_NONE;

            // cameraSurfaceView = null;

            if (isCameraThreadRunning()) {
                cameraThread.quit();
                cameraThread = null;
            }

            previewCallback.updatePreviewFormat(null);
            previewCallback.updatePreviewSize(null);

            return true;
        }
    }

    private void muteSound(boolean mute) {
        if (isMuteSoundEnabled) {
            AudioManager mgr = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            // mgr.setStreamMute(AudioManager.STREAM_SYSTEM, mute);
            if (mute) {
                previousStreamVolume = mgr.getStreamVolume(AudioManager.STREAM_SYSTEM);
                mgr.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
            } else if (previousStreamVolume >= 0) {
                mgr.setStreamVolume(AudioManager.STREAM_SYSTEM, previousStreamVolume, 0);
            }

            if (android.os.Build.VERSION.SDK_INT >= 17) {
                try {
                    if (isCameraLocked()) {
                        synchronized (sync) {
                            camera.enableShutterSound(!mute);
                        }
                    }
                } catch (RuntimeException e) {
                    logger.error("a RuntimeException occurred during enableShutterSound()", e);
                }
            }
        }
    }

    public void enableMuteSound(boolean enable) {
        isMuteSoundEnabled = enable;
    }

    public int getMaxZoom() {

        synchronized (sync) {

            if (currentCameraState != CameraState.IDLE) {
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

        synchronized (sync) {

            if (currentCameraState != CameraState.IDLE) {
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

    public boolean setCurrentZoom(int zoom) {

        synchronized (sync) {

            if (currentCameraState != CameraState.IDLE) {
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
        synchronized (sync) {
            try {
                if (isCameraLocked()) {
                    return camera.getParameters().getSupportedPreviewSizes();
                }
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
            }
            return null;
        }
    }

    public List<int[]> getSupportedPreviewFpsRanges() {
        synchronized (sync) {
            try {
                if (isCameraLocked()) {
                    return camera.getParameters().getSupportedPreviewFpsRange();
                }
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
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
                logger.error("a RuntimeException occurred during getParameters()", e);
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
                logger.error("a RuntimeException occurred during getParameters()", e);
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
                logger.error("a RuntimeException occurred during getParameters()", e);
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
                logger.error("a RuntimeException occurred during getParameters()", e);
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
                logger.error("a RuntimeException occurred during getParameters()", e);
            }
            return null;
        }
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

        synchronized (sync) {

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

    private boolean setCameraPreviewSize(int width, int height) {
        logger.debug("setCameraPreviewSize(), width=" + width + ", height=" + height);

        synchronized (sync) {

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

    public ImageFormat getCameraPreviewFormat() {

        synchronized (sync) {

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

            return ImageFormat.fromValue(params.getPreviewFormat());
        }
    }

    private boolean setCameraPreviewFormat(ImageFormat previewFormat) {
        logger.debug("setCameraPreviewFormat(), previewFormat=" + previewFormat);

        synchronized (sync) {

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

        synchronized (sync) {

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

        synchronized (sync) {

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

        synchronized (sync) {

            if (camera == null) {
                logger.error("camera is null");
                return false;
            }

            if (!isCameraLocked()) {
                logger.error("can't get/set parameters: camera is not locked");
                return false;
            }

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

    public FlashMode getCameraFlashMode() {

        synchronized (sync) {

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

            return FlashMode.fromValue(params.getFlashMode());
        }
    }

    /**
     * used to change permanent or temp flash mode for opened camera; working both for photo and video
     */
    public boolean setCameraFlashMode(FlashMode flashMode) {
        logger.debug("setCameraFlashMode(), flashMode=" + flashMode);

        synchronized (sync) {

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

    public FocusMode getCameraFocusMode() {

        synchronized (sync) {
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

            return FocusMode.fromValue(params.getFocusMode());
        }
    }

    /**
     * used to change permanent or temp focus mode for opened camera; working both for photo and video
     */
    public boolean setCameraFocus(FocusMode focusMode) {
        logger.debug("setCameraFocus(), focusMode=" + focusMode);

        synchronized (sync) {

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
                isMeteringAreasSupported = params.getMaxNumMeteringAreas() > 0;
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

    public ColorEffect getCameraColorEffect() {

        synchronized (sync) {

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

            return ColorEffect.fromValue(params.getColorEffect());
        }
    }

    /**
     * used to change permanent or temp color effect for opened camera; working both for photo and video
     */
    public boolean setCameraColorEffect(ColorEffect effect) {
        logger.debug("setCameraColorEffect(), effect=" + effect);

        synchronized (sync) {

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

        synchronized (sync) {

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
                photoSettings.setPictureFormat(ImageFormat.fromValue(params.getPictureFormat()));
            }

            if (!isPictureFormatSupported) {
                logger.error(" _ picture format " + photoSettings.getPictureFormat() + " is NOT supported");
                photoSettings.setPictureFormat(ImageFormat.fromValue(params.getPictureFormat()));
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
                photoSettings.setFlashMode(FlashMode.fromValue(params.getFlashMode()));
            }

            if (!setCameraFocus(photoSettings.getFocusMode())) {
                photoSettings.setFocusMode(FocusMode.fromValue(params.getFocusMode()));
            }

            if (!setCameraColorEffect(photoSettings.getColorEffect())) {
                photoSettings.setColorEffect(ColorEffect.fromValue(params.getColorEffect()));
            }

            return true;
        }
    }

    public boolean takePhoto(final PhotoSettings photoSettings, String photoDirectoryPath, String photoFileName, final boolean writeToFile) {
        logger.debug("takePhoto(), photoSettings=" + photoSettings + ", photoDirectoryPath=" + photoDirectoryPath + ", photoFileName=" + photoFileName + ", writeToFile=" + writeToFile);

        synchronized (sync) {

            if (currentCameraState != CameraState.IDLE) {
                logger.error("current camera state is not IDLE! state is " + currentCameraState);
                return currentCameraState == CameraState.TAKING_PHOTO;
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

            if (writeToFile) {
                if (TextUtils.isEmpty(photoFileName)) {
                    photoFileName = makeNewFileName(CameraState.TAKING_PHOTO, new Date(System.currentTimeMillis()), new Pair<>(photoSettings.getPictureWidth(), photoSettings.getPictureHeight()), "jpg");
                } else {
                    photoFileName = FileHelper.removeExtension(photoFileName) + ".jpg";
                }

                if ((this.lastPhotoFile = FileHelper.checkPathNoThrow(photoDirectoryPath, photoFileName)) == null) {
                    logger.error("incorrect photo path: " + photoDirectoryPath + File.separator + photoFileName);
                    return false;
                }
            } else {
                this.lastPhotoFile = null;
            }

            if (photoSettings.getFocusMode() == FocusMode.AUTO || photoSettings.getFocusMode() == FocusMode.MACRO) {

                camera.cancelAutoFocus();

                logger.info("taking photo with auto focus...");

                camera.autoFocus(new AutoFocusCallback() {

                    @Override
                    public void onAutoFocus(boolean success, Camera camera) {
                        logger.debug("onAutoFocus(), success=" + true);
                        takePhotoInternal(writeToFile);
                    }
                });

            } else {
                logger.info("taking photo without auto focus...");
                takePhotoInternal(writeToFile);
            }

            return true;
        }
    }

    private void takePhotoInternal(boolean writeToFile) {
        muteSound(true);
        setCurrentCameraState(CameraState.TAKING_PHOTO);
        isPreviewStated = false;
        camera.takePicture(isMuteSoundEnabled ? null : shutterCallbackStub, null, new CustomPictureCallback(writeToFile));
    }

    public File getLastPhotoFile() {
        return lastPhotoFile;
    }

    private class CustomPictureCallback implements Camera.PictureCallback {

        private final boolean writeToFile;

        CustomPictureCallback(boolean writeToFile) {
            this.writeToFile = writeToFile;
        }

        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            logger.debug("onPictureTaken()");

            if (currentCameraState != CameraState.TAKING_PHOTO) {
                throw new IllegalStateException("current camera state is not " + CameraState.TAKING_PHOTO);
            }

            if (currentPhotoSettings != null) {

                logger.debug("last photo file: " + lastPhotoFile);
                if (lastPhotoFile != null) {
                    if (FileHelper.writeBytesToFile(lastPhotoFile, data, false)) {
                        if (currentPhotoSettings.isStoreLocationEnabled())
                            if (!FileHelper.writeExifLocation(lastPhotoFile, lastLocation)) {
                                logger.error("can't write location to exif");
                            }
                    } else {
                        logger.error("can't write picture data to file");
                    }
                }
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

            setCurrentCameraState(CameraState.IDLE);

            muteSound(false);

            if (writeToFile) {
                photoReadyListeners.notifyPhotoFileReady(lastPhotoFile);
            } else {
                photoReadyListeners.notifyPhotoDataReady(data);
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
                logger.error("a RuntimeException occurred during getParameters()", e);
            }
            return null;
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
                return new VideoSettings(cameraId, VideoQuality.DEFAULT, null, null, false, null, VideoSettings.DEFAULT_VIDEO_FRAME_RATE,
                        FlashMode.fromValue(params.getFlashMode()), FocusMode.fromValue(params.getFocusMode()),
                        ColorEffect.fromValue(params.getColorEffect()), false, 0);
            } catch (IllegalArgumentException e) {
                logger.error("an IllegalArgumentException occurred during fromValue(): " + e.getMessage());
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

        return new VideoSettings(cameraId, VideoQuality.LOW, VideoEncoder.H264, AudioEncoder.AAC, false, lowVideoSize,
                VideoSettings.VIDEO_FRAME_RATE_MAX, FlashMode.AUTO, FocusMode.AUTO, ColorEffect.NONE,
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

        return new VideoSettings(cameraId, VideoQuality.DEFAULT, VideoEncoder.H264, AudioEncoder.AAC, false, mediumVideoSize,
                VideoSettings.VIDEO_FRAME_RATE_MAX, FlashMode.AUTO, FocusMode.AUTO, ColorEffect.NONE,
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

        return new VideoSettings(cameraId, VideoQuality.HIGH, VideoEncoder.H264, AudioEncoder.AAC, false, highVideoSize,
                VideoSettings.VIDEO_FRAME_RATE_MAX, FlashMode.AUTO, FocusMode.AUTO, ColorEffect.NONE,
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
            }

        }

        return (isVideoSizeSupported /* && isPreviewSizeSupported */);
    }

    /**
     * @param profile         if is not null, it will be setted to recorder (re-filled if necessary); otherwise single
     *                        parameters will be setted in appropriate order without profile
     * @param videoSettings   must not be null
     * @param previewFpsRange if is not null and requested fps falls within the range, it will be applied
     */
    private boolean setMediaRecorderParams(CamcorderProfile profile, VideoSettings videoSettings, double[] previewFpsRange) {
        logger.debug("setMediaRecorderParams(), profile=" + profile + ", videoSettings=" + videoSettings + ", previewFpsRange="
                + Arrays.toString(previewFpsRange));

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

        if (previewFpsRange != null && previewFpsRange.length > 2) {
            previewFpsRange = null;
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

            if (previewFpsRange != null && previewCallback.getLastFps() >= previewFpsRange[0] && previewCallback.getLastFps() <= previewFpsRange[1]) {
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

            logger.debug(camcorderProfileToString(profile));

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

    public File getLastVideoFile() {
        return lastVideoFile;
    }

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

    private boolean setRecordingHint(boolean hint) {

        synchronized (sync) {

            if (camera == null) {
                logger.error("camera is null");
                return false;
            }

            if (!isCameraLocked()) {
                logger.error("can't get/set parameters: camera is not locked");
                return false;
            }

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

        synchronized (sync) {

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

            final Parameters params;
            try {
                params = camera.getParameters();
            } catch (RuntimeException e) {
                logger.error("a RuntimeException occurred during getParameters()", e);
                return false;
            }

            if (!setCameraFlashMode(videoSettings.getFlashMode())) {
                videoSettings.setFlashMode(FlashMode.fromValue(params.getFlashMode()));
            }

            if (!setCameraFocus(videoSettings.getFocusMode())) {
                videoSettings.setFocusMode(FocusMode.fromValue(params.getFocusMode()));
            }

            if (!setCameraColorEffect(videoSettings.getColorEffect())) {
                videoSettings.setColorEffect(ColorEffect.fromValue(params.getColorEffect()));
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

    private boolean prepareMediaRecorder(VideoSettings videoSettings, VideoRecordLimit recLimit, String saveDirectoryPath, String fileName) {

        synchronized (sync) {

            final long startPrepareTime = System.currentTimeMillis();

            if (!isCameraOpened()) {
                logger.error("camera is not opened");
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

            if (videoSettings.getQuality() == VideoQuality.DEFAULT) {

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
                    videoSettings.setQuality(cameraId, VideoQuality.DEFAULT);
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
                    stopRecordVideo();
                    mediaRecorderErrorListeners.notifyMediaRecorderError(what, extra);
                }
            });

            mediaRecorder.setOnInfoListener(new OnInfoListener() {

                @Override
                public void onInfo(MediaRecorder mediaRecorder, int what, int extra) {

                    switch (what) {
                        case MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED:
                        case MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED:
                            logger.debug("onInfo(), what=" + what + ", extra=" + extra);
                            recordLimitReachedListeners.notifyRecordLimitReached(stopRecordVideo());
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
                fileName = makeNewFileName(CameraState.RECORDING_VIDEO, new Date(System.currentTimeMillis()), resolution, getFileExtensionByVideoEncoder(videoSettings.getVideoEncoder()));
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
    }

    private boolean releaseMediaRecorder() {
        logger.debug("releaseMediaRecorder()");

        synchronized (sync) {

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

                try {
                    camera.reconnect();
                } catch (IOException e) {
                    logger.error("an IOException occures during reconnect()", e);
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
    }

    public boolean startRecordVideo(VideoSettings videoSettings, VideoRecordLimit recLimit, String saveDirectoryPath, String fileName) {
        logger.debug("startRecordVideo(), videoSettings=" + videoSettings + "deleteTempVideoFiles="
                + ", recLimit=" + recLimit + ", saveDirectoryPath=" + saveDirectoryPath + ", fileName=" + fileName);

        synchronized (sync) {

            if (currentCameraState != CameraState.IDLE) {
                logger.error("current camera state is not IDLE! state is " + currentCameraState);
                return currentCameraState == CameraState.RECORDING_VIDEO;
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

                setCurrentCameraState(CameraState.RECORDING_VIDEO);

                isMediaRecorderRecording = true;

                return true;
            }

            releaseMediaRecorder();

            return false;
        }
    }

    public File stopRecordVideo() {
        logger.debug("stopRecordVideo()");

        synchronized (sync) {

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

                setCurrentCameraState(CameraState.IDLE);

            } else {
                logger.error("mediaRecorder is not recording");
                return null;
            }

            releaseMediaRecorder();

            if (makePreviewThreadPoolExecutor != null) {
                try {
                    makePreviewThreadPoolExecutor.execute(new MakePreviewRunnable(new MakePreviewRunnableInfo(previewIdsHolder.incrementAndGet(), lastVideoFile.getName(), currentVideoSettings, lastVideoFile)));
                } catch (NullPointerException e) {
                    logger.error("a NullPointerException occurred during getName(): " + e.getMessage());
                }
            } else {
                logger.error("makePreviewThreadPoolExecutor is null");
            }

            currentVideoSettings = null;

            return lastVideoFile;
        }
    }

    public void initMakePreviewThreadPoolExecutor(boolean syncWorkQueue, String workQueuesPath) {
        logger.debug("initMakePreviewThreadPoolExecutor(), syncWorkQueue=" + syncWorkQueue + ", workQueuesPath=" + workQueuesPath);

        releaseMakePreviewThreadPoolExecutor();

        makePreviewThreadPoolExecutor = new MakePreviewThreadPoolExecutor(this, MAKE_PREVIEW_POOL_SIZE, syncWorkQueue,
                workQueuesPath != null ? workQueuesPath + File.separator + "make_preview" : null, logger);
    }

    public void releaseMakePreviewThreadPoolExecutor() {

        if (makePreviewThreadPoolExecutor == null) {
            return;
        }

        logger.debug("releaseMakePreviewThreadPoolExecutor()");

        makePreviewThreadPoolExecutor.shutdown();
        makePreviewThreadPoolExecutor = null;
    }

    public void enableFpsLogging(boolean enable) {
        previewCallback.setAllowLogging(enable);
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

        CameraInfo cameraInfo = new CameraInfo();
        Camera.getCameraInfo(cameraId, cameraInfo);

        int result = 0;

        if (cameraInfo.facing == CameraInfo.CAMERA_FACING_BACK) {
            result = ((360 - degrees) + cameraInfo.orientation);
        } else if (cameraInfo.facing == CameraInfo.CAMERA_FACING_FRONT) {
            result = ((360 - degrees) - cameraInfo.orientation);
            result += 360;
        }
        return result % 360;
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
        int format = -1;
        if (videoEncoder != null) {
            switch (videoEncoder) {
                case DEFAULT:
                    format = MediaRecorder.OutputFormat.DEFAULT;
                    break;

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

    public class MakePreviewRunnable extends TaskRunnable<MakePreviewRunnableInfo> {

        private final MakePreviewRunnableInfo rInfo;

        public MakePreviewRunnable(MakePreviewRunnableInfo rInfo) {
            super(rInfo);
            this.rInfo = rInfo;
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

                videoPreviewListeners.notifyPreviewFailed(rInfo.videoFile);

                return;
            }

            MediaMetadataRetriever retriever = MetadataRetriever.createMediaMetadataRetriever(context, Uri.fromFile(rInfo.videoFile), null);
            final Bitmap firstFrame = MetadataRetriever.extractFrameAtPosition(retriever, 1, false);
            final Bitmap lastFrame = MetadataRetriever.extractFrameAtPosition(retriever,
                    (long) (MetadataRetriever.extractMediaDuration(retriever, false) * 0.95), true);

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

            videoPreviewListeners.notifyPreviewReady(lastPreviewFile, firstFrame, lastFrame, rInfo.videoFile);
        }
    }

    private class CameraThread extends CustomHandlerThread {

        private final int cameraId;
        private final SurfaceView surfaceView;
        private final boolean setMaxFps;
        private final boolean setRgbPreviewFormat;
        private final boolean enableVideoStabilization;
        @Nullable
        private final CountDownLatch latch;

        private boolean openResult = false;

        CameraThread(final int cameraId, final SurfaceView surfaceView, final boolean setMaxFps, final boolean setRgbPreviewFormat,
                             final boolean enableVideoStabilization, @Nullable final CountDownLatch latch) {
            super(CameraThread.class.getSimpleName());
            this.cameraId = cameraId;
            this.surfaceView= surfaceView;
            this.setMaxFps = setMaxFps;
            this.setRgbPreviewFormat = setRgbPreviewFormat;
            this.enableVideoStabilization = enableVideoStabilization;
            this.latch = latch;
        }

        public boolean getOpenResult() {
            return openResult;
        }

        @Override
        protected void onLooperPrepared() {
            super.onLooperPrepared();
            addNewTask(new Runnable() {
                @Override
                public void run() {
                    CameraThread.this.openResult = createCamera(cameraId, surfaceView, setMaxFps, setRgbPreviewFormat, enableVideoStabilization);
                    if (latch != null) {
                        latch.countDown();
                    }
                }
            }, 0);
        }
    }

    private class CustomPreviewCallback extends FrameCalculator implements Camera.PreviewCallback {

        private boolean allowLogging;

        private long recorderInterval = 0;

        private ImageFormat previewFormat;

        private int previewWidth = -1;
        private int previewHeight = -1;

        CustomPreviewCallback() {
            super(logger);
        }

        public void setAllowLogging(boolean allow) {
            allowLogging = allow;
        }

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

            if (allowLogging) {
                onPreviewFrame();
            }

            if (isCameraLocked() && callbackBufferQueueSize > 0) {
                camera.addCallbackBuffer(data);
            }
        }
    }

    protected static class SurfaceCallbackObservable extends Observable<SurfaceHolder.Callback> {

        void notifySurfaceCreated(SurfaceHolder surfaceHolder) {
            synchronized (mObservers) {
                for (SurfaceHolder.Callback l : mObservers) {
                    l.surfaceCreated(surfaceHolder);
                }
            }
        }

        void notifySurfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
            synchronized (mObservers) {
                for (SurfaceHolder.Callback l : mObservers) {
                    l.surfaceChanged(surfaceHolder, format, width, height);
                }
            }
        }

        void notifySurfaceDestroyed(SurfaceHolder surfaceHolder) {
            synchronized (mObservers) {
                for (SurfaceHolder.Callback l : mObservers) {
                    l.surfaceDestroyed(surfaceHolder);
                }
            }
        }
    }

    protected static class CameraErrorObservable extends Observable<OnCameraErrorListener> {

        void notifyCameraError(int error) {
            synchronized (mObservers) {
                for (OnCameraErrorListener l : mObservers) {
                    l.onCameraError(error);
                }
            }
        }
    }

    protected static class MediaRecorderErrorObservable extends Observable<OnMediaRecorderErrorListener> {

        void notifyMediaRecorderError(int error, int extra) {
            synchronized (mObservers) {
                for (OnMediaRecorderErrorListener l : mObservers) {
                    l.onMediaRecorderError(error, extra);
                }
            }
        }
    }

    protected static class PhotoReadyObservable extends Observable<OnPhotoReadyListener> {

        void notifyPhotoFileReady(File photoFile) {
            synchronized (mObservers) {
                for (OnPhotoReadyListener l : mObservers) {
                    l.onPhotoFileReady(photoFile);
                }
            }
        }

        void notifyPhotoDataReady(byte[] photoData) {
            synchronized (mObservers) {
                for (OnPhotoReadyListener l : mObservers) {
                    l.onPhotoDataReady(photoData);
                }
            }
        }
    }

    protected static class RecordLimitReachedObservable extends Observable<OnRecordLimitReachedListener> {

        void notifyRecordLimitReached(File videoFile) {
            synchronized (mObservers) {
                for (OnRecordLimitReachedListener l : mObservers) {
                    l.onRecordLimitReached(videoFile);
                }
            }
        }

    }

    protected static class VideoPreviewObservable extends Observable<OnVideoPreviewListener> {

        void notifyPreviewFailed(File videoFile) {
            synchronized (mObservers) {
                for (OnVideoPreviewListener l : mObservers) {
                    l.onVideoPreviewFailed(videoFile);
                }
            }
        }

        void notifyPreviewReady(File previewFile, Bitmap firstFrame, Bitmap lastFrame, File videoFile) {
            synchronized (mObservers) {
                for (OnVideoPreviewListener l : mObservers) {
                    l.onVideoPreviewReady(previewFile, firstFrame, lastFrame, videoFile);
                }
            }
        }
    }

    public interface OnCameraErrorListener {

        void onCameraError(int error);
    }

    public interface OnMediaRecorderErrorListener {

        void onMediaRecorderError(int error, int extra);
    }

    public interface OnPhotoReadyListener {

        void onPhotoFileReady(File photoFile);

        void onPhotoDataReady(byte[] photoData);
    }

    public interface OnRecordLimitReachedListener {

        void onRecordLimitReached(File videoFile);
    }

    /**
     * preview will be created asynchronously (if it's allowed), result will be delivered to onVideoPreviewReady()
     * callback
     */
    public interface OnVideoPreviewListener {

        void onVideoPreviewFailed(File videoFile);

        /**
         * preview for its video file is ready; invokes from the other thread
         */
        void onVideoPreviewReady(File previewFile, Bitmap firstFrame, Bitmap lastFrame, File videoFile);
    }
}