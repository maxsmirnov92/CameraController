package net.maxsmr.cameracontroller.camera.settings.photo;

import android.hardware.Camera.Size;

import com.google.gson.annotations.Expose;

import java.io.Serializable;

import net.maxsmr.cameracontroller.camera.settings.ColorEffect;
import net.maxsmr.cameracontroller.camera.settings.FlashMode;
import net.maxsmr.cameracontroller.camera.settings.FocusMode;

public class PhotoSettings implements Serializable {

    private static final long serialVersionUID = 2375720325674255533L;

    public PhotoSettings() {
    }

    public PhotoSettings(ImageFormat pictureFormat,
                         Size pictureSize, int jpegQuality, FlashMode flashMode, FocusMode focusMode, ColorEffect effect) {

//        setParentDirPath(parentDirPath);
//        setFileName(fileName);

        setPictureFormat(pictureFormat);

        setPictureSize(pictureSize);

        setJpegQuality(jpegQuality);

        setFlashMode(flashMode);
        setFocusMode(focusMode);
        setColorEffect(effect);
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

    public final static ImageFormat DEFAULT_IMAGE_FORMAT = ImageFormat.JPEG;
    @Expose
    ImageFormat pictureFormat = DEFAULT_IMAGE_FORMAT;

    public ImageFormat getPictureFormat() {
        return pictureFormat;
    }

    public boolean setPictureFormat(ImageFormat pictureFormat) {
        if (pictureFormat != null && pictureFormat.isPictureFormat()) {
            this.pictureFormat = pictureFormat;
            return true;
        } else {
            return false;
        }
    }

    @Expose
    int pictureWidth = -1;
    @Expose
    int pictureHeight = -1;

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
        } else {
            return false;
        }
    }

    public void setPictureSize(int pictureWidth, int pictureHeight) {
        this.pictureWidth = pictureWidth;
        this.pictureHeight = pictureHeight;
    }

    public final static int DEFAULT_JPEG_QUALITY = 100;
    @Expose
    int jpegQuality = DEFAULT_JPEG_QUALITY;

    public int getJpegQuality() {
        return jpegQuality;
    }

    public boolean setJpegQuality(int jpegQuality) {
        if (jpegQuality > 0 && jpegQuality <= 100) {
            this.jpegQuality = jpegQuality;
            return true;
        } else {
            return false;
        }
    }

    public final static FlashMode DEFAULT_FLASH_MODE = FlashMode.AUTO;
    @Expose
    FlashMode flashMode = DEFAULT_FLASH_MODE;

    public FlashMode getFlashMode() {
        return flashMode;
    }

    public void setFlashMode(FlashMode flashMode) {
        this.flashMode = flashMode;
    }

    public final static FocusMode DEFAULT_FOCUS_MODE = FocusMode.AUTO;
    @Expose
    FocusMode focusMode = DEFAULT_FOCUS_MODE;

    public FocusMode getFocusMode() {
        return focusMode;
    }

    public void setFocusMode(FocusMode focusMode) {
        this.focusMode = focusMode;
    }

    public final static ColorEffect DEFAULT_COLOR_EFFECT = ColorEffect.NONE;
    @Expose
    ColorEffect colorEffect = DEFAULT_COLOR_EFFECT;

    public ColorEffect getColorEffect() {
        return colorEffect;
    }

    public void setColorEffect(ColorEffect effect) {
        this.colorEffect = effect;
    }

    public final static boolean DEFAULT_ENABLE_STORE_LOCATION = true;
    @Expose
    boolean enableStoreLocation = DEFAULT_ENABLE_STORE_LOCATION;

    public boolean isStoreLocationEnabled() {
        return enableStoreLocation;
    }

    public void enableStoreLocation(boolean enable) {
        this.enableStoreLocation = enable;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((colorEffect == null) ? 0 : colorEffect.hashCode());
        result = prime * result + (enableStoreLocation ? 1231 : 1237);
        result = prime * result + ((flashMode == null) ? 0 : flashMode.hashCode());
        result = prime * result + ((focusMode == null) ? 0 : focusMode.hashCode());
        result = prime * result + jpegQuality;
        result = prime * result + ((pictureFormat == null) ? 0 : pictureFormat.hashCode());
        result = prime * result + pictureHeight;
        result = prime * result + pictureWidth;
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
        PhotoSettings other = (PhotoSettings) obj;
        if (colorEffect != other.colorEffect)
            return false;
        if (enableStoreLocation != other.enableStoreLocation)
            return false;
        if (flashMode != other.flashMode)
            return false;
        if (focusMode != other.focusMode)
            return false;
        if (jpegQuality != other.jpegQuality)
            return false;
        if (pictureFormat != other.pictureFormat)
            return false;
        if (pictureHeight != other.pictureHeight)
            return false;
        if (pictureWidth != other.pictureWidth)
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "PhotoSettings [pictureFormat=" + pictureFormat + ", pictureWidth=" + pictureWidth + ", pictureHeight=" + pictureHeight
                + ", jpegQuality=" + jpegQuality + ", flashMode=" + flashMode + ", focusMode=" + focusMode + ", colorEffect=" + colorEffect
                + ", enableStoreLocation=" + enableStoreLocation + "]";
    }

}
