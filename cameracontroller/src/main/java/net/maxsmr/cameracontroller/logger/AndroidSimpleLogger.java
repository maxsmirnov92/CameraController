package net.maxsmr.cameracontroller.logger;


import android.util.Log;

import net.maxsmr.cameracontroller.logger.base.BaseSimpleLogger;

public class AndroidSimpleLogger extends BaseSimpleLogger {

    private final String mName;

    public AndroidSimpleLogger(String name) {
        mName = name;
    }

    @Override
    public void info(String message) {
        if (isLoggingEnabled()) {
            if (message != null) {
                Log.i(mName, message);
            }
        }
    }

    @Override
    public void debug(String message) {
        if (isLoggingEnabled()) {
            if (message != null) {
                Log.d(mName, message);
            }
        }
    }

    @Override
    public void warn(String message) {
        if (isLoggingEnabled()) {
            if (message != null) {
                Log.w(mName, message);
            }
        }
    }

    @Override
    public void warn(Throwable exception) {
        if (isLoggingEnabled()) {
            if (exception != null) {
                Log.w(mName, exception.getMessage(), exception);
            }
        }
    }

    @Override
    public void warn(String message, Throwable exception) {
        if (isLoggingEnabled()) {
            Log.w(mName, message, exception);
        }
    }

    @Override
    public void error(String message) {
        if (isLoggingEnabled()) {
            if (message != null) {
                Log.e(mName, message);
            }
        }
    }

    @Override
    public void error(Throwable exception) {
        if (isLoggingEnabled()) {
            if (exception != null) {
                Log.e(mName, exception.getMessage(), exception);
            }
        }
    }

    @Override
    public void error(String message, Throwable exception) {
        if (isLoggingEnabled()) {
            Log.e(mName, message, exception);
        }
    }
}
