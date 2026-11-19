package com.sgh1101.watchinstall.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecResultTest {
    private val mark = ExecResult.END_MARK

    @Test
    fun parsesOutputAndExitCode() {
        val r = ExecResult.parse("Galaxy Watch7\n${mark}0\n")
        assertEquals("Galaxy Watch7", r.output)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun parsesNonZeroExitCode() {
        val r = ExecResult.parse("Failure [INSTALL_FAILED_OLDER_SDK]\n${mark}1\n")
        assertEquals("Failure [INSTALL_FAILED_OLDER_SDK]", r.output)
        assertEquals(1, r.exitCode)
    }

    @Test
    fun detectsEndMarkOnlyAtEnd() {
        assertFalse(ExecResult.hasEndMark("partial output"))
        assertFalse(ExecResult.hasEndMark("${mark}0 and more text"))
        assertTrue(ExecResult.hasEndMark("done\n${mark}255\n"))
    }

    @Test
    fun missingMarkKeepsOutput() {
        val r = ExecResult.parse("cut off")
        assertEquals("cut off", r.output)
        assertNull(r.exitCode)
    }
}
