package net.maxsmr.cameracontroller.frame;

import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import net.maxsmr.cameracontroller.logger.base.Logger;
import net.maxsmr.commonutils.data.MathUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.maxsmr.cameracontroller.frame.stats.FrameStats;
import net.maxsmr.cameracontroller.frame.stats.IFrameStatsListener;
import net.maxsmr.commonutils.data.Observable;

public class FrameCalculator implements IPreviewFrameCallback {

    public static final long DEFAULT_NOTIFY_INTERVAL = 1000;

    private final Object mSync = new Object();

    private final FrameStatsObservable mFrameStatsObservable = new FrameStatsObservable();

    private Handler mNotifyHandler;

    private ExecutorService mCalcExecutor;

    private long mNotifyInterval = DEFAULT_NOTIFY_INTERVAL;

    @Nullable
    private FrameStats mLastStats;

    private boolean mPreviewStarted;

    /**
     * in ms
     */
    private long mLastNotifyTime;

    /**
     * in ms
     */
    private long mStartPreviewTime;

    /**
     * in ns
     */
    private long mStartIntervalTime;
    private long mLastFrameTime;

    private int mIntervalFrames;
    private int mLastFps;

    private final List<Long> mFrameTimesDuringInterval = new ArrayList<>();
    private float mLastAverageFrameTimeDuringInterval;

    private long mLastNotifyFramesCount;

    private long mTotalFrames;
    private long mTotalFpsSum;
    private int mTotalFpsCount = 0;
    private float mTotalFrameTimeSum;
    private int mTotalFrameTimesCount = 0;

    private Logger mFrameLogger;

    public FrameCalculator(Logger logger) {
        mNotifyHandler = new Handler(Looper.getMainLooper());
        setFrameLogger(logger);
    }

    @NonNull
    public Observable<IFrameStatsListener> getFrameStatsObservable() {
        return mFrameStatsObservable;
    }

    public long getNotifyInterval() {
        return mNotifyInterval;
    }

    public void setNotifyInterval(long notifyInterval) {
        if (notifyInterval < 0) {
            throw new IllegalArgumentException("incorrect notify interval: " + notifyInterval);
        }
        if (notifyInterval != 0 && notifyInterval < DEFAULT_NOTIFY_INTERVAL) {
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

    public void setFrameLogger(Logger frameLogger) {
        mFrameLogger = frameLogger;
        if (mFrameLogger == null) {
            mFrameLogger = new Logger.Stub();
        }
    }

    @Nullable
    public FrameStats getLastStats() {
        return mLastStats;
    }

    public boolean isPreviewStarted() {
        return mPreviewStarted;
    }

    public void notifyPreviewStarted() {
        synchronized (mSync) {
            resetAllCounters();
            startExec();
            mStartPreviewTime = System.currentTimeMillis();
            mPreviewStarted = true;
        }
    }

    public void notifyPreviewFinished() {
        synchronized (mSync) {
            stopExec();
            mPreviewStarted = false;
        }
    }

    /**
     * ms
     */
    public long getStartPreviewTime() {
        return mStartPreviewTime;
    }

    public float getLastAverageFrameTimeDuringInterval() {
        return mLastAverageFrameTimeDuringInterval;
    }

    /**
     * ms
     */
    public float getAverageFrameTime() {
        return mTotalFrameTimesCount > 0 ? (mTotalFrameTimeSum / mTotalFrameTimesCount) / 1000000 : 0;
    }

    public int getLastFps() {
        return mLastFps;
    }

    public float getAverageFpsMethod1() {
        return mTotalFpsCount > 0 ? (float) mTotalFpsSum / mTotalFpsCount : mLastFps;
    }

    public float getAverageFpsMethod2() {
        long currentTime = System.currentTimeMillis();
        long measureTime = mStartPreviewTime > 0 ? (currentTime - mStartPreviewTime) / 1000 : 0;
        return measureTime > 0 ? (float) mTotalFrames / measureTime : mLastFps;
    }

    @Override
    public void onPreviewFrame() {
        synchronized (mSync) {
            if (isPreviewStarted()) {
                mCalcExecutor.execute(new FrameLogRunnable(System.nanoTime()));
            }
        }
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

            mStartPreviewTime = 0;
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
                throw new IllegalArgumentException("happen time < start interval time");
            }

            if (mLastFrameTime != 0 && mLastFrameTime < mEventTime) {
                mFrameTimesDuringInterval.add(mEventTime - mLastFrameTime);
            }
            mLastFrameTime = mEventTime;

            mIntervalFrames++;
            if (mEventTime - mStartIntervalTime >= 1000000000) {

                mLastAverageFrameTimeDuringInterval = (float) MathUtils.avg(mFrameTimesDuringInterval);
                mTotalFrameTimeSum += mLastAverageFrameTimeDuringInterval;
                mTotalFrameTimesCount++;
                mFrameTimesDuringInterval.clear();

                mTotalFrames += mIntervalFrames;
                mLastFps = mIntervalFrames;
                mTotalFpsSum += mLastFps;
                mTotalFpsCount++;
                mIntervalFrames = 0;

                mStartIntervalTime = 0;

                mLastStats = new FrameStats(mStartPreviewTime, mLastFps, mLastAverageFrameTimeDuringInterval / 1000000, getAverageFpsMethod1(), getAverageFrameTime());
                if (mFrameLogger != null) {
                    mFrameLogger.debug("current frame time: " + mLastStats.lastAverageFrameTime +
                            " ms / overall average frame time: " + mLastStats.overallAverageFrameTime + " ms");
                    mFrameLogger.debug("current fps: " + mLastStats.lastFps + " / overall average fps: " + mLastStats.overallAverageFps);
                }
            }

            if (mLastStats != null) {
                if (mNotifyInterval > 0 && (mLastNotifyTime <= 0 || (mEventTime - mLastNotifyTime) >= mNotifyInterval)) {
                    mNotifyHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mFrameStatsObservable.notifyStatsUpdated(mLastStats, mTotalFrames - mLastNotifyFramesCount);
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
