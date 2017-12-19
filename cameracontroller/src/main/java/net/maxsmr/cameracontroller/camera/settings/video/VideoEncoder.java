package net.maxsmr.cameracontroller.camera.settings.video;

import java.lang.reflect.Field;

public enum VideoEncoder {

	DEFAULT(0, "DEFAULT"),

	H263(1, "H263"),

	H264(2, "H264"),

	MPEG_4_SP(3, "MPEG_4_SP");

	VideoEncoder(int id, String constValue) {
		this.id = id;
		this.constantValue = constValue;
	}

	private static final String constantClassName = "android.media.MediaRecorder$VideoEncoder";
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

	public static VideoEncoder fromValue(int value) throws IllegalArgumentException {

		for (VideoEncoder videoEncoder : VideoEncoder.values()) {
			if (videoEncoder.getValue() == value) {
				return videoEncoder;
			}
		}

		throw new IllegalArgumentException("Incorrect native value for enum type " + VideoEncoder.class.getName() + ": " + value);
	}
}