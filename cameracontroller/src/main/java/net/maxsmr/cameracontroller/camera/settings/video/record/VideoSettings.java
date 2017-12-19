package net.maxsmr.cameracontroller.camera.settings.video.record;

import android.hardware.Camera.Size;
import android.media.CamcorderProfile;

import com.google.gson.annotations.Expose;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

import net.maxsmr.cameracontroller.camera.settings.ColorEffect;
import net.maxsmr.cameracontroller.camera.settings.FlashMode;
import net.maxsmr.cameracontroller.camera.settings.FocusMode;
import net.maxsmr.cameracontroller.camera.settings.video.AudioEncoder;
import net.maxsmr.cameracontroller.camera.settings.video.VideoEncoder;
import net.maxsmr.cameracontroller.camera.settings.video.VideoQuality;

public class VideoSettings implements Serializable {

    private static final long serialVersionUID = 4832290244769646652L;

    private static final Logger logger = LoggerFactory.getLogger(VideoSettings.class);

    public VideoSettings() {
    }

    /**
     * @param quality   if value in range 0..6 and supported, then appropriate profile with all settings will be set and
     *                  other args will be ignored; if fails or not supported or -1 then single settigns will be applied
     * @param frameRate 0 - auto, measured frame rate will be applied (if it was already calculated), (0..30] - custom
     *                  frame rate
     */
    public VideoSettings(int cameraId, VideoQuality quality, VideoEncoder videoEncoder, AudioEncoder audioEncoder, boolean disableAudio,
                         Size videoSize, int frameRate, FlashMode flashMode, FocusMode focusMode, ColorEffect effect,
                         boolean enableMakePreview, int previewGridSize) {

//        setParentDirPath(parentDirPath);
//        setFileName(fileName);

        setQuality(cameraId, quality);

        setVideoEncoder(videoEncoder);
        setAudioEncoder(audioEncoder);

        disableAudio(disableAudio);

        setVideoFrameSize(videoSize);
        setVideoFrameRate(frameRate);

        setFlashMode(flashMode);
        setFocusMode(focusMode);
        setColorEffect(effect);

        enableMakePreview(enableMakePreview);
        setPreviewGridSize(previewGridSize);
    }

//    File parentDirPath;
//
//    public File getParentDirPath() {
//        return parentDirPath;
//    }
//
//    public void setParentDirPath(String path) {
//        if (!TextUtils.isEmpty(path)) {
//            parentDirPath = new File(path);
//        }
//    }
//
//    String fileName;
//
//    public String getFileName() {
//        return fileName;
//    }
//
//    public void setFileName(String name) {
//        if (!TextUtils.isEmpty(name)) {
//            fileName = name;
//        }
//    }

    @Expose
    VideoQuality quality = VideoQuality.DEFAULT;

    public VideoQuality getQuality() {
        return quality;
    }

    /**
     * @param quality if value in range 0..6 and supported, then appropriate profile with all settings will be set and
     *                other args will be ignored; if fails or not supported or -1 then single settigns will be applied
     */
    public boolean setQuality(int cameraId, VideoQuality quality) {
        if (quality != null && (CamcorderProfile.hasProfile(cameraId, quality.getValue()) || quality == VideoQuality.DEFAULT)) {
            this.quality = quality;
            return true;
        } else {
            if (quality != null) {
                logger.error("profile " + quality.getValue() + " is not supported for camera with id " + cameraId);
            }
            return false;
        }
    }

    @Expose
    VideoEncoder videoEncoder = VideoEncoder.DEFAULT;

    public VideoEncoder getVideoEncoder() {
        return videoEncoder;
    }

    public void setVideoEncoder(VideoEncoder videoEncoder) {
        if (videoEncoder != null)
            this.videoEncoder = videoEncoder;
    }

    @Expose
    AudioEncoder audioEncoder = AudioEncoder.DEFAULT;

    public AudioEncoder getAudioEncoder() {
        return audioEncoder;
    }

    public void setAudioEncoder(AudioEncoder audioEncoder) {
        if (audioEncoder != null)
            this.audioEncoder = audioEncoder;
    }

    public final static boolean DEFAULT_DISABLE_AUDIO = false;
    @Expose
    boolean disableAudio = DEFAULT_DISABLE_AUDIO;

    public boolean isAudioDisabled() {
        return disableAudio;
    }

    public void disableAudio(boolean disable) {
        this.disableAudio = disable;
    }

    public final static boolean DEFAULT_ENABLE_STORE_LOCATION = true;
    boolean enableStoreLocation = DEFAULT_ENABLE_STORE_LOCATION;

    public boolean isStoreLocationEnabled() {
        return enableStoreLocation;
    }

    public void enableStoreLocation(boolean enable) {
        this.enableStoreLocation = enable;
    }

    @Expose
    int width = -1;
    @Expose
    int height = -1;

    public int getVideoFrameWidth() {
        return width;
    }

