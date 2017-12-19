package net.maxsmr.cameracontroller.camera.settings;

import java.lang.reflect.Field;

public enum ColorEffect {

	NONE(0, "EFFECT_NONE"),

	MONOCHROME(1, "EFFECT_MONO"),

	NEGATIVE(2, "EFFECT_NEGATIVE"),

	POSTERIZE(3, "EFFECT_POSTERIZE"),

	SEPIA(4, "EFFECT_SEPIA"),

	SOLARIZE(5, "EFFECT_SOLARIZE"),

	WHITEBOARD(6, "EFFECT_WHITEBOARD");

	ColorEffect(int id, String constValue) {
		this.id = id;
		this.constantValue = constValue;
	}

	private static final String constantClassName = "android.hardware.Camera$Parameters";
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

	public static ColorEffect fromValue(String value) throws IllegalArgumentException {

		if (value == null) {
			return null;
		}

		for (ColorEffect colorEffect : ColorEffect.values()) {
			if (colorEffect.getValue() != null && colorEffect.getValue().equals(value)) {
				return colorEffect;
			}
		}

		throw new IllegalArgumentException("Incorrect native value for enum type " + ColorEffect.class.getName() + ": " + value);
	}

}
