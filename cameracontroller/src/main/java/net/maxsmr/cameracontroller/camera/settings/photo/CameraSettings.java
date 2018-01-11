package net.maxsmr.cameracontroller.camera.settings.photo;

import android.hardware.Camera.Size;

import net.maxsmr.cameracontroller.camera.settings.ColorEffect;
import net.maxsmr.cameracontroller.camera.settings.FlashMode;
import net.maxsmr.cameracontroller.camera.settings.FocusMode;
import net.maxsmr.cameracontroller.camera.settings.video.record.VideoSettings;

import java.io.Serializable;

public class CameraSettings implements Serializable {

    private static final long serialVersionUID = 2375720325674255533L;

    public static final ImageFormat DEFAULT_IMAGE_FORMAT = ImageFormat.JPEG;

    public static final ImageFormat DEFAULT_PREVIEW_FORMAT = ImageFormat.NV21;

    public static final int DEFAULT_JPEG_QUALITY = 100;

    public static final FlashMode DEFAULT_FLASH_MODE = FlashMode.AUTO;

    public static final FocusMode DEFAULT_FOCUS_MODE = FocusMode.AUTO;

    public static final ColorEffect DEFAULT_COLOR_EFFECT = ColorEffect.NONE;

    public static final int PREVIEW_FRAME_RATE_AUTO = VideoSettings.VIDEO_FRAME_RATE_AUTO;

    public static final int DEFAULT_PREVIEW_FRAME_RATE = PREVIEW_FRAME_RATE_AUTO;

    public static final boolean DEFAULT_ENABLE_VIDEO_STABILIZATION = true;

    private ImageFormat previewFormat = DEFAULT_PREVIEW_FORMAT;

    private ImageFormat pictureFormat = DEFAULT_IMAGE_FORMAT;

    private int pictureWidth = -1;

    private int pictureHeight = -1;

    private int jpegQuality = DEFAULT_JPEG_QUALITY;

    private FlashMode flashMode = DEFAULT_FLASH_MODE;

    private FocusMode focusMode = DEFAULT_FOCUS_MODE;

    private ColorEffect colorEffect = DEFAULT_COLOR_EFFECT;

    private boolean enableVideoStabilization = DEFAULT_ENABLE_VIDEO_STABILIZATION;

    private int previewFrameRate = DEFAULT_PREVIEW_FRAME_RATE;

    /** default */
    public CameraSettings(ImageFormat previewFormat, ImageFormat pictureFormat,
                          Size pictureSize, int jpegQuality,
                          boolean enableVideoStabilization, int previewFrameRate) {
        this(previewFormat, pictureFormat, pictureSize.width, pictureSize.height, jpegQuality,
                enableVideoStabilization, previewFrameRate);
    }

    public CameraSettings(ImageFormat previewFormat, ImageFormat pictureFormat,
                          int pictureWidth, int pictureHeight, int jpegQuality,
                          boolean enableVideoStabilization, int previewFrameRate) {

        setPreviewFormat(previewFormat);
        setPictureFormat(pictureFormat);
        setPictureSize(pictureWidth, pictureHeight);
        setJpegQuality(jpegQuality);

        enableVideoStabilization(enableVideoStabilization);

        setPreviewFrameRate(previewFrameRate);
    }

    public ImageFormat getPreviewFormat() {
        return previewFormat;
    }

    public void setPreviewFormat(ImageFormat previewFormat) {
        this.previewFormat = previewFormat;
    }

    public ImageFormat getPictureFormat() {
        return pictureFormat;
    }

    public void setPictureFormat(ImageFormat pictureFormat) {
        this.pictureFormat = pictureFormat;
    }

    public int getPictureWidth() {
        return pictureWidth;
    }

    public int getPictureHeight() {
        return pictureHeight;
    }

    public boolean setPictureSize(Size pictureSize) {
        if (pictureSize != null) {
            setPictureSize(pictureSize.width, pictureSize.height);
            return true;
        }
        return false;
    }

    public void setPictureSize(int pictureWidth, int pictureHeight) {
        this.pictureWidth = pictureWidth;
        this.pictureHeight = pictureHeight;
    }


    public int getJpegQuality() {
        return jpegQuality;
    }

    public boolean setJpegQuality(int jpegQuality) {
        if (jpegQuality > 0 && jpegQuality <= 100) {
            this.jpegQuality = jpegQuality;
            return true;
        }
            return false;
    }


    public boolean isVideoStabilizationEnabled() {
        return enableVideoStabilization;
    }

    public void enableVideoStabilization(boolean enableVideoStabilization) {
        this.enableVideoStabilization = enableVideoStabilization;
    }

    public int getPreviewFrameRate() {
        return previewFrameRate;
    }

    public boolean setPreviewFrameRate(int frameRate) {
        if (frameRate > 0 || frameRate == PREVIEW_FRAME_RATE_AUTO) {
            this.previewFrameRate = frameRate;
            return true;
        }
        return false;
    }

}
