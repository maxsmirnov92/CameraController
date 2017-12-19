package net.maxsmr.cameracontroller.camera.settings.photo;

import java.lang.reflect.Field;

public enum ImageFormat {

	JPEG(0, "JPEG"),

	NV21(1, "NV21"),

	NV16(2, "NV16"),

	RGB_565(3, "RGB_565"),

	YUY2(4, "YUV2"),

	YV12(5, "YV12");

	ImageFormat(int id, String constValue) {
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

	public static ImageFormat fromValue(int value) throws IllegalArgumentException {

		for (ImageFormat imageFormat : ImageFormat.values()) {
			if (imageFormat.getValue() == value) {
				return imageFormat;
			}
		}

		throw new IllegalArgumentException("Incorrect native value for enum type " + ImageFormat.class.getName() + ": " + value);
	}

	public boolean isPreviewFormat() {
		return (this == ImageFormat.NV21 || this == ImageFormat.NV16 || this == ImageFormat.YUY2 || this == ImageFormat.YV12);
	}

	public boolean isPictureFormat() {
		return (this == ImageFormat.JPEG || this == ImageFormat.NV16 || this == ImageFormat.NV21 || this == ImageFormat.RGB_565
				|| this == ImageFormat.YUY2 || this == ImageFormat.YV12);
	}
}
