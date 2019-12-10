package net.maxsmr.android.build.tasks

import org.gradle.api.tasks.TaskAction

open class CheckSignedAarTask : BaseSignAarTask() {

    private val COMMAND_FORMAT = "jarsigner -keystore %s -storepass %s -verify -verbose -certs %s %s"

    @TaskAction
    fun check() {
        checkArgs()
        runScript(
                String.format(
                        COMMAND_FORMAT,
                        getKeystoreFile().absolutePath,
                        keystorePassword,
                        aarPath,
                        keystoreAlias
                )
        )
    }
}