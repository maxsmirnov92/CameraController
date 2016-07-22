package ru.altarix.cameracontroller.settings.video;

public enum RECORD_LIMIT_WHAT {

    NONE(-1),

    TIME(0),

    SIZE(1);

    private final int value;

    RECORD_LIMIT_WHAT(int value) {
        this.value = value;
    }

    public int getId() {
        return value;
    }

    public int getValue() {
        return value;
    }

    public static RECORD_LIMIT_WHAT fromNativeValue(int value) throws IllegalArgumentException {

        switch (value) {
            case -1:
                return NONE;
            case 0:
                return TIME;
            case 1:
                return SIZE;
            default:
                throw new IllegalArgumentException("Incorrect native value for enum type " + RECORD_LIMIT_WHAT.class.getName() + ": " + value);
        }
    }


}
