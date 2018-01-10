package net.maxsmr.cameracontroller.camera;

import android.content.Context;
import android.hardware.SensorManager;
import android.media.ExifInterface;
import android.view.OrientationEventListener;

import net.maxsmr.commonutils.graphic.GraphicUtils;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

// TODO move to common
public abstract class OrientationIntervalListener extends OrientationEventListener {

    public static final int ROTATION_NOT_SPECIFIED = -1;

    public static final int NOTIFY_INTERVAL_NOT_SPECIFIED = -1;

    public static final long DEFAULT_NOTIFY_INTERVAL = TimeUnit.SECONDS.toMillis(1);

    private final long notifyInterval;

    private long previousNotifyTime = 0;

    private int previousRotation = ROTATION_NOT_SPECIFIED;

    public OrientationIntervalListener(Context context) {
        this(context, SensorManager.SENSOR_DELAY_NORMAL, DEFAULT_NOTIFY_INTERVAL);
    }

    public OrientationIntervalListener(Context context, long interval) {
        this(context, SensorManager.SENSOR_DELAY_NORMAL, interval);
    }

    public OrientationIntervalListener(Context context, int rate, long interval) {
        super(context, rate);
        if (!(interval == NOTIFY_INTERVAL_NOT_SPECIFIED || interval > 0)) {
            throw new IllegalArgumentException("incorrect notify interval: " + interval);
        }
        notifyInterval = interval;
    }

    public int getPreviousRotation() {
        return previousRotation;
    }

    public void setPreviousRotation(int previousRotation) {
        if (previousRotation == ROTATION_NOT_SPECIFIED || previousRotation >= 0 && previousRotation < 360) {
            this.previousRotation = previousRotation;
        }
    }

    @Override
    public void onOrientationChanged(int orientation) {
        long currentTime = System.currentTimeMillis();
        if (notifyInterval == NOTIFY_INTERVAL_NOT_SPECIFIED || previousNotifyTime == 0 || currentTime - previousNotifyTime >= notifyInterval) {
            doAction(orientation);
            previousNotifyTime = currentTime;
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
