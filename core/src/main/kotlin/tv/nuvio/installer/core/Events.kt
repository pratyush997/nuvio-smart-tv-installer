// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

enum class LogLevel { INFO, SUCCESS, ERROR, COMMAND, OUTPUT }

/** Coarse install steps, in order, for progress UIs. */
enum class InstallStage(val title: String) {
    PACKAGE("Get package"),
    CONNECT("Connect to TV"),
    SIGN("Sign"),
    UPLOAD("Upload"),
    INSTALL("Install"),
}

/** Receives progress from the installer. Implementations must be thread-safe. */
interface InstallerEvents {
    fun log(level: LogLevel, text: String)
    fun progress(label: String, done: Long, total: Long) {}
    fun stage(stage: InstallStage) {}
    /** Called while waiting for the user to finish the Samsung login in the browser. */
    fun waitingForLogin(waiting: Boolean) {}
}

internal fun InstallerEvents.info(text: String) = log(LogLevel.INFO, text)
internal fun InstallerEvents.success(text: String) = log(LogLevel.SUCCESS, text)
internal fun InstallerEvents.error(text: String) = log(LogLevel.ERROR, text)
internal fun InstallerEvents.command(text: String) = log(LogLevel.COMMAND, text)
internal fun InstallerEvents.output(text: String) = log(LogLevel.OUTPUT, text)

/** An error whose message is meant to be shown to the user as-is. */
open class InstallerException(message: String, cause: Throwable? = null) : Exception(message, cause)
