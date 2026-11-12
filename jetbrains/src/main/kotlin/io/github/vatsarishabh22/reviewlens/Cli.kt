package io.github.vatsarishabh22.reviewlens

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Path
import java.util.concurrent.Executors

/** A command that exited non-zero; [stdout] keeps what it printed (`gh api` prints GitHub's refusal there). */
/** A command that failed; [timedOut] when it was stopped unanswered, so what it did on the other side is unknown. */
class CliException(message: String, val stdout: String = "", val stderr: String = "", val timedOut: Boolean = false) : RuntimeException(message)

/** The first line of [stderr] without git's `fatal:` / `error:` prefix, e.g. "Could not read from remote repository." */
fun cliReason(stderr: String, program: String): String =
    stderr.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }?.removePrefix("fatal: ")?.removePrefix("error: ")
        ?: "$program failed"

/**
 * Runs [command] in [dir] and returns stdout (trimmed unless [trim] is false); a non-zero exit throws with stderr. With
 * [timeoutMs], a command still running then is stopped and throws.
 */
fun runCli(dir: Path, vararg command: String, trim: Boolean = true, timeoutMs: Int? = null): String {
    // CONSOLE parent env = the login-shell PATH, so Homebrew's gh/git resolve even when the IDE starts from Finder.
    val line = GeneralCommandLine(*command).withWorkingDirectory(dir)
    val output = if (timeoutMs == null) ExecUtil.execAndGetOutput(line) else ExecUtil.execAndGetOutput(line, timeoutMs)
    // the reason rides in stderr too: callers that reword a failure keep a tool's own words from there
    if (output.isTimeout) "${command.first()} didn't answer in ${timeoutMs!! / 1000} s".let {
        logger<CliException>().info("${command.joinToString(" ")} stopped after $timeoutMs ms")
        throw CliException(it, stderr = it, timedOut = true)
    }
    if (output.exitCode != 0) {
        // the command line goes to the log; people see only the tool's own reason (it lands in balloons)
        logger<CliException>().info("${command.joinToString(" ")} exited ${output.exitCode}: ${output.stderr.trim()}")
        throw CliException(cliReason(output.stderr, command.first()), output.stdout, output.stderr.trim())
    }
    return if (trim) output.stdout.trim() else output.stdout
}

/** Asks [ask] about every key side by side, a thread each (at most 16), so the slowest host call sets the wait; a key it can't answer (null, or a throw) is left out. */
fun <K, V : Any> askAll(keys: Collection<K>, ask: (K) -> V?): Map<K, V> {
    if (keys.isEmpty()) return emptyMap()
    val pool = Executors.newFixedThreadPool(keys.size.coerceAtMost(16))
    try {
        return keys.map { key -> key to pool.submit<V?> { runCatching { ask(key) }.getOrNull() } }.mapNotNull { (key, answer) -> answer.get()?.let { key to it } }.toMap()
    } finally {
        pool.shutdown()
    }
}
