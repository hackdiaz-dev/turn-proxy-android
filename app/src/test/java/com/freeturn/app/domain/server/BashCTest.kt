package com.freeturn.app.domain.server

import org.junit.Assert.assertEquals
import org.junit.Test

class BashCTest {
    @Test
    fun wrapsPlainScriptInSingleQuotes() {
        assertEquals("bash -c 'echo hi'", ServerControl.bashC("echo hi"))
    }

    @Test
    fun keepsGroupingAndOperatorsInsideQuotes() {
        val script = "id -u; command -v sudo >/dev/null 2>&1 && { sudo -n true; }"
        assertEquals("bash -c '" + script + "'", ServerControl.bashC(script))
    }

    @Test
    fun escapesSingleQuote() {
        assertEquals("bash -c 'it'\\''s'", ServerControl.bashC("it's"))
    }
}
