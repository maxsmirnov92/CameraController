package ru.altarix.cameracontroller;

public enum CAMERA_STATE {
    IDLE {
        @Override
        public int getValue() {
            return -1;
        };
    },

    TAKING_PHOTO {
        @Override
        public int getValue() {
            return 0;
        };
    },

    RECORDING_VIDEO {
        @Override
        public int getValue() {
            return 1;
        }
    };

    public int getValue() {
        return -1;
    }

    public static int getCount() {
        return CAMERA_STATE.values().length;
    }

    public static CAMERA_STATE fromNativeValue(int value) {
        for (CAMERA_STATE e : CAMERA_STATE.values()) {
            if (e.getValue() == value)
                return e;
        }
        throw new IllegalArgumentException("Incorrect native value " + String.valueOf(value) + " for enum type "
                + CAMERA_STATE.class.getName());
    }
}
