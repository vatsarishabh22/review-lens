package io.github.vatsarishabh22.reviewlens

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Path

/** A command that exited non-zero; [stdout] keeps what it printed (`gh api` prints GitHub's refusal there). */
class CliException(message: String, val stdout: String = "", val stderr: String = "") : RuntimeException(message)

/** The first line of [stderr] without git's `fatal:` / `error:` prefix, e.g. "Could not read from remote repository." */
fun cliReason(stderr: String, program: String): String =
    stderr.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }?.removePrefix("fatal: ")?.removePrefix("error: ")
        ?: "$program failed"

/** Runs [command] in [dir] and returns stdout (trimmed unless [trim] is false); a non-zero exit throws with stderr. */
fun runCli(dir: Path, vararg command: String, trim: Boolean = true): String {
    // CONSOLE parent env = the login-shell PATH, so Homebrew's gh/git resolve even when the IDE starts from Finder.
    val output = ExecUtil.execAndGetOutput(GeneralCommandLine(*command).withWorkingDirectory(dir))
    if (output.exitCode != 0) {
        // the command line goes to the log; people see only the tool's own reason (it lands in balloons)
        logger<CliException>().info("${command.joinToString(" ")} exited ${output.exitCode}: ${output.stderr.trim()}")
        throw CliException(cliReason(output.stderr, command.first()), output.stdout, output.stderr.trim())
    }
    return if (trim) output.stdout.trim() else output.stdout
}
