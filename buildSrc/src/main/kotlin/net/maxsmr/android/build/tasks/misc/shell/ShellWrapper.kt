package net.maxsmr.android.build.tasks.misc.shell

import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayList

class ShellWrapper(
        var addToCommandsMap: Boolean = true,

        var targetCode: Int = DEFAULT_TARGET_CODE,

        var workingDir: String = "",

        var configurator: IProcessBuilderConfigurator? = null,

        var enableLogging: Boolean = true
) {

    private val commandId = AtomicInteger(1)

    private val commandsMap = mutableMapOf<Int, CommandInfo?>()

    var isDisposed = false
        private set

    fun getCommandsMap(): Map<Int, CommandInfo> {

        check(!isDisposed) { ShellWrapper::class.java.simpleName + " is disposed" }

        val result = mutableMapOf<Int, CommandInfo>()
        synchronized(commandsMap) {
            for ((key, info) in commandsMap) {
                if (info != null) {
                    result[key] = CommandInfo(info)
                }
            }
            return result
        }
    }

    private fun clearCommandsMap() {

        check(!isDisposed) { ShellWrapper::class.java.simpleName + " is disposed" }

        synchronized(commandsMap) {
            for (v in commandsMap.values) {
                if (v != null) {
                    v.result = null
                    v.startedThreads = LinkedHashMap()
                }
            }
            commandsMap.clear()
        }
    }

    fun dispose() {
        check(!isDisposed) { ShellWrapper::class.java.simpleName + " is already disposed" }
        clearCommandsMap()
        isDisposed = true
    }

    fun executeCommand(command: String, useSU: Boolean): CommandResult =
            executeCommand(mutableListOf(command), useSU)

    fun executeCommand(commands: List<String>, useSU: Boolean): CommandResult {
        log("Execute commands: \"$commands\", useSU: $useSU", false)

        check(!isDisposed) { ShellWrapper::class.java.simpleName + " is disposed" }

        require(commands.isNotEmpty()) { "Nothing to execute" }

        val commands = commands.toMutableList()

        if (useSU) {
            commands.add(0, "su")
            commands.add(1, "-c")
        }

        val commandId = this.commandId.getAndIncrement()
        val commandInfo = CommandInfo(commands)

        if (addToCommandsMap) {
            synchronized(commandsMap) {
                commandsMap.put(commandId, commandInfo)
            }
        }

        val result = execProcess(commands, workingDir, configurator, targetCode, object : ShellCallback {

            override fun needToLogCommands(): Boolean {
                return true
            }

            override fun shellOut(from: ShellCallback.StreamType, shellLine: String) {
                log("Output $from: $shellLine", false)
            }

            override fun processStarted() {
                log("Command \"" + commandInfo.commandsToRun + "\" started", false)
            }

            override fun processStartFailed(t: Throwable?) {
                log("Command \"" + commandInfo.commandsToRun + "\" start failed: $t", true)
//                commandInfo.setResult(new CommandResult(targetCode, -1, null, null));
            }

            override fun processComplete(exitValue: Int) {

            }
        }, object : ThreadsCallback {

            override fun onThreadStarted(info: CmdThreadInfo, thread: Thread) {
                synchronized(commandInfo) {
                    commandInfo.startedThreads.put(info, thread)
                }
            }

            override fun onThreadFinished(info: CmdThreadInfo, thread: Thread) {
                synchronized(commandInfo) {
                    commandInfo.startedThreads.remove(info)
                }
            }
        })

        synchronized(commandInfo) {
            commandInfo.result = result
            // synchronize in case of threads still not finished (otherwise - ConcurrentModificationException)
            log("Command completed: $commandInfo", false)
        }

        return result
    }

    fun log(msg: String, isError: Boolean) {
        if (enableLogging) {
            if (!isError) {
                println(msg)
            } else {
                System.err.println(msg)
            }
        }
    }

    class CommandInfo {

        val commandsToRun: List<String>

        var startedThreads: MutableMap<CmdThreadInfo, Thread>

        var result: CommandResult?

        val isCompleted: Boolean
            get() = with(result) {
                this != null && this.isCompleted
            }

        val isSuccessful: Boolean
            get() = with(result) {
                this != null && this.isSuccessful
            }

        constructor(commandsToRun: List<String>?) {
            this.commandsToRun = if (commandsToRun != null) ArrayList(commandsToRun) else ArrayList()
            this.startedThreads = mutableMapOf()
            this.result = null
        }

        constructor(info: CommandInfo) {
            this.commandsToRun = info.commandsToRun
            this.startedThreads = info.startedThreads
            this.result = info.result
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CommandInfo) return false

            if (commandsToRun != other.commandsToRun) return false
            if (startedThreads != other.startedThreads) return false
            if (result != other.result) return false

            return true
        }

        override fun hashCode(): Int {
            var result1 = commandsToRun.hashCode()
            result1 = 31 * result1 + startedThreads.hashCode()
            result1 = 31 * result1 + (result?.hashCode() ?: 0)
            return result1
        }

        override fun toString(): String {
            return "CommandInfo(commandsToRun=$commandsToRun, startedThreads=$startedThreads, result=$result)"
        }
    }

}