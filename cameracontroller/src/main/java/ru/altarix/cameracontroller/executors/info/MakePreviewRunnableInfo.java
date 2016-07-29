package ru.altarix.cameracontroller.executors.info;

import net.maxsmr.tasksutils.taskrunnable.RunnableInfo;

import java.io.File;
import java.io.Serializable;

import ru.altarix.cameracontroller.settings.video.record.VideoSettings;


public class MakePreviewRunnableInfo extends RunnableInfo implements Serializable {

	private static final long serialVersionUID = 4489799759899877354L;

	public final VideoSettings videoSettings;
	public final File videoFile;

	public MakePreviewRunnableInfo(String name, VideoSettings videoSettings, File videoFile) {
		super((int) serialVersionUID, name);

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
