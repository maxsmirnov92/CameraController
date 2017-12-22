package net.maxsmr.cameracontroller.executors.info;

import net.maxsmr.tasksutils.taskexecutor.RunnableInfo;

import java.io.File;
import java.io.Serializable;

import net.maxsmr.cameracontroller.camera.settings.video.record.VideoSettings;


public class MakePreviewRunnableInfo extends RunnableInfo implements Serializable {

	private static final long serialVersionUID = 4489799759899877354L;

	public final VideoSettings videoSettings;
	public final File videoFile;

	public MakePreviewRunnableInfo(int id, String name, VideoSettings videoSettings, File videoFile) {
		super(id, name);

		this.videoSettings = videoSettings;
		this.videoFile = videoFile;
	}


	public static MakePreviewRunnableInfo fromByteArray(byte[] byteArray) {
		return fromByteArray(MakePreviewRunnableInfo.class, byteArray);
	}

	@Override
	public String toString() {
		return "MakePreviewRunnableInfo [videoSettings=" + videoSettings + ", videoFile=" + videoFile + "]";
	}

}
