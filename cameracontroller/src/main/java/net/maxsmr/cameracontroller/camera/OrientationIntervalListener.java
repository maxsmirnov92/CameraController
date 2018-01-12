package net.maxsmr.cameracontroller.camera;

import android.content.Context;
import android.hardware.SensorManager;
import android.media.ExifInterface;
import android.support.annotation.NonNull;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.WindowManager;

import net.maxsmr.commonutils.graphic.GraphicUtils;

import java.io.File;
import java.io.IOException;

// TODO move to common
public abstract class OrientationIntervalListener extends OrientationEventListener {

    public static final int ROTATION_NOT_SPECIFIED = -1;

    public static final int NOTIFY_INTERVAL_NOT_SPECIFIED = -1;

    public static final int NOTIFY_DIFF_THRESHOLD_NOT_SPECIFIED = -1;

    private final long notifyInterval;

    private final int notifyDiffThreshold;

    private long lastNotifyTime = 0;

    private int lastRotation = ROTATION_NOT_SPECIFIED;

    private int lastCorrectedRotation = ROTATION_NOT_SPECIFIED;

    public OrientationIntervalListener(Context context) {
        this(context, SensorManager.SENSOR_DELAY_NORMAL, NOTIFY_INTERVAL_NOT_SPECIFIED, NOTIFY_DIFF_THRESHOLD_NOT_SPECIFIED);
    }

    public OrientationIntervalListener(Context context, long interval, int diffThreshold) {
        this(context, SensorManager.SENSOR_DELAY_NORMAL, interval, diffThreshold);
    }

    public OrientationIntervalListener(Context context, int rate, long interval, int diffThreshold) {
        super(context, rate);
        if (!(interval == NOTIFY_INTERVAL_NOT_SPECIFIED || interval > 0)) {
            throw new IllegalArgumentException("incorrect notify interval: " + interval);
        }
        if (!(diffThreshold == NOTIFY_DIFF_THRESHOLD_NOT_SPECIFIED || diffThreshold > 0)) {
            throw new IllegalArgumentException("incorrect notify diff threshold: " + interval);
        }
        notifyInterval = interval;
        notifyDiffThreshold = diffThreshold;
    }

    public long getLastNotifyTime() {
        return lastNotifyTime;
    }

    public int getLastRotation() {
        return lastRotation;
    }

    /** user defined value */
    public int getLastCorrectedRotation() {
        return lastCorrectedRotation;
    }

    /**
     * @param lastCorrectedRotation user defined value
     * */
    public void setLastCorrectedRotation(int lastCorrectedRotation) {
        if (lastCorrectedRotation == ROTATION_NOT_SPECIFIED || lastCorrectedRotation >= 0 && lastCorrectedRotation < 360) {
            this.lastCorrectedRotation = lastCorrectedRotation;
        }
    }

    @Override
    public void onOrientationChanged(int orientation) { // display orientation
        if (orientation >= 0 && orientation < 360) {
            long currentTime = System.currentTimeMillis();
            int diff = 0;

            boolean intervalPassed = notifyInterval == NOTIFY_INTERVAL_NOT_SPECIFIED || lastNotifyTime == 0 || currentTime - lastNotifyTime >= notifyInterval;

            if (intervalPassed) {

                if (lastRotation != ROTATION_NOT_SPECIFIED) {
                    if (orientation >= 0 && orientation < 90 && lastRotation >= 270 && lastRotation < 360) {
                        int currentRotationFixed = orientation + 360;
                        diff = Math.abs(currentRotationFixed - lastRotation);
                    } else if (orientation >= 270 && orientation < 360 && lastRotation >= 0 && lastRotation < 90) {
                        int lastRotationFixed = lastRotation + 360;
                        diff = Math.abs(orientation - lastRotationFixed);
                    } else {
                        diff = Math.abs(orientation - lastRotation);
                    }
                }

                if (lastRotation == ROTATION_NOT_SPECIFIED ||
                        notifyDiffThreshold == NOTIFY_DIFF_THRESHOLD_NOT_SPECIFIED || diff >= notifyDiffThreshold) {
                    doAction(orientation);
                    lastNotifyTime = currentTime;
                    lastRotation = orientation;
                }

            }
        }
    }

    protected abstract void doAction(int orientation);

    public static int getCorrectedDisplayRotation(int rotation) {
        int result = ROTATION_NOT_SPECIFIED;
        if (rotation >= 315 && rotation < 360 || rotation >= 0 && rotation < 45) {
            result = 0;
        } else if (rotation >= 45 && rotation < 135) {
            result = 90;
        } else if (rotation >= 135 && rotation < 225) {
            result = 180;
        } else if (rotation >= 225 && rotation < 315) {
            result = 270;
        }
        return result;
    }

    // TODO move
    public static int getExifOrientationByRotationAngle(int degrees) {
        int orientation = 0;
        if (degrees >= 0 && degrees < 90) {
            orientation = ExifInterface.ORIENTATION_NORMAL;
        } else if (degrees >= 90 && degrees < 180) {
            orientation = ExifInterface.ORIENTATION_ROTATE_90;
        } else if (degrees >= 180 && degrees < 270) {
            orientation = ExifInterface.ORIENTATION_ROTATE_180;
        } else if (degrees >= 270 && degrees < 360) {
            orientation = ExifInterface.ORIENTATION_ROTATE_270;
        }
        return orientation;
    }

    // TODO move
    public static boolean writeExifOrientation(File imageFile, int degrees) {

        if (!GraphicUtils.canDecodeImage(imageFile)) {
//            logger.error("incorrect picture file: " + imageFile);
            return false;
        }

        if (!(degrees >= 0 && degrees < 360)) {
//            logger.warn("incorrect angle: " + degrees);
            return false;
        }

        try {

            ExifInterface exif = new ExifInterface(imageFile.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(getExifOrientationByRotationAngle(degrees)));
            exif.saveAttributes();
            return true;
        } catch (IOException e) {
//            logger.error("an IOException occurred", e);
            return false;
        }
    }

    // TODO move to GuiUtils
    public static int getCurrentDisplayOrientation(@NonNull Context context) {
        int degrees = 0;
        final int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
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
        return degrees;
    }
}
