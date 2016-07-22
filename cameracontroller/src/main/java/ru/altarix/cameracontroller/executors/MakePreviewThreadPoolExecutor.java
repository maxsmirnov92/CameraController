package ru.altarix.cameracontroller.executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import ru.altarix.commonutils.data.FileHelper;
import ru.altarix.tasksutils.AbstractSyncThreadPoolExecutor;
import ru.altarix.tasksutils.taskrunnable.TaskRunnable;
import ru.altarix.cameracontroller.CameraController;
import ru.altarix.cameracontroller.executors.info.MakePreviewRunnableInfo;

public class MakePreviewThreadPoolExecutor extends AbstractSyncThreadPoolExecutor {

    private final static Logger logger = LoggerFactory.getLogger(MakePreviewThreadPoolExecutor.class);

    private final static int KEEP_ALIVE_TIME = 60;

    private final CameraController cameraController = CameraController.getInstance();

    public MakePreviewThreadPoolExecutor(int poolSize, boolean syncQueue, String queueDirPath) {
        super(poolSize, poolSize, KEEP_ALIVE_TIME, TimeUnit.SECONDS, "MakePreviewThread", syncQueue, queueDirPath);
        logger.debug("MakePreviewThreadPoolExecutor(), poolSize=" + poolSize + ", syncQueue=" + syncQueue + ", queueDirPath="
                + queueDirPath);
    }

    @Override
    protected synchronized boolean restoreTaskRunnablesFromFiles() {
        logger.debug("restoreTaskRunnablesFromFiles()");

        if (!FileHelper.testDirNoThrow(queueDirPath)) {
            logger.error("incorrect queue dir path: " + queueDirPath);
            return false;
        }

        File queueDir = new File(queueDirPath);

        List<File> filesList = FileHelper.sortFilesByLastModified(Arrays.asList(queueDir.listFiles()), false, false);

        if (filesList == null || filesList.size() == 0) {
            logger.error("filesList is null or empty");
            return false;
        }

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
                if (execute(taskRunnable)) {
                    restoreCount++;
                } else {
                    logger.error("taskRunnable " + taskRunnable + " was not added to runnable queue, deleting file " + f + "...");
                    if (!f.delete()) {
                        logger.error("can't delete file");
                    }
                }
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
