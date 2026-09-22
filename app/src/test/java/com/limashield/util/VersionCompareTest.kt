package com.limashield.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {

    @Test
    fun `newer versions are detected`() {
        assertTrue(UpdateChecker.isNewer("0.9.4", "0.9.3"))
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.3"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"))
        assertTrue(UpdateChecker.isNewer("0.9.3.1", "0.9.3"))
    }

    @Test
    fun `same or older versions are not`() {
        assertFalse(UpdateChecker.isNewer("0.9.3", "0.9.3"))
        assertFalse(UpdateChecker.isNewer("0.9.2", "0.9.3"))
        assertFalse(UpdateChecker.isNewer("0.9", "0.9.0"))
        assertFalse(UpdateChecker.isNewer("garbage", "0.9.3"))
    }
}
