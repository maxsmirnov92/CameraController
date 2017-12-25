package net.maxsmr.cameracontroller.camera.settings;

import android.support.annotation.Nullable;

import net.maxsmr.commonutils.data.CompareUtils;

import java.lang.reflect.Field;

public enum FocusMode {

    AUTO(0, "FOCUS_MODE_AUTO"),

    CONTINUOUS_PICTURE(1, "FOCUS_MODE_CONTINUOUS_PICTURE"),

    CONTINUOUS_VIDEO(2, "FOCUS_MODE_CONTINUOUS_VIDEO"),

    EDOF(3, "FOCUS_MODE_EDOF"),

    FIXED(4, "FOCUS_MODE_FIXED"),

    INFINITY(4, "FOCUS_MODE_INFINITY"),

    MACRO(4, "FOCUS_MODE_MACRO");

    FocusMode(int id, String constValue) {
        this.id = id;
        this.constantValue = constValue;
    }

    private static final String constantClassName = "android.hardware.Camera$Parameters";
    private String constantValue;
    private final int id;

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

    @Nullable
    public static FocusMode fromValue(String value) {
        FocusMode result = null;
        for (FocusMode focusMode : FocusMode.values()) {
            if (CompareUtils.stringsEqual(value, focusMode.getValue(), false)) {
                result = focusMode;
                break;
            }
        }
        return result;
    }

}
