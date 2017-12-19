package net.maxsmr.cameracontroller.frame;

public interface IPreviewFrameCallback {

    void notifyPreviewStarted();

    void notifyPreviewFinished();

    void onPreviewFrame();
}
