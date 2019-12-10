package net.maxsmr.android.build.tasks.misc

import java.io.File

fun checkNotEmpty(str: String?, argName: String) {
    require(!str.isNullOrEmpty()) { "Argument $argName is null or empty" }
}

fun checkFilePathValid(path: String?, argName: String) {
    require(!path.isNullOrEmpty()) { "Path $argName is null or empty" }
    checkFileValid(File(path), argName)
}

fun checkFileValid(file: File?, argName: String) {
    require(!(file == null || !file.exists() || !file.isFile || file.length() <= 0)) { "File $file for $argName is not valid" }
}