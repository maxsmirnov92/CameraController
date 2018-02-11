package net.maxsmr.cameracontroller.frame;

public interface IFrameCallback {

    void notifySteamStarted();

    void notifyStreamFinished();

    /** @return frame time in ns if handled, 0 - otherwise */
    long onFrame();
}
