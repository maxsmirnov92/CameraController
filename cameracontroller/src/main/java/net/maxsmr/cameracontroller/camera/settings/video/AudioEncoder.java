package net.maxsmr.cameracontroller.camera.settings.video;

import net.maxsmr.cameracontroller.camera.settings.photo.ImageFormat;

import java.lang.reflect.Field;

public enum AudioEncoder {

	DEFAULT(0, "DEFAULT"),

	AAC(1, "AAC"),

	AMR_NB(2, "AMR_NB"),

	AMR_WB(3, "AMR_WB");

	AudioEncoder(int id, String constValue) {
		this.id = id;
		this.constantValue = constValue;
	}

	private static final String constantClassName = "android.media.MediaRecorder$AudioEncoder";
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

	public static AudioEncoder fromValue(int value) {
		AudioEncoder result = null;
		for (AudioEncoder encoder : AudioEncoder.values()) {
			if (value == encoder.getValue()) {
				result = encoder;
				break;
			}
		}
		return result;
	}
}