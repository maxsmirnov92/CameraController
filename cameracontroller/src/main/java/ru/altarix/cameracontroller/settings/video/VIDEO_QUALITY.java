package ru.altarix.cameracontroller.settings.video;

import java.lang.reflect.Field;

public enum VIDEO_QUALITY {

	DEFAULT(-1, null),

	HIGH(0, "QUALITY_HIGH"),

	LOW(2, "QUALITY_LOW"),

	_1080P(3, "QUALITY_1080P"),

	_720P(4, "QUALITY_720P"),

	_480P(5, "QUALITY_480P"),

	CIF(6, "QUALITY_CIF"),

	QCIF(7, "QUALITY_QCIF"),

	QVGA(8, "QUALITY_QVGA");

	VIDEO_QUALITY(int id, String constValue) {
		this.id = id;
		this.constantValue = constValue;
	}

	private static final String constantClassName = "android.media.CamcorderProfile";
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

		if (constantValue == null) {
			return id;
		}

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

	public static VIDEO_QUALITY fromNativeValue(int value) throws IllegalArgumentException {

		for (VIDEO_QUALITY quality : VIDEO_QUALITY.values()) {
			if (quality.getValue() == value) {
				return quality;
			}
		}
		throw new IllegalArgumentException("Incorrect native value for enum type " + VIDEO_QUALITY.class.getName() + ": " + value);
	}
}
