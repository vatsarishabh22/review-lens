package io.github.vatsarishabh22.reviewlens

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import java.nio.file.Path

class CliException(message: String) : RuntimeException(message)

/** Runs [command] in [dir] and returns stdout (trimmed unless [trim] is false); a non-zero exit throws with stderr. */
fun runCli(dir: Path, vararg command: String, trim: Boolean = true): String {
    // CONSOLE parent env = the login-shell PATH, so Homebrew's gh/git resolve even when the IDE starts from Finder.
    val output = ExecUtil.execAndGetOutput(GeneralCommandLine(*command).withWorkingDirectory(dir))
    if (output.exitCode != 0) throw CliException("${command.joinToString(" ")}: ${output.stderr.trim()}")
    return if (trim) output.stdout.trim() else output.stdout
}
