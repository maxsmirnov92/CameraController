package net.maxsmr.cameracontroller.logger.base;

public abstract class BaseSimpleLogger implements Logger {

    private boolean mLoggingEnabled;

    public boolean isLoggingEnabled() {
        return mLoggingEnabled;
    }

    public void setLoggingEnabled(boolean mLoggingEnabled) {
        this.mLoggingEnabled = mLoggingEnabled;
    }
}
