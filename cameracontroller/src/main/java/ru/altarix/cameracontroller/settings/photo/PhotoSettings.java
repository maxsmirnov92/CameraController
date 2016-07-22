package ru.altarix.cameracontroller.settings.photo;

import android.hardware.Camera.Size;

import com.google.gson.annotations.Expose;

import java.io.Serializable;

import ru.altarix.cameracontroller.settings.COLOR_EFFECT;
import ru.altarix.cameracontroller.settings.FLASH_MODE;
import ru.altarix.cameracontroller.settings.FOCUS_MODE;

public class PhotoSettings implements Serializable {

    private static final long serialVersionUID = 2375720325674255533L;

    public PhotoSettings() {
    }

    public PhotoSettings(IMAGE_FORMAT pictureFormat,
                         Size pictureSize, int jpegQuality, FLASH_MODE flashMode, FOCUS_MODE focusMode, COLOR_EFFECT effect) {

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

    public final static IMAGE_FORMAT DEFAULT_IMAGE_FORMAT = IMAGE_FORMAT.JPEG;
    @Expose
    IMAGE_FORMAT pictureFormat = DEFAULT_IMAGE_FORMAT;

    public IMAGE_FORMAT getPictureFormat() {
        return pictureFormat;
    }

    public boolean setPictureFormat(IMAGE_FORMAT pictureFormat) {
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

    public final static FLASH_MODE DEFAULT_FLASH_MODE = FLASH_MODE.AUTO;
    @Expose
    FLASH_MODE flashMode = DEFAULT_FLASH_MODE;

    public FLASH_MODE getFlashMode() {
        return flashMode;
    }

    public void setFlashMode(FLASH_MODE flashMode) {
        this.flashMode = flashMode;
    }

    public final static FOCUS_MODE DEFAULT_FOCUS_MODE = FOCUS_MODE.AUTO;
    @Expose
    FOCUS_MODE focusMode = DEFAULT_FOCUS_MODE;

    public FOCUS_MODE getFocusMode() {
        return focusMode;
    }

    public void setFocusMode(FOCUS_MODE focusMode) {
        this.focusMode = focusMode;
    }

    public final static COLOR_EFFECT DEFAULT_COLOR_EFFECT = COLOR_EFFECT.NONE;
    @Expose
    COLOR_EFFECT colorEffect = DEFAULT_COLOR_EFFECT;

    public COLOR_EFFECT getColorEffect() {
        return colorEffect;
    }

    public void setColorEffect(COLOR_EFFECT effect) {
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
