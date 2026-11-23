package com.framescope.monitor.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The open shell answers many commands.
 *
 * The tests run against a real shell, because the thing being tested is the
 * conversation with one: the marker that ends an answer, the exit code that
 * comes with it, and the two streams that belong to the command.
 */
class ShellSessionTest {

    @Test
    fun returnsTheOutputAndTheExitCodeOfACommand() {
        val session = ShellSession()
        try {
            val result = session.run("printf 'hello'; printf 'world'", 5_000)

            assertEquals(ShellExecutionStatus.SUCCESS, result.status)
            assertEquals(0, result.code)
            assertEquals("helloworld", result.out)
        } finally {
            session.close()
        }
    }

    @Test
    fun keepsTheErrorStreamApartFromTheOutput() {
        val session = ShellSession()
        try {
            val result = session.run("printf 'out'; printf 'err' >&2; exit 7", 5_000)

            assertEquals(ShellExecutionStatus.FAILURE, result.status)
            assertEquals(7, result.code)
            assertEquals("out", result.out)
            assertTrue(result.err.contains("err"))
            assertFalse(result.out.contains("err"))
        } finally {
            session.close()
        }
    }

    @Test
    fun answersManyCommandsInTheSameShell() {
        val session = ShellSession()
        try {
            val first = session.run("printf 'one'", 5_000)
            val second = session.run("printf 'two'", 5_000)
            val third = session.run("printf 'three'", 5_000)

            assertEquals("one", first.out)
            assertEquals("two", second.out)
            assertEquals("three", third.out)
            assertTrue(session.isAlive)
        } finally {
            session.close()
        }
    }

    @Test
    fun outputWithoutANewlineStillEndsAtTheMarker() {
        val session = ShellSession()
        try {
            val result = session.run("printf 'no-newline'", 5_000)

            assertEquals(ShellExecutionStatus.SUCCESS, result.status)
            assertEquals("no-newline", result.out)
        } finally {
            session.close()
        }
    }

    @Test
    fun outputThatLooksLikeTheMarkerIsStillOnlyTheCommand() {
        val session = ShellSession()
        try {
            val result = session.run("printf 'framescope-not-the-marker'", 5_000)

            assertEquals(ShellExecutionStatus.SUCCESS, result.status)
            assertEquals("framescope-not-the-marker", result.out)
        } finally {
            session.close()
        }
    }

    @Test
    fun aCommandThatReadsTheInputGetsAnEmptyFile() {
        val session = ShellSession()
        try {
            // The marker that ends the answer must not be eaten by a command
            // that reads from stdin.
            val result = session.run("cat; printf 'after'", 5_000)

            assertEquals(ShellExecutionStatus.SUCCESS, result.status)
            assertEquals("after", result.out)
        } finally {
            session.close()
        }
    }

    @Test
    fun aCommandThatRunsTooLongEndsTheShell() {
        val session = ShellSession()
        try {
            val result = session.run("sleep 30", 300)

            assertEquals(ShellExecutionStatus.TIMEOUT, result.status)
            assertFalse(session.isAlive)
        } finally {
            session.close()
        }
    }

    @Test
    fun aClosedShellAnswersThatItIsClosed() {
        val session = ShellSession()
        session.close()

        val result = session.run("printf 'x'", 1_000)

        assertEquals(ShellExecutionStatus.FAILURE, result.status)
        assertTrue(result.err.contains("closed"))
    }

    @Test
    fun aBigAnswerIsTruncatedAndStillEnds() {
        val session = ShellSession(maxOutputBytes = 4096)
        try {
            val result = session.run("head -c 20000 /dev/zero | tr '\\0' x", 5_000)

            assertEquals(ShellExecutionStatus.SUCCESS, result.status)
            assertTrue(result.out.contains("truncated"))
            assertTrue(result.out.length < 8000)
        } finally {
            session.close()
        }
    }

    @Test
    fun everyCommandStartsFromTheSameCleanState() {
        val session = ShellSession()
        try {
            // A command that sets a variable must not change what the next
            // command sees: a one-shot command never did, and a measurement
            // that depends on the command before it would be a strange one.
            session.run("framescope_value=41", 5_000)
            val result = session.run("printf '%s' \"${'$'}framescope_value\"", 5_000)

            assertEquals(ShellExecutionStatus.SUCCESS, result.status)
            assertEquals("", result.out)
        } finally {
            session.close()
        }
    }

    @Test
    fun aCommandThatEndsTheShellDoesNotEndTheSession() {
        val session = ShellSession()
        try {
            val first = session.run("exit 3", 5_000)
            val second = session.run("printf 'still-here'", 5_000)

            assertEquals(3, first.code)
            assertEquals(ShellExecutionStatus.SUCCESS, second.status)
            assertEquals("still-here", second.out)
        } finally {
            session.close()
        }
    }
}
