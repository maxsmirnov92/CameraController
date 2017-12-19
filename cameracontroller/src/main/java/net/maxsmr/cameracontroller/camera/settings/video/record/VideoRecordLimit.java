package net.maxsmr.cameracontroller.camera.settings.video.record;

import net.maxsmr.cameracontroller.camera.settings.video.RecordLimitWhat;

public class VideoRecordLimit {

	private final RecordLimitWhat what;

	public RecordLimitWhat getRecordLimitWhat() {
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

	public VideoRecordLimit(RecordLimitWhat what, long value) {

		if (what == null) {
			what = RecordLimitWhat.NONE;
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
