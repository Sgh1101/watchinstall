package com.sgh1101.watchinstall.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallResultTest {

    @Test
    fun success() {
        assertEquals(InstallResult.Success, InstallResult.parse("Success\n", 0))
    }

    @Test
    fun successWithStreamingPreamble() {
        val out = "Performing Streamed Install\nSuccess\n"
        assertEquals(InstallResult.Success, InstallResult.parse(out, 0))
    }

    @Test
    fun failureWithMessage() {
        val out = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.foo signatures do not match previously installed version; ignoring!]"
        val r = InstallResult.parse(out, 1) as InstallResult.Failure
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE", r.code)
        assertTrue(r.message!!.startsWith("Package com.foo"))
        assertTrue(r.explanation.contains("서명"))
    }

    @Test
    fun failureWithoutMessage() {
        val r = InstallResult.parse("Failure [INSTALL_FAILED_OLDER_SDK]\n", 1) as InstallResult.Failure
        assertEquals("INSTALL_FAILED_OLDER_SDK", r.code)
        assertNull(r.message)
        assertTrue(r.explanation.contains("minSdk"))
    }

    @Test
    fun successTextButNonZeroExitIsNotSuccess() {
        val r = InstallResult.parse("Success\n", 255)
        assertTrue(r is InstallResult.Failure)
    }

    @Test
    fun unknownError() {
        val out = "Exception occurred while executing 'install':\njava.lang.IllegalArgumentException: Size must be positive"
        val r = InstallResult.parse(out, 255) as InstallResult.Failure
        assertEquals("UNKNOWN", r.code)
        assertTrue(r.message!!.startsWith("Exception occurred"))
    }

    @Test
    fun unknownCodeStillExplained() {
        val r = InstallResult.parse("Failure [INSTALL_FAILED_SOMETHING_NEW: x]", 1) as InstallResult.Failure
        assertTrue(r.explanation.contains("INSTALL_FAILED_SOMETHING_NEW"))
    }
}
