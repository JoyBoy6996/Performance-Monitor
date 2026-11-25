package com.framescope.monitor.core

/**
 * The diagnostic lines a run captured from the privileged shell.
 *
 * A line that only carries placeholders is not a reading: it says the shell
 * did not answer. Printing such a line next to real values makes a run look
 * complete when it is not, so it is dropped and the card says plainly that
 * nothing was captured.
 *
 * The same rule is used by the app and by the PDF, so both say the same thing
 * about the same run.
 */
object ShellLogs {

    /** True when the line carries no real reading at all. */
    fun isPlaceholder(line: String): Boolean {
        val text = line.trim()
        return text.isEmpty() || text == "--" ||
            text.contains("shell_uid=--") || text.contains("uid=--")
    }

    /**
     * True when the line is the identity of the run and it is real.
     *
     * The identity line names the shell that answered, the platform of the
     * device, the chip sensors and the clusters. It is the first line of the
     * Shell Logs card.
     */
    fun isRealIdentity(line: String): Boolean {
        val text = line.trim()
        return text.startsWith("shell_uid=") && !isPlaceholder(text)
    }

    /** The identity line of the run, or null when the shell never answered. */
    fun identity(logs: String): String? =
        logs.lineSequence().map { it.trim() }.firstOrNull { isRealIdentity(it) }

    /**
     * Every captured line that carries a real reading.
     *
     * The identity line is part of it; a caller that prints the identity on
     * its own takes it out again.
     */
    fun realLines(logs: String): List<String> =
        logs.lineSequence().map { it.trim() }
            .filter { !isPlaceholder(it) }
            .toList()

    /**
     * The lines that belong under the identity line.
     *
     * The identity is printed on its own, so it is left out of the rest.
     */
    fun detailLines(logs: String): List<String> =
        realLines(logs).filterNot { isRealIdentity(it) }

    /**
     * The first line of the log.
     *
     * It is the line that says how the run was measured, and it is printed on
     * its own. A run that has no line at all is an old run that was recorded
     * before the diagnostics were kept.
     */
    fun head(logs: String): String? = realLines(logs).firstOrNull()

    /** Everything under the first line. */
    fun body(logs: String): List<String> = realLines(logs).drop(1)
}
