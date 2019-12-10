package net.maxsmr.android.build.tasks

import net.maxsmr.android.build.tasks.misc.checkNotEmpty
import org.gradle.api.tasks.TaskAction

open class SignReleaseAarTask : BaseSignAarTask() {

    private val COMMAND_FORMAT = "jarsigner -keystore %s -storepass %s -keypass %s -signedjar %s -verbose %s %s"

    @TaskAction
    fun sign() {
        checkArgs()

        var newAarPath = ""
        val extIndex = aarPath.lastIndexOf(".aar")
        if (extIndex != -1 && extIndex < aarPath.length - 1) {
            newAarPath = aarPath.replaceRange(extIndex, aarPath.length, "-signed")
            newAarPath += ".aar"
        }

        checkNotEmpty(newAarPath, "New AAR path")

        runScript(
                String.format(
                        COMMAND_FORMAT,
                        getKeystoreFile().absolutePath,
                        keystorePassword,
                        keystorePassword,
                        newAarPath,
                        aarPath,
                        keystoreAlias
                )
        )
    }
}