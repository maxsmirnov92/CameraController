package net.maxsmr.cameracontroller.camera.settings.photo;


import java.lang.reflect.Field;

public enum ImageFormat {

    JPEG(0, "JPEG"),

    NV21(1, "NV21"),

    NV16(2, "NV16"),

    RGB_565(3, "RGB_565"),

    YUY2(4, "YUV2"),

    YV12(5, "YV12");

    ImageFormat(int id, String constValue) {
        this.id = id;
        this.constantValue = constValue;
    }

    private static final String constantClassName = "android.graphics.ImageFormat";
    private final String constantValue;
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

    public int getValue() {

        try {
            loadClass();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(String.format("Class '%s' not found", constantClassName));
        }

        Field[] fields = constClass.getDeclaredFields();

        try {

            for (Field field : fields) {

                if (field.getName().equals(constantValue))
                    return (Integer) field.get(null);

            }

        } catch (IllegalAccessException e) {
            throw new RuntimeException(String.format("IllegalAccessException for %s", constantClassName + '.' + constantValue));
        }

        return -1;
    }

    public int getImageFormatValue() {
        int value = -1;
        switch (this) {
            case JPEG:
                value = android.graphics.ImageFormat.JPEG;
                break;
            case RGB_565:
                value = android.graphics.ImageFormat.RGB_565;
                break;
            case NV16:
                value = android.graphics.ImageFormat.NV16;
                break;
            case NV21:
                value = android.graphics.ImageFormat.NV21;
                break;
            case YUY2:
                value = android.graphics.ImageFormat.YUY2;
                break;
            case YV12:
                value = android.graphics.ImageFormat.YV12;
                break;
        }
        return value;
    }

    public static ImageFormat fromValue(int value) {
        ImageFormat result = null;
        for (ImageFormat imageFormat : ImageFormat.values()) {
            if (value == imageFormat.getValue()) {
                result = imageFormat;
                break;
            }
        }
        return result;
    }

}
