package net.maxsmr.cameracontroller.frame.stats;

import android.support.annotation.NonNull;

public interface IFrameStatsListener {

    void onFrameStatsUpdated(@NonNull FrameStats stats, long framesSinceLastNotify);
}
