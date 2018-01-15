package net.maxsmr.cameracontroller.frame.stats;

import android.os.Parcel;
import android.os.Parcelable;

public final class FrameStats implements Parcelable {

    public final long previewStartTime;

    public final double lastFps;

    public final double lastAverageFrameTime;

    public final double overallAverageFps;

    public final double overallAverageFrameTime;

    public FrameStats(long previewStartTime, double lastFps, double lastAverageFrameTime, double overallAverageFps, double overallAverageFrameTime) {
        this.previewStartTime = previewStartTime;
        this.lastFps = lastFps;
        this.lastAverageFrameTime = lastAverageFrameTime;
        this.overallAverageFps = overallAverageFps;
        this.overallAverageFrameTime = overallAverageFrameTime;
    }

    protected FrameStats(Parcel in) {
        previewStartTime = in.readLong();
        lastFps = in.readDouble();
        lastAverageFrameTime = in.readDouble();
        overallAverageFps = in.readDouble();
        overallAverageFrameTime = in.readDouble();
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

        if (previewStartTime != that.previewStartTime) return false;
        if (Double.compare(that.lastFps, lastFps) != 0) return false;
        if (Double.compare(that.lastAverageFrameTime, lastAverageFrameTime) != 0) return false;
        if (Double.compare(that.overallAverageFps, overallAverageFps) != 0) return false;
        return Double.compare(that.overallAverageFrameTime, overallAverageFrameTime) == 0;
    }

    @Override
    public int hashCode() {
        int result;
        long temp;
        result = (int) (previewStartTime ^ (previewStartTime >>> 32));
        temp = Double.doubleToLongBits(lastFps);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(lastAverageFrameTime);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(overallAverageFps);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(overallAverageFrameTime);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
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
        parcel.writeDouble(lastFps);
        parcel.writeDouble(lastAverageFrameTime);
        parcel.writeDouble(overallAverageFps);
        parcel.writeDouble(overallAverageFrameTime);
    }
}
