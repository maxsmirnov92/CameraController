package ru.altarix.cameracontroller.settings.photo;

import java.lang.reflect.Field;

public enum IMAGE_FORMAT {

	JPEG(0, "JPEG"),

	NV21(1, "NV21"),

	NV16(2, "NV16"),

	RGB_565(3, "RGB_565"),

	YUY2(4, "YUV2"),

	YV12(5, "YV12");

	IMAGE_FORMAT(int id, String constValue) {
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

	public static IMAGE_FORMAT fromNativeValue(int value) throws IllegalArgumentException {

		for (IMAGE_FORMAT imageFormat : IMAGE_FORMAT.values()) {
			if (imageFormat.getValue() == value) {
				return imageFormat;
			}
		}

		throw new IllegalArgumentException("Incorrect native value for enum type " + IMAGE_FORMAT.class.getName() + ": " + value);
	}

	public boolean isPreviewFormat() {
		return (this == IMAGE_FORMAT.NV21 || this == IMAGE_FORMAT.NV16 || this == IMAGE_FORMAT.YUY2 || this == IMAGE_FORMAT.YV12);
	}

	public boolean isPictureFormat() {
		return (this == IMAGE_FORMAT.JPEG || this == IMAGE_FORMAT.NV16 || this == IMAGE_FORMAT.NV21 || this == IMAGE_FORMAT.RGB_565
				|| this == IMAGE_FORMAT.YUY2 || this == IMAGE_FORMAT.YV12);
	}
}
