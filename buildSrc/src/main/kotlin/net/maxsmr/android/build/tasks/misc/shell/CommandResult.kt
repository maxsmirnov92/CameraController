package net.maxsmr.android.build.tasks.misc.shell

import java.util.*

const val PROCESS_EXIT_CODE_SUCCESS = 0

const val DEFAULT_TARGET_CODE = PROCESS_EXIT_CODE_SUCCESS

class CommandResult @JvmOverloads constructor(
        val targetExitCode: Int? = DEFAULT_TARGET_CODE,
        // null == not completed
        var exitCode: Int? = null,
        stdOutLines: List<String>? = null,
        stdErrLines: List<String>? = null) {

    val isCompleted: Boolean
        get() = exitCode != null

    val isSuccessful: Boolean
        get() = isCompleted && exitCode == targetExitCode /*&& stdErrLines.isEmpty()*/

    val isFailed: Boolean
        get() = isCompleted && !isSuccessful

    private val stdOutLines: MutableList<String> = if (stdOutLines != null) ArrayList(stdOutLines) else ArrayList()

    private var stdErrLines: MutableList<String> = if (stdErrLines != null) ArrayList(stdErrLines) else ArrayList()

    constructor(from: CommandResult) : this(from.targetExitCode, from.exitCode, from.stdOutLines, from.stdErrLines)

    fun getStdOutLines(): List<String> {
        return ArrayList(stdOutLines)
    }

    fun setStdOutLines(stdOutLines: List<String>?) {
        this.stdOutLines.clear()
        if (stdOutLines != null) {
            this.stdOutLines.addAll(stdOutLines)
        }
    }

    fun getStdErrLines(): List<String> {
        return ArrayList(stdErrLines)
    }

    fun setStdErrLines(stdErrLines: MutableList<String>?) {
        this.stdErrLines.clear()
        if (stdErrLines != null) {
            this.stdErrLines = stdErrLines
        }
    }

    override fun toString(): String {
        return "CommandResult(targetExitCode=$targetExitCode, exitCode=$exitCode, stdOutLines=$stdOutLines, stdErrLines=$stdErrLines)"
    }
}