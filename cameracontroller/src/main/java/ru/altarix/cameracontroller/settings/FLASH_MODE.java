package ru.altarix.cameracontroller.settings;

import java.lang.reflect.Field;

public enum FLASH_MODE{

	OFF(0, "FLASH_MODE_OFF"),

	ON(1, "FLASH_MODE_ON"),

	RED_EYE(2, "FLASH_MODE_RED_EYE"),

	TORCH(3, "FLASH_MODE_TORCH"),

	AUTO(4, "FLASH_MODE_AUTO");

	FLASH_MODE(int id, String constValue) {
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

	public static FLASH_MODE fromNativeValue(String value) throws IllegalArgumentException {

		if (value == null) {
			return null;
		}

		for (FLASH_MODE flashMode : FLASH_MODE.values()) {
			if (flashMode.getValue() != null && flashMode.getValue().equals(value)) {
				return flashMode;
			}
		}

		throw new IllegalArgumentException("Incorrect native value for enum type " + FLASH_MODE.class.getName() + ": " + value);
	}

}
