package net.maxsmr.cameracontroller.frame.producer;

import android.os.Handler;
import android.os.Looper;

import net.maxsmr.cameracontroller.frame.IPreviewFrameCallback;
import net.maxsmr.commonutils.data.MathUtils;
import net.maxsmr.tasksutils.ScheduledThreadPoolExecutorManager;

public class FakePreviewFrameProducer {

    private final ScheduledThreadPoolExecutorManager mExecutorManager = new ScheduledThreadPoolExecutorManager(ScheduledThreadPoolExecutorManager.ScheduleMode.FIXED_RATE, "Fake Frame Thread");

    private IPreviewFrameCallback mPreviewCallback;

    private Handler mPreviewHandler;

    public boolean isRunning() {
        return mExecutorManager.isRunning();
    }

    public void startPreview(IPreviewFrameCallback callback, int interval, int delay) {
        if (callback != null) {
            stopPreview();
            mPreviewCallback = callback;
            mPreviewHandler = new Handler(Looper.myLooper());
            mPreviewCallback.notifyPreviewStarted();
            mExecutorManager.addRunnableTask(new PreviewRunnable(delay));
            mExecutorManager.start(interval);
        }
    }

    public void stopPreview() {
        mExecutorManager.stop(false, 0);
        if (mPreviewCallback != null) {
            mPreviewCallback.notifyPreviewFinished();
            mPreviewCallback = null;
        }
        mPreviewHandler = null;
    }

    private class PreviewRunnable implements Runnable {

        private final int mDelay;

        private PreviewRunnable(int delay) {
            mDelay = delay;
            if (delay < 0) {
                throw new IllegalArgumentException("incorrect delay: " + delay);
            }
        }

        @Override
        public void run() {
            if (mPreviewHandler != null) {
                int delay = mDelay > 0? MathUtils.randInt(0, mDelay) : 0;
                mPreviewHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mPreviewCallback.onPreviewFrame();
                    }
                }, delay);
            }
        }
    }

}
