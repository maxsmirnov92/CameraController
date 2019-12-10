package net.maxsmr.android.build.tasks.misc.shell

import net.maxsmr.android.build.tasks.misc.shell.ShellCallback.StreamType
import java.io.*
import java.util.*
import java.util.concurrent.CountDownLatch
import kotlin.collections.ArrayList

private fun createAndStartProcess(
        commands: List<String>,
        workingDir: String,
        configurator: IProcessBuilderConfigurator?,
        sc: ShellCallback?,
        tc: ThreadsCallback?,
        latch: CountDownLatch?
): Process? {

    val commands = commands.toMutableList()

    for (i in commands.indices) {
        commands[i] = String.format(Locale.US, "%s", commands[i])
    }

    val pb = ProcessBuilder(commands)

    if (workingDir.isNotEmpty()) {
        val workingDirFile = File(workingDir)
        if (workingDirFile.exists() && workingDirFile.isDirectory) {
            pb.directory(workingDirFile)
        } else {
            System.err.println("working directory $workingDir not exists")
        }
    }

    if (sc != null && sc.needToLogCommands()) {
        val cmdLog = StringBuilder()
        for (cmd in commands) {
            cmdLog.append(cmd)
            cmdLog.append(' ')
        }
        sc.shellOut(StreamType.CMD, cmdLog.toString())
    }

    configurator?.configure(pb)

    var process: Process? = null
    var startEx: IOException? = null

    try {
        process = pb.start()
    } catch (e: IOException) {
        startEx = e
    }

    if (process == null) {
        sc?.processStartFailed(startEx)
        return null
    } else {
        sc?.processStarted()
    }

    val outThreadInfo = CmdThreadInfo(commands, workingDir, StreamType.OUT)
    val outThread = StreamConsumeThread(outThreadInfo, process.inputStream, sc, tc, latch)
    outThread.start()
    tc?.onThreadStarted(outThreadInfo, outThread)

    val errThreadInfo = CmdThreadInfo(commands, workingDir, StreamType.ERR)
    val errThread = StreamConsumeThread(errThreadInfo, process.errorStream, sc, tc, latch)
    errThread.start()
    tc?.onThreadStarted(errThreadInfo, errThread)

    return process
}

fun execProcessAsync(
        cmd: String,
        workingDir: String = "",
        configurator: IProcessBuilderConfigurator? = null,
        sc: ShellCallback?,
        tc: ThreadsCallback?
) = execProcessAsync(listOf(cmd), workingDir, configurator, sc, tc)

/**
 * @return true if started successfully, false - otherwise
 */
fun execProcessAsync(
        cmds: List<String>,
        workingDir: String = "",
        configurator: IProcessBuilderConfigurator? = null,
        sc: ShellCallback?,
        tc: ThreadsCallback?
): Boolean {
    println("execProcessAsync(), commands=$cmds, workingDir=$workingDir, configurator=$configurator, sc=$sc, tc=$tc")
    val latch = CountDownLatch(2)
    val process = createAndStartProcess(cmds, workingDir, configurator, sc, tc, latch)
    if (process != null) {
        ProcessWaitThread(process, sc, latch).start()
        return true
    }
    return false
}


fun execProcess(
        cmd: String,
        workingDir: String = "",
        configurator: IProcessBuilderConfigurator? = null,
        targetExitCode: Int?,
        sc: ShellCallback?,
        tc: ThreadsCallback?
) = execProcess(listOf(cmd), workingDir, configurator, targetExitCode, sc, tc)

/**
 * @return result code; -1 if start failed or interrupted
 */
fun execProcess(
        cmds: List<String>,
        workingDir: String = "",
        configurator: IProcessBuilderConfigurator? = null,
        targetExitCode: Int?,
        sc: ShellCallback?,
        tc: ThreadsCallback?
): CommandResult {
    println("execProcess(), commands=$cmds, workingDir=$workingDir, configurator=$configurator, targetExitCode=$targetExitCode, sc=$sc, tc=$tc")

    val stdOutLines = ArrayList<String>()
    val stdErrLines = ArrayList<String>()

    val latch = CountDownLatch(2)

    val wrappedCallback = WrappedShellCallback(sc, stdOutLines, stdErrLines)
    val process = createAndStartProcess(cmds, workingDir, configurator, wrappedCallback, tc, latch)

    if (wrappedCallback.wasStarted && !wrappedCallback.isFinished) {
        try {
            latch.await()
        } catch (e: InterruptedException) {
            System.err.println("an InterruptedException occurred during await(): $e")
        }
    }

    var exitCode = -1
    try {
        exitCode = process?.waitFor() ?: -1
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        System.err.println("an InterruptedException occurred during waitFor(): $e")
    } finally {
        process?.destroy()
        sc?.processComplete(exitCode)
    }

    return CommandResult(targetExitCode, exitCode, stdOutLines, stdErrLines)
}

