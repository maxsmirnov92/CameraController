package net.maxsmr.cameracontroller.camera.settings;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import net.maxsmr.commonutils.data.CompareUtils;

import java.lang.reflect.Field;

public enum  WhiteBalance {

    AUTO("WHITE_BALANCE_AUTO", 0),
    INCANDESCENT("WHITE_BALANCE_INCANDESCENT", 1),
    FLUORESCENT("WHITE_BALANCE_FLUORESCENT", 2),
    WARM_FLUORESCENT("WHITE_BALANCE_WARM_FLUORESCENT", 3),
    DAYLIGHT("WHITE_BALANCE_DAYLIGHT", 4),
    CLOUDY_DAYLIGHT("WHITE_BALANCE_CLOUDY_DAYLIGHT", 5),
    TWILIGHT("WHITE_BALANCE_TWILIGHT", 6),
    SHADE("WHITE_BALANCE_SHADE", 7);

    private static final String constantClassName = "android.hardware.Camera$Parameters";
    private final String constantValue;
    private final int id;

    WhiteBalance(String constantValue, int id) {
        this.constantValue = constantValue;
        this.id = id;
    }

    @SuppressWarnings("rawtypes")
    private static Class constClass = null;

    private static void loadClass() throws ClassNotFoundException {
        if (constClass == null)
            constClass = Class.forName(constantClassName);
    }

    public int getId() {
        return id;
    }

    public String getValue() {

        try {
            loadClass();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(String.format("Class '%s' not found", constantClassName));
        }

        Field[] fields = constClass.getDeclaredFields();

        try {

            for (Field field : fields) {

                // field.setAccessible(true);

                if (field.getName().equals(constantValue))
                    return (String) field.get(null);

            }

        } catch (IllegalAccessException e) {
            throw new RuntimeException(String.format("IllegalAccessException for %s", constantClassName + '.' + constantValue));
        }

        return null;
    }

    @NonNull
    public static WhiteBalance getMinMaxValue(boolean min) {
        WhiteBalance result = WhiteBalance.AUTO;
        for (WhiteBalance v : WhiteBalance.values()) {
            if (min && v.getId() < result.getId()) {
                result = v;
            } else if (!min && v.getId() > result.getId()) {
                result = v;
            }
        }
        return result;
    }


    @Nullable
    public static WhiteBalance fromId(int id) {
        WhiteBalance result = null;
        for (WhiteBalance whiteBalance : WhiteBalance.values()) {
            if (id == whiteBalance.getId()) {
                result = whiteBalance;
                break;
            }
        }
        return result;
    }

    @Nullable
    public static WhiteBalance fromValue(String value) {
        WhiteBalance result = null;
        for (WhiteBalance whiteBalance : WhiteBalance.values()) {
            if (CompareUtils.stringsEqual(value, whiteBalance.getValue(), false)) {
                result = whiteBalance;
                break;
            }
        }
        return result;
    }

}
