package net.maxsmr.cameracontroller.frame.stats;

import android.os.Parcel;
import android.os.Parcelable;

public final class FrameStats implements Parcelable {

    public final long previewStartTime;

    public final float lastFps;

    public final float lastAverageFrameTime;

    public final float overallAverageFps;

    public final float overallAverageFrameTime;

    public FrameStats(long previewStartTime, float lastFps, float lastAverageFrameTime, float overallAverageFps, float overallAverageFrameTime) {
        this.previewStartTime = previewStartTime;
        this.lastFps = lastFps;
        this.lastAverageFrameTime = lastAverageFrameTime;
        this.overallAverageFps = overallAverageFps;
        this.overallAverageFrameTime = overallAverageFrameTime;
    }

    protected FrameStats(Parcel in) {
        previewStartTime = in.readLong();
        lastFps = in.readFloat();
        lastAverageFrameTime = in.readFloat();
        overallAverageFps = in.readFloat();
        overallAverageFrameTime = in.readFloat();
    }

    public static final Creator<FrameStats> CREATOR = new Creator<FrameStats>() {
        @Override
        public FrameStats createFromParcel(Parcel in) {
            return new FrameStats(in);
        }

        @Override
        public FrameStats[] newArray(int size) {
            return new FrameStats[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FrameStats that = (FrameStats) o;

        if (Float.compare(that.lastFps, lastFps) != 0) return false;
        if (Float.compare(that.lastAverageFrameTime, lastAverageFrameTime) != 0) return false;
        if (Float.compare(that.overallAverageFps, overallAverageFps) != 0) return false;
        return Float.compare(that.overallAverageFrameTime, overallAverageFrameTime) == 0;
    }

    @Override
    public int hashCode() {
        int result = (lastFps != +0.0f ? Float.floatToIntBits(lastFps) : 0);
        result = 31 * result + (lastAverageFrameTime != +0.0f ? Float.floatToIntBits(lastAverageFrameTime) : 0);
        result = 31 * result + (overallAverageFps != +0.0f ? Float.floatToIntBits(overallAverageFps) : 0);
        result = 31 * result + (overallAverageFrameTime != +0.0f ? Float.floatToIntBits(overallAverageFrameTime) : 0);
        return result;
    }

    @Override
    public String toString() {
        return "FrameStats{" +
                "lastFps=" + lastFps +
                ", lastAverageFrameTime=" + lastAverageFrameTime +
                ", overallAverageFps=" + overallAverageFps +
                ", overallAverageFrameTime=" + overallAverageFrameTime +
                '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeLong(previewStartTime);
        parcel.writeFloat(lastFps);
        parcel.writeFloat(lastAverageFrameTime);
        parcel.writeFloat(overallAverageFps);
        parcel.writeFloat(overallAverageFrameTime);
    }
}
