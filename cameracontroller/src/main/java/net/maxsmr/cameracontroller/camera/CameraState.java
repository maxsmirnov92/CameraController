package net.maxsmr.cameracontroller.camera;

public enum CameraState {
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
        return CameraState.values().length;
    }

    public static CameraState fromValue(int value) {
        for (CameraState e : CameraState.values()) {
            if (e.getValue() == value)
                return e;
        }
        throw new IllegalArgumentException("Incorrect native value " + String.valueOf(value) + " for enum type "
                + CameraState.class.getName());
    }
}
