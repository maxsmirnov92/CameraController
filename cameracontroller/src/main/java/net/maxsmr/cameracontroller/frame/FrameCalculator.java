package net.maxsmr.cameracontroller.frame;

import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import net.maxsmr.cameracontroller.frame.stats.FrameStats;
import net.maxsmr.cameracontroller.frame.stats.IFrameStatsListener;
import net.maxsmr.commonutils.data.MathUtils;
import net.maxsmr.commonutils.data.Observable;
import net.maxsmr.commonutils.logger.base.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class FrameCalculator implements IFrameCallback {

    public static final long DEFAULT_CALCULATE_INTERVAL = TimeUnit.SECONDS.toMillis(1);

    public static final long DEFAULT_NOTIFY_INTERVAL = DEFAULT_CALCULATE_INTERVAL;

    private static Logger mFrameLogger;

    private final Object mSync = new Object();

    private final FrameStatsObservable mFrameStatsObservable = new FrameStatsObservable();

    private Handler mNotifyHandler;

    private ExecutorService mCalcExecutor;

    /** in ms */
    private long mCalculateInterval = DEFAULT_CALCULATE_INTERVAL;

    /** in ms */
    private long mNotifyInterval = DEFAULT_NOTIFY_INTERVAL;

    @Nullable
    private FrameStats mLastStats;

    private boolean mStreamStarted;

    /**
     * in ms
     */
    private long mStartStreamTime;

    /**
     * in ns
     */
    private long mStartIntervalTime;

    /**
     * in ms
     */
    private long mLastNotifyTime;

    /*
     * in ns
     */
    private long mLastFrameTime;

    private int mIntervalFrames;
    private double mLastFps;

    /** in ns */
    private final List<Long> mFrameTimesDuringInterval = new ArrayList<>();
    /** in ns */
    private double mLastAverageFrameTimeDuringInterval;

    private long mLastNotifyFramesCount;

    private long mTotalFrames;
    private long mTotalFpsSum;
    private int mTotalFpsCount = 0;
    private double mTotalFrameTimeSum;
    private int mTotalFrameTimesCount = 0;

    public FrameCalculator() {
        this(Looper.getMainLooper());
    }

    public FrameCalculator(@NonNull Looper notifyLooper) {
        mNotifyHandler = new Handler(notifyLooper);
    }

    @NonNull
    public Observable<IFrameStatsListener> getFrameStatsObservable() {
        return mFrameStatsObservable;
    }

    public long getCalculateInterval() {
        return mCalculateInterval;
    }

    public void setCalculateInterval(long calculateInterval) {
        if (calculateInterval <= 0) {
            throw new IllegalArgumentException("incorrect calculate interval: " + calculateInterval);
        }
        this.mCalculateInterval = calculateInterval;
    }

    public long getNotifyInterval() {
        return mNotifyInterval;
    }

    public void setNotifyInterval(long notifyInterval) {
        if (notifyInterval < 0) {
            throw new IllegalArgumentException("incorrect notify interval: " + notifyInterval);
        }
        if (notifyInterval != 0 && notifyInterval < mCalculateInterval) {
            notifyInterval = DEFAULT_NOTIFY_INTERVAL;
        }
        this.mNotifyInterval = notifyInterval;
    }

    public void setNotifyHandler(Handler notifyHandler) {
        if (notifyHandler == null) {
            mNotifyHandler = new Handler(Looper.getMainLooper());
        }
        mNotifyHandler = notifyHandler;
    }

    @Nullable
    public FrameStats getLastStats() {
        return mLastStats;
    }

    public boolean isStreamStarted() {
        return mStreamStarted;
    }

    public void notifySteamStarted() {
        synchronized (mSync) {
            resetAllCounters();
            startExec();
            mStartStreamTime = System.currentTimeMillis();
            mStreamStarted = true;
        }
    }

    public void notifyStreamFinished() {
        synchronized (mSync) {
            stopExec();
            mStartStreamTime = 0;
            mStreamStarted = false;
        }
    }

    /**
     * ms
     */
    public long getStartStreamTime() {
        return mStartStreamTime;
    }

    public double getLastAverageFrameTimeDuringInterval() {
        return mLastAverageFrameTimeDuringInterval;
    }

    /**
     * ms
     */
    public double getAverageFrameTime() {
        return mTotalFrameTimesCount > 0 ? (mTotalFrameTimeSum / mTotalFrameTimesCount) / 1000000 : 0;
    }

    public double getLastFps() {
        return mLastFps;
    }

    public double getAverageFpsMethod1() {
        return mTotalFpsCount > 0 ? (double) mTotalFpsSum / mTotalFpsCount : mLastFps;
    }

    public double getAverageFpsMethod2() {
        long currentTime = System.currentTimeMillis();
        long measureTime = mStartStreamTime > 0 ? (currentTime - mStartStreamTime) / 1000 : 0;
        return measureTime > 0 ? (double) mTotalFrames / measureTime : mLastFps;
    }

    @Override
    public long onFrame() {
        long time = 0;
        synchronized (mSync) {
            if (isStreamStarted()) {
                time = System.nanoTime();
                mCalcExecutor.execute(new FrameLogRunnable(time));
            }
        }
        return time;
    }

    private boolean isExecRunning() {
        return mCalcExecutor != null && !mCalcExecutor.isShutdown();
    }

    private void startExec() {
        stopExec();
        mCalcExecutor = Executors.newSingleThreadExecutor();
    }

    private void stopExec() {
        if (isExecRunning()) {
            mCalcExecutor.shutdown();
            mCalcExecutor = null;
        }
    }

    private void resetAllCounters() {
        synchronized (mSync) {

            mLastStats = null;

            mStartStreamTime = 0;
            mTotalFrames = 0;

            mLastFrameTime = 0;
            mLastAverageFrameTimeDuringInterval = 0;
            mFrameTimesDuringInterval.clear();
            mTotalFrameTimeSum = 0;
            mTotalFrameTimesCount = 0;

            mIntervalFrames = 0;
            mStartIntervalTime = 0;
            mLastFps = 0;
            mTotalFpsSum = 0;
            mTotalFpsCount = 0;

            mLastNotifyFramesCount = 0;
        }
    }

    public static void setFrameLogger(Logger frameLogger) {
        mFrameLogger = frameLogger;
        if (mFrameLogger == null) {
            mFrameLogger = new Logger.Stub();
        }
    }

    protected class FrameLogRunnable implements Runnable {

        /**
         * in ns
         */
        private final long mEventTime;

        /**
         * @param eventTime in ns
         */
        FrameLogRunnable(long eventTime) {
            mEventTime = eventTime;
            if (mStartIntervalTime == 0) {
                mStartIntervalTime = mEventTime;
            }
        }

        @Override
        public void run() {

            if (mEventTime < mStartIntervalTime) {
                mFrameLogger.error("event time < start interval time");
                return;
            }

            if (mLastFrameTime != 0 && mLastFrameTime < mEventTime) {
                mFrameTimesDuringInterval.add(mEventTime - mLastFrameTime);
            }
            mLastFrameTime = mEventTime;

            mIntervalFrames++;
            if (mEventTime - mStartIntervalTime >= TimeUnit.MILLISECONDS.toNanos(mCalculateInterval)) {

                double scale = TimeUnit.SECONDS.toMillis(1) / (double) mCalculateInterval;

                mLastAverageFrameTimeDuringInterval = MathUtils.avg(mFrameTimesDuringInterval);
                mTotalFrameTimeSum += mLastAverageFrameTimeDuringInterval;
                mTotalFrameTimesCount++;
                mFrameTimesDuringInterval.clear();

                mTotalFrames += mIntervalFrames;
                mLastFps = mIntervalFrames * scale;
                mTotalFpsSum += mLastFps;
                mTotalFpsCount++;
                mIntervalFrames = 0;

                mStartIntervalTime = 0;

                mLastStats = new FrameStats(mStartStreamTime, mLastFps, TimeUnit.NANOSECONDS.toMillis((int) mLastAverageFrameTimeDuringInterval), getAverageFpsMethod1(), getAverageFrameTime());
                if (mFrameLogger != null) {
                    mFrameLogger.debug("current frame time: " + mLastStats.lastAverageFrameTime +
                            " ms / overall average frame time: " + mLastStats.overallAverageFrameTime + " ms");
                    mFrameLogger.debug("current fps: " + mLastStats.lastFps + " / overall average fps: " + mLastStats.overallAverageFps);
                }
            }

            if (mLastStats != null) {
                if (mNotifyInterval == 0 || (mLastNotifyTime <= 0 || (mEventTime - mLastNotifyTime) >= mNotifyInterval)) {
                    final FrameStats lastStats = mLastStats;
                    final long framesSinseLastNotify = mTotalFrames - mLastNotifyFramesCount;
                    mNotifyHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mFrameStatsObservable.notifyStatsUpdated(lastStats, framesSinseLastNotify);
                        }
                    });
                    mLastNotifyTime = mEventTime;
                }
                mLastNotifyFramesCount = mTotalFrames;
            }
        }
    }

    private static class FrameStatsObservable extends Observable<IFrameStatsListener> {

        void notifyStatsUpdated(@NonNull FrameStats frameStats, long framesSinceLastNotify) {
            synchronized (mObservers) {
                for (IFrameStatsListener l : mObservers) {
                    l.onFrameStatsUpdated(frameStats, framesSinceLastNotify);
                }
            }
        }
    }
}
