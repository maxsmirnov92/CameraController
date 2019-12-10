package net.maxsmr.android.build.tasks

import net.maxsmr.android.build.tasks.misc.checkFilePathValid
import net.maxsmr.android.build.tasks.misc.checkFileValid
import net.maxsmr.android.build.tasks.misc.checkNotEmpty
import net.maxsmr.android.build.tasks.misc.shell.ShellWrapper
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import java.io.File

abstract class BaseSignAarTask : DefaultTask() {

    @Input
    var keystoreAlias: String = ""

    @Input
    var keystorePassword: String = ""

    @Input
    var aarPath: String = ""

    @Input
    var keystoreFileName: String = "release.keystore"
    
    @Input
    var enableLogging: Boolean = false

    open fun checkArgs() {
        checkNotEmpty(keystoreAlias, "Keystore alias")
        checkNotEmpty(keystorePassword, "Keystore password")
        checkNotEmpty(keystoreFileName, "Keystore file name")
        checkFilePathValid(aarPath, "AAR")
    }

    protected fun runScript(script: String) {
        println("Executing script: $script")
        with(ShellWrapper(enableLogging = enableLogging)) {
            var jreHome = System.getenv("JRE_HOME") ?: ""
            if (jreHome.isNotEmpty()) {
                if (!jreHome.endsWith("/") && !jreHome.endsWith("\\")) {
                    jreHome += File.separator
                }
                jreHome += "bin"
            }
            this.workingDir = jreHome
            val commands = script.split(" ")
            println("commands: $commands")
            executeCommand(commands.toMutableList(), false)
        }
    }

    protected fun getKeystoreFile(): File {
        var keystoreFile: File? = null
        val homeDir = System.getenv("ANDROID_HOME") ?: ""
        if (homeDir.isNotEmpty()) {
            keystoreFile = File(homeDir, keystoreFileName)
        }
        checkFileValid(keystoreFile, "Keystore file")
        return keystoreFile!!
    }
}