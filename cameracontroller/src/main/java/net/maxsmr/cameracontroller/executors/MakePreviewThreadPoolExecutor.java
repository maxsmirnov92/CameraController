package net.maxsmr.cameracontroller.executors;

import net.maxsmr.cameracontroller.logger.base.Logger;
import net.maxsmr.commonutils.data.FileHelper;
import net.maxsmr.tasksutils.taskexecutor.AbsTaskRunnableExecutor;


import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import net.maxsmr.cameracontroller.camera.CameraController;
import net.maxsmr.cameracontroller.executors.info.MakePreviewRunnableInfo;
import net.maxsmr.tasksutils.taskexecutor.TaskRunnable;

// TODO при инициализации передавать AbstractRunnableStorage
public class MakePreviewThreadPoolExecutor extends AbsTaskRunnableExecutor {

    private final static int KEEP_ALIVE_TIME = 60;

    private final Logger logger;

    private final CameraController cameraController;

    public MakePreviewThreadPoolExecutor(CameraController cameraController, int poolSize, boolean syncQueue, String queueDirPath, Logger logger) {
        super(poolSize, poolSize, KEEP_ALIVE_TIME, TimeUnit.SECONDS, "MakePreviewThread", syncQueue, queueDirPath);
        this.logger = logger;
        this.cameraController = cameraController;
        this.logger.debug("MakePreviewThreadPoolExecutor(), poolSize=" + poolSize + ", syncQueue=" + syncQueue + ", queueDirPath="
                + queueDirPath);
    }

    @Override
    protected synchronized boolean restoreTaskRunnablesFromFiles() {
        logger.debug("restoreTaskRunnablesFromFiles()");

        if (!FileHelper.checkDirNoThrow(queueDirPath)) {
            logger.error("incorrect queue dir path: " + queueDirPath);
            return false;
        }

        File queueDir = new File(queueDirPath);

        Collection<File> filesList = FileHelper.sortFilesByLastModified(Arrays.asList(queueDir.listFiles()), false, false);

        int restoreCount = 0;

        final long startTime = System.currentTimeMillis();

        logger.info("restoring TaskRunnable objects by files (" + filesList.size() + ")...");

        for (File f : filesList) {

            if (Thread.currentThread().isInterrupted()) {
                return false;
            }

            if (f == null || f.isDirectory()) {
                continue;
            }

            if (f.isFile() && f.length() > 0 && FileHelper.getFileExtension(f.getName()).equalsIgnoreCase(FILE_EXT_DAT)) {

                TaskRunnable taskRunnable = null;

                try {

                    taskRunnable = cameraController.new MakePreviewRunnable(MakePreviewRunnableInfo.fromByteArray(FileHelper.readBytesFromFile(f)));

                } catch (IllegalArgumentException e) {
                    logger.error("an IllegalArgumentException occured", e);

                } catch (ClassCastException e) {
                    logger.error("a ClassCastException occured", e);
                }

                logger.debug(" _ taskRunnable from byte array: " + taskRunnable);
                execute(taskRunnable);
                restoreCount++;

            } else {
                logger.error("incorrect TaskRunnable file: " + f + ", deleting...");
                if (!f.delete()) {
                    logger.error("can't delete file: " + f);
                }
            }

        }

        logger.info("restoring complete, time: " + (System.currentTimeMillis() - startTime) + " ms");
        logger.info("restored TaskRunnable objects count: " + restoreCount + ", task count: " + getTaskCount());
        return true;
    }

}
