package net.maxsmr.cameracontroller.camera;

import net.maxsmr.tasksutils.taskexecutor.RunnableInfo;

import java.io.File;
import java.io.Serializable;

import net.maxsmr.cameracontroller.camera.settings.video.record.VideoSettings;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;


public class MakePreviewRunnableInfo extends RunnableInfo implements Serializable {

	private static final long serialVersionUID = 4489799759899877354L;

	@Nullable
	public final VideoSettings videoSettings;

	@NotNull
	public final File videoFile;

	public MakePreviewRunnableInfo(int id, String name, @Nullable VideoSettings videoSettings, @NotNull File videoFile) {
		super(id, name);

		this.videoSettings = videoSettings;
		this.videoFile = videoFile;
	}


	public static MakePreviewRunnableInfo fromByteArray(byte[] byteArray) {
		return fromByteArray(MakePreviewRunnableInfo.class, byteArray);
	}

	@NotNull
	@Override
	public String toString() {
		return "MakePreviewRunnableInfo [videoSettings=" + videoSettings + ", videoFile=" + videoFile + "]";
	}

}