    public int getVideoFrameHeight() {
        return height;
    }

    public boolean setVideoFrameSize(Size videoSize) {
        if (videoSize != null) {
            setVideoFrameSize(videoSize.width, videoSize.height);
            return true;
        } else {
            return false;
        }
    }

    public void setVideoFrameSize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public final static int VIDEO_FRAME_RATE_MAX = 30;
    /**
     * use calculated value in onPreviewFrame() callback if exists
     */
    public final static int VIDEO_FRAME_RATE_AUTO = 0;
    public final static int DEFAULT_VIDEO_FRAME_RATE = VIDEO_FRAME_RATE_AUTO;
    @Expose
    int frameRate = DEFAULT_VIDEO_FRAME_RATE;

    public int getVideoFrameRate() {
        return frameRate;
    }

    /**
     * @param frameRate 0 - auto, measured frame rate will be applied (if it was already calculated), (0..30] - custom
     *                  frame rate
     */
    public boolean setVideoFrameRate(int frameRate) {
        if (frameRate >= 0 && frameRate <= VIDEO_FRAME_RATE_MAX) {
            this.frameRate = frameRate;
            return true;
        } else {
            return false;
        }
    }

    @Expose
    FlashMode flashMode = FlashMode.AUTO;

    public FlashMode getFlashMode() {
        return flashMode;
    }

    public void setFlashMode(FlashMode flashMode) {
        this.flashMode = flashMode;
    }

    @Expose
    FocusMode focusMode = FocusMode.AUTO;

    public FocusMode getFocusMode() {
        return focusMode;
    }

    public void setFocusMode(FocusMode focusMode) {
        this.focusMode = focusMode;
    }

    @Expose
    ColorEffect colorEffect = ColorEffect.NONE;

    public ColorEffect getColorEffect() {
        return colorEffect;
    }

    public void setColorEffect(ColorEffect effect) {
        this.colorEffect = effect;
    }

    public final static boolean DEFAULT_ENABLE_MAKE_PREVIEW = true;
    @Expose
    boolean enableMakePreview = DEFAULT_ENABLE_MAKE_PREVIEW;

    public boolean isMakePreviewEnabled() {
        return enableMakePreview;
    }

    public void enableMakePreview(boolean enable) {
        enableMakePreview = enable;
    }

    public final static int DEFAULT_PREVIEW_GRID_SIZE = 3;
    @Expose
    int previewGridSize = DEFAULT_PREVIEW_GRID_SIZE;

    public int getPreviewGridSize() {
        return previewGridSize;
    }

    public boolean setPreviewGridSize(int previewGridSize) {
        if (previewGridSize <= 0) {
            return false;
        }
        this.previewGridSize = previewGridSize;
        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((audioEncoder == null) ? 0 : audioEncoder.hashCode());
        result = prime * result + ((colorEffect == null) ? 0 : colorEffect.hashCode());
        result = prime * result + (disableAudio ? 1231 : 1237);
        result = prime * result + (enableMakePreview ? 1231 : 1237);
        result = prime * result + (enableStoreLocation ? 1231 : 1237);
        result = prime * result + ((flashMode == null) ? 0 : flashMode.hashCode());
        result = prime * result + ((focusMode == null) ? 0 : focusMode.hashCode());
        result = prime * result + frameRate;
        result = prime * result + height;
        result = prime * result + previewGridSize;
        result = prime * result + ((quality == null) ? 0 : quality.hashCode());
        result = prime * result + ((videoEncoder == null) ? 0 : videoEncoder.hashCode());
        result = prime * result + width;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        VideoSettings other = (VideoSettings) obj;
        if (audioEncoder != other.audioEncoder)
            return false;
        if (colorEffect != other.colorEffect)
            return false;
        if (disableAudio != other.disableAudio)
            return false;
        if (enableMakePreview != other.enableMakePreview)
            return false;
        if (enableStoreLocation != other.enableStoreLocation)
            return false;
        if (flashMode != other.flashMode)
            return false;
        if (focusMode != other.focusMode)
            return false;
        if (frameRate != other.frameRate)
            return false;
        if (height != other.height)
            return false;
        if (previewGridSize != other.previewGridSize)
            return false;
        if (quality != other.quality)
            return false;
        if (videoEncoder != other.videoEncoder)
            return false;
        if (width != other.width)
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "VideoSettings [quality=" + quality + ", videoEncoder=" + videoEncoder + ", audioEncoder=" + audioEncoder
                + ", disableAudio=" + disableAudio + ", enableStoreLocation=" + enableStoreLocation + ", width=" + width + ", height="
                + height + ", frameRate=" + frameRate + ", flashMode=" + flashMode + ", focusMode=" + focusMode + ", colorEffect="
                + colorEffect + ", enableMakePreview=" + enableMakePreview + ", previewGridSize=" + previewGridSize + "]";
    }

}
