package net.maxsmr.cameracontroller.camera;

import android.content.Context;
import android.hardware.SensorManager;
import android.media.ExifInterface;
import android.view.OrientationEventListener;

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

    private long previousNotifyTime = 0;

    private int previousRotation = ROTATION_NOT_SPECIFIED;

    private int previousFixedRotation = ROTATION_NOT_SPECIFIED;

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

    public int getPreviousRotation() {
        return previousRotation;
    }

    public int getPreviousFixedRotation() {
        return previousFixedRotation;
    }

    public void setPreviousFixedRotation(int previousFixedRotation) {
        if (previousFixedRotation == ROTATION_NOT_SPECIFIED || previousFixedRotation >= 0 && previousFixedRotation < 360) {
            this.previousFixedRotation = previousFixedRotation;
        }
    }

    @Override
    public void onOrientationChanged(int orientation) {
        if (orientation >= 0 && orientation < 360) {
            long currentTime = System.currentTimeMillis();
            if (notifyInterval == NOTIFY_INTERVAL_NOT_SPECIFIED || previousNotifyTime == 0 || currentTime - previousNotifyTime >= notifyInterval) {
                if (notifyDiffThreshold == NOTIFY_DIFF_THRESHOLD_NOT_SPECIFIED || previousRotation == ROTATION_NOT_SPECIFIED || Math.abs(orientation - previousRotation) >= notifyDiffThreshold) {
                    doAction(orientation);
                    previousNotifyTime = currentTime;
                    previousRotation = orientation;
                }
            }
        }
    }

    protected abstract void doAction(int orientation);

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
}
