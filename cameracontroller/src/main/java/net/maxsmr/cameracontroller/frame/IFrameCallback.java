package net.maxsmr.cameracontroller.frame;

public interface IFrameCallback {

    void notifySteamStarted();

    void notifyStreamFinished();

    void onFrame();
}
