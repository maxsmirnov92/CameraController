package net.maxsmr.cameracontroller.frame.stats;

import org.jetbrains.annotations.NotNull;

public interface IFrameStatsListener {

    void onFrameStatsUpdated(@NotNull FrameStats stats, long framesSinceLastNotify);
}
