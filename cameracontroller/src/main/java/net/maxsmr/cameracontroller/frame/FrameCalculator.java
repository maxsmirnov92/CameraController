package net.maxsmr.cameracontroller.frame;

import android.os.Handler;
import android.os.Looper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.maxsmr.cameracontroller.frame.stats.FrameStats;
import net.maxsmr.cameracontroller.frame.stats.IFrameStatsListener;
import net.maxsmr.commonutils.data.MathUtils;
import net.maxsmr.commonutils.data.Observable;
import net.maxsmr.commonutils.logger.BaseLogger;
import net.maxsmr.commonutils.logger.holder.BaseLoggerHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class FrameCalculator implements IFrameCallback {

    public static final long DEFAULT_CALCULATE_INTERVAL = TimeUnit.SECONDS.toMillis(1);

    public static final long DEFAULT_NOTIFY_INTERVAL = DEFAULT_CALCULATE_INTERVAL;

    private static final BaseLogger logger = BaseLoggerHolder.getInstance().getLogger(FrameCalculator.class);

    private final Object sync = new Object();

    private final FrameStatsObservable frameStatsObservable = new FrameStatsObservable();

    private Handler notifyHandler;

    private ExecutorService calcExecutor;

    /**
     * in ms
     */
    private long calculateInterval = DEFAULT_CALCULATE_INTERVAL;

    /**
     * in ms
     */
    private long notifyInterval = DEFAULT_NOTIFY_INTERVAL;

    @Nullable
    private FrameStats lastStats;

    private boolean isStreamStarted;

    /**
     * in ms
     */
    private long startStreamTime;

    /**
     * in ns
     */
    private long startIntervalTime;

    /**
     * in ms
     */
    private long lastNotifyTime;

    /*
     * in ns
     */
    private long lastFrameTime;

    private int intervalFrames;
    private double lastFps;

    /**
     * in ns
     */
    private final List<Long> frameTimesDuringInterval = new ArrayList<>();
    /**
     * in ns
     */
    private double lastAverageFrameTimeDuringInterval;

    private long lastNotifyFramesCount;

    private long totalFrames;
    private long totalFpsSum;
    private int totalFpsCount = 0;
    private double totalFrameTimeSum;
    private int totalFrameTimesCount = 0;

    public FrameCalculator() {
        this(Looper.getMainLooper());
    }

    public FrameCalculator(@NotNull Looper notifyLooper) {
        notifyHandler = new Handler(notifyLooper);
    }

    @NotNull
    public Observable<IFrameStatsListener> getFrameStatsObservable() {
        return frameStatsObservable;
    }

    public long getCalculateInterval() {
        return calculateInterval;
    }

    public void setCalculateInterval(long calculateInterval) {
        if (calculateInterval <= 0) {
            throw new IllegalArgumentException("incorrect calculate interval: " + calculateInterval);
        }
        if (calculateInterval > notifyInterval) {
            calculateInterval = DEFAULT_CALCULATE_INTERVAL;
        }
        this.calculateInterval = calculateInterval;
    }

    public long getNotifyInterval() {
        return notifyInterval;
    }

    public void setNotifyInterval(long notifyInterval) {
        if (notifyInterval < 0) {
            throw new IllegalArgumentException("incorrect notify interval: " + notifyInterval);
        }
        if (notifyInterval != 0 && notifyInterval < calculateInterval) {
            notifyInterval = DEFAULT_NOTIFY_INTERVAL;
        }
        this.notifyInterval = notifyInterval;
    }

    public void setNotifyHandler(Handler notifyHandler) {
        if (notifyHandler == null) {
            this.notifyHandler = new Handler(Looper.getMainLooper());
        }
        this.notifyHandler = notifyHandler;
    }

    @Nullable
    public FrameStats getLastStats() {
        return lastStats;
    }

    public boolean isStreamStarted() {
        return isStreamStarted;
    }

    public void notifySteamStarted() {
        synchronized (sync) {
            resetAllCounters();
            startExec();
            startStreamTime = System.currentTimeMillis();
            isStreamStarted = true;
        }
    }

    public void notifyStreamFinished() {
        synchronized (sync) {
            stopExec();
            startStreamTime = 0;
            isStreamStarted = false;
        }
    }

    /**
     * ms
     */
    public long getStartStreamTime() {
        return startStreamTime;
    }

    public double getLastAverageFrameTimeDuringInterval() {
        return lastAverageFrameTimeDuringInterval;
    }

    /**
     * ms
     */
    public double getAverageFrameTime() {
        return totalFrameTimesCount > 0 ? (totalFrameTimeSum / totalFrameTimesCount) / 1000000 : 0;
    }

    public double getLastFps() {
        return lastFps;
    }

    public double getAverageFpsMethod1() {
        return totalFpsCount > 0 ? (double) totalFpsSum / totalFpsCount : lastFps;
    }

    public double getAverageFpsMethod2() {
        long currentTime = System.currentTimeMillis();
        long measureTime = startStreamTime > 0 ? (currentTime - startStreamTime) / 1000 : 0;
        return measureTime > 0 ? (double) totalFrames / measureTime : lastFps;
    }

    @Override
    public long onFrame() {
        long time = 0;
        synchronized (sync) {
            if (isStreamStarted()) {
                time = System.nanoTime();
                calcExecutor.execute(new FrameLogRunnable(time));
            }
        }
        return time;
    }

    private boolean isExecRunning() {
        return calcExecutor != null && !calcExecutor.isShutdown();
    }

    private void startExec() {
        stopExec();
        calcExecutor = Executors.newSingleThreadExecutor();
    }

    private void stopExec() {
        if (isExecRunning()) {
            calcExecutor.shutdown();
            calcExecutor = null;
        }
    }

    private void resetAllCounters() {
        synchronized (sync) {

            lastStats = null;

            startStreamTime = 0;
            totalFrames = 0;

            lastFrameTime = 0;
            lastAverageFrameTimeDuringInterval = 0;
            frameTimesDuringInterval.clear();
            totalFrameTimeSum = 0;
            totalFrameTimesCount = 0;

            intervalFrames = 0;
            startIntervalTime = 0;
            lastFps = 0;
            totalFpsSum = 0;
            totalFpsCount = 0;

            lastNotifyFramesCount = 0;
        }
    }

    protected class FrameLogRunnable implements Runnable {

        /**
         * in ns
         */
        private final long eventTime;

        /**
         * @param eventTime in ns
         */
        FrameLogRunnable(long eventTime) {
            this.eventTime = eventTime;
            if (startIntervalTime == 0) {
                startIntervalTime = eventTime;
            }
        }

        @Override
        public void run() {

            if (eventTime < startIntervalTime) {
                logger.e("event time (" + eventTime + ") < start interval time (" + startIntervalTime + ")");
                return;
            }

            if (lastFrameTime != 0 && lastFrameTime < eventTime) {
                frameTimesDuringInterval.add(eventTime - lastFrameTime);
            }
            lastFrameTime = eventTime;

            intervalFrames++;
            if (eventTime - startIntervalTime >= TimeUnit.MILLISECONDS.toNanos(calculateInterval)) {

                double scale = TimeUnit.SECONDS.toMillis(1) / (double) calculateInterval;

                lastAverageFrameTimeDuringInterval = MathUtils.avg(frameTimesDuringInterval);
                totalFrameTimeSum += lastAverageFrameTimeDuringInterval;
                totalFrameTimesCount++;
                frameTimesDuringInterval.clear();

                totalFrames += intervalFrames;
                lastFps = intervalFrames * scale;
                totalFpsSum += lastFps;
                totalFpsCount++;
                intervalFrames = 0;

                startIntervalTime = 0;

                lastStats = new FrameStats(startStreamTime, lastFps, TimeUnit.NANOSECONDS.toMillis((int) lastAverageFrameTimeDuringInterval), getAverageFpsMethod1(), getAverageFrameTime());
                logger.d("current frame time: " + lastStats.lastAverageFrameTime +
                        " ms / overall average frame time: " + lastStats.overallAverageFrameTime + " ms");
                logger.d("current fps: " + lastStats.lastFps + " / overall average fps: " + lastStats.overallAverageFps);
            }

            if (lastStats != null) {
                if (notifyInterval == 0 || (lastNotifyTime <= 0 || (eventTime - lastNotifyTime) >= notifyInterval)) {
                    final FrameStats lastStats = FrameCalculator.this.lastStats;
                    final long framesSinseLastNotify = totalFrames - lastNotifyFramesCount;
                    notifyHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            frameStatsObservable.notifyStatsUpdated(lastStats, framesSinseLastNotify);
                        }
                    });
                    lastNotifyTime = eventTime;
                }
                lastNotifyFramesCount = totalFrames;
            }
        }
    }

    private static class FrameStatsObservable extends Observable<IFrameStatsListener> {

        void notifyStatsUpdated(@NotNull FrameStats frameStats, long framesSinceLastNotify) {
            synchronized (observers) {
                for (IFrameStatsListener l : observers) {
                    l.onFrameStatsUpdated(frameStats, framesSinceLastNotify);
                }
            }
        }
    }
}
