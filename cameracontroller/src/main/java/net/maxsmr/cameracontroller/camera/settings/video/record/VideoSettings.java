package net.maxsmr.cameracontroller.camera.settings.video.record;

import android.hardware.Camera.Size;
import android.media.CamcorderProfile;

import com.google.gson.annotations.Expose;


import java.io.Serializable;

import net.maxsmr.cameracontroller.camera.settings.ColorEffect;
import net.maxsmr.cameracontroller.camera.settings.FlashMode;
import net.maxsmr.cameracontroller.camera.settings.FocusMode;
import net.maxsmr.cameracontroller.camera.settings.video.AudioEncoder;
import net.maxsmr.cameracontroller.camera.settings.video.VideoEncoder;
import net.maxsmr.cameracontroller.camera.settings.video.VideoQuality;
import net.maxsmr.commonutils.logger.BaseLogger;
import net.maxsmr.commonutils.logger.holder.BaseLoggerHolder;

public class VideoSettings implements Serializable {

    private static final long serialVersionUID = 4832290244769646652L;

    private static final BaseLogger logger = BaseLoggerHolder.getInstance().getLogger(VideoSettings.class);

    public static final int VIDEO_FRAME_RATE_MAX = 60;
    /**
     * use calculated value in onPreviewFrame() callback if exists
     */
    public static final int VIDEO_FRAME_RATE_AUTO = 0;

    public static final int DEFAULT_VIDEO_FRAME_RATE = VIDEO_FRAME_RATE_AUTO;

    public static final int DEFAULT_PREVIEW_GRID_SIZE = 3;

    public static final boolean DEFAULT_ENABLE_MAKE_PREVIEW = true;

    public static final boolean DEFAULT_DISABLE_AUDIO = false;

    private VideoQuality quality = VideoQuality.DEFAULT;

    private VideoEncoder videoEncoder = VideoEncoder.DEFAULT;

    private AudioEncoder audioEncoder = AudioEncoder.DEFAULT;

    private boolean disableAudio = DEFAULT_DISABLE_AUDIO;

    private int width = -1;

    private int height = -1;

    private int frameRate = DEFAULT_VIDEO_FRAME_RATE;

    private boolean enableMakePreview = DEFAULT_ENABLE_MAKE_PREVIEW;

    private int previewGridSize = DEFAULT_PREVIEW_GRID_SIZE;

    /** default */
    public VideoSettings() {
    }

    public VideoSettings(int cameraId, VideoQuality quality, VideoEncoder videoEncoder, AudioEncoder audioEncoder, boolean disableAudio,
                         Size videoSize, int frameRate,
                         boolean enableMakePreview, int previewGridSize) {
        this(cameraId, quality, videoEncoder, audioEncoder, disableAudio, videoSize.width, videoSize.height, frameRate, enableMakePreview, previewGridSize);
    }

    /**
     * @param quality   if value in range 0..6 and supported, then appropriate profile with all settings will be set and
     *                  other args will be ignored; if fails or not supported or -1 then single settigns will be applied
     * @param frameRate 0 - auto, measured frame rate will be applied (if it was already calculated), (0..30] - custom
     *                  frame rate
     */
    public VideoSettings(int cameraId, VideoQuality quality, VideoEncoder videoEncoder, AudioEncoder audioEncoder, boolean disableAudio,
                         int videoWidth, int videoHeight, int frameRate,
                         boolean enableMakePreview, int previewGridSize) {

        setQuality(cameraId, quality);

        setVideoEncoder(videoEncoder);
        setAudioEncoder(audioEncoder);

        disableAudio(disableAudio);

        setVideoFrameSize(videoWidth, videoHeight);
        setVideoFrameRate(frameRate);

        enableMakePreview(enableMakePreview);
        setPreviewGridSize(previewGridSize);
    }

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
                logger.e("profile " + quality.getValue() + " is not supported for camera with id " + cameraId);
            }
            return false;
        }
    }

    public VideoEncoder getVideoEncoder() {
        return videoEncoder;
    }

    public void setVideoEncoder(VideoEncoder videoEncoder) {
        if (videoEncoder != null)
            this.videoEncoder = videoEncoder;
    }

    public AudioEncoder getAudioEncoder() {
        return audioEncoder;
    }

    public void setAudioEncoder(AudioEncoder audioEncoder) {
        if (audioEncoder != null)
            this.audioEncoder = audioEncoder;
    }


    public boolean isAudioDisabled() {
        return disableAudio;
    }

    public void disableAudio(boolean disable) {
        this.disableAudio = disable;
    }

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

    public int getVideoFrameRate() {
        return frameRate;
    }

    /**
     * @param frameRate {@linkplain VideoSettings#VIDEO_FRAME_RATE_AUTO} - auto, measured frame rate will be applied (if it was already calculated),
     *                  (0..{@link VideoSettings#VIDEO_FRAME_RATE_MAX}] - custom frame rate
     */
    public boolean setVideoFrameRate(int frameRate) {
        if (frameRate > 0 && frameRate <= VIDEO_FRAME_RATE_MAX || frameRate == VIDEO_FRAME_RATE_AUTO) {
            this.frameRate = frameRate;
            return true;
        }
        return false;
    }

    public boolean isMakePreviewEnabled() {
        return enableMakePreview;
    }

    public void enableMakePreview(boolean enable) {
        enableMakePreview = enable;
    }

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
}
