package net.maxsmr.cameracontroller.camera.settings.video;

public enum RecordLimitWhat {

    NONE(-1),

    TIME(0),

    SIZE(1);

    private final int value;

    RecordLimitWhat(int value) {
        this.value = value;
    }

    public int getId() {
        return value;
    }

    public int getValue() {
        return value;
    }

    public static RecordLimitWhat fromValue(int value) throws IllegalArgumentException {
        switch (value) {
            case -1:
                return NONE;
            case 0:
                return TIME;
            case 1:
                return SIZE;
            default:
                throw new IllegalArgumentException("Incorrect value for enum type " + RecordLimitWhat.class.getName() + ": " + value);
        }
    }


}
