package ru.altarix.cameracontroller.settings.video.record;

import ru.altarix.cameracontroller.settings.video.RECORD_LIMIT_WHAT;

public class VideoRecordLimit {

	private final RECORD_LIMIT_WHAT what;

	public RECORD_LIMIT_WHAT getRecordLimitWhat() {
		return what;
	}

	private final long maxDurationMs;
	private final long maxSizeBytes;

	public long getRecordLimitValue() {
		switch (what) {
		case TIME:
			return maxDurationMs;
		case SIZE:
			return maxSizeBytes;
		default:
			return 0;
		}
	}

	public VideoRecordLimit(RECORD_LIMIT_WHAT what, long value) {

		if (what == null) {
			what = RECORD_LIMIT_WHAT.NONE;
		}

		this.what = what;

		switch (what) {

		case TIME:
			maxDurationMs = value;
			maxSizeBytes = 0;
			break;

		case SIZE:
			maxDurationMs = 0;
			maxSizeBytes = value;
			break;

		default:
			maxDurationMs = 0;
			maxSizeBytes = 0;
			break;
		}

	}

	@Override
	public String toString() {
		return "VideoRecordLimit [what=" + what + ", maxDurationMs=" + maxDurationMs + ", maxSizeBytes=" + maxSizeBytes + "]";
	}

}