private class StreamConsumeThread internal constructor(private val threadInfo: CmdThreadInfo, internal val `is`: InputStream, internal val sc: ShellCallback?, internal val tc: ThreadsCallback?, internal val latch: CountDownLatch?) : Thread() {

    init {
        this.name = threadInfo.type.name
    }

    override fun run() {
        try {
            val isr = InputStreamReader(`is`)
            val br = BufferedReader(isr)
            var line = br.readLine()
            while (!isInterrupted && line != null) {
                sc?.shellOut(threadInfo.type, line)
                line = br.readLine()
            }
        } catch (e: IOException) {
            System.err.println("an IOException occurred: $e")
        }

        latch?.countDown()
        tc?.onThreadFinished(threadInfo, this)
    }
}

private class ProcessWaitThread(internal val process: Process, internal val sc: ShellCallback?, internal val latch: CountDownLatch?) : Thread(ProcessWaitThread::class.java.name) {

    private val latchCounts: Long
        get() = latch?.count ?: 0

    override fun run() {

        var exitVal = -1

        if (latch != null && latchCounts > 0) {
            try {
                latch.await()
            } catch (e: InterruptedException) {
                System.err.println("an InterruptedException occurred during await(): $e")
            }
        }

        try {
            exitVal = process.waitFor()
        } catch (e: InterruptedException) {
            currentThread().interrupt()
            System.err.println("an InterruptedException occurred during waitFor(): $e")
        }

        process.destroy()

        sc?.processComplete(exitVal)
    }
}

class CmdThreadInfo(cmds: List<String>?,
                    private val workingDir: String?,
                    val type: StreamType) {

    private val cmds: List<String> = if (cmds != null) ArrayList(cmds) else ArrayList()


    override fun toString(): String {
        return "CmdThreadInfo(workingDir=$workingDir, type=$type, commands=$cmds)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CmdThreadInfo) return false

        if (workingDir != other.workingDir) return false
        if (type != other.type) return false
        if (cmds != other.cmds) return false

        return true
    }

    override fun hashCode(): Int {
        var result = workingDir?.hashCode() ?: 0
        result = 31 * result + type.hashCode()
        result = 31 * result + cmds.hashCode()
        return result
    }
}

interface ShellCallback {

    enum class StreamType private constructor(val value: String) {
        CMD("cmd"), OUT("out"), ERR("err")
    }

    fun needToLogCommands(): Boolean

    fun shellOut(from: StreamType, shellLine: String)

    fun processStarted()

    fun processStartFailed(t: Throwable?)

    fun processComplete(exitValue: Int)
}

interface ThreadsCallback {

    fun onThreadStarted(info: CmdThreadInfo, thread: Thread)

    fun onThreadFinished(info: CmdThreadInfo, thread: Thread)
}

interface IProcessBuilderConfigurator {

    fun configure(builder: ProcessBuilder)
}

private class WrappedShellCallback(
        private val sc: ShellCallback?,
        private val stdOutLines: MutableList<String>,
        private val stdErrLines: MutableList<String>
) : ShellCallback {

    var wasStarted: Boolean = false
    var isFinished: Boolean = false

    override fun needToLogCommands(): Boolean {
        return sc != null && sc.needToLogCommands()
    }

    override fun shellOut(from: StreamType, shellLine: String) {
        when (from) {
            StreamType.OUT -> stdOutLines.add(shellLine)
            StreamType.ERR -> stdErrLines.add(shellLine)
            else -> {
                // do nothing
            }
        }
        sc?.shellOut(from, shellLine)
    }

    override fun processStarted() {
        wasStarted = true
    }

    override fun processStartFailed(t: Throwable?) {
        isFinished = true
        sc?.processStartFailed(t)
    }

    override fun processComplete(exitValue: Int) {
        isFinished = true
        sc?.processComplete(exitValue)
    }
}

