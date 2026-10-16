package dev.uednd.hydros

import dev.uednd.hydros.ui.state.*
import org.junit.Assert.*
import org.junit.Test

class FormValidationTest {
    @Test fun phoneInputRejectsIncompleteAndInvalidValues() {
        listOf("", "138", "1380000000", "12800000001", "1380000000x").forEach { assertNotNull(phoneError(it)) }
        assertNull(phoneError("13800000001"))
    }
    @Test fun secretsRequireValidLengthAndCodeFormat() {
        assertNotNull(passwordError(""))
        assertNotNull(passwordError("12345"))
        assertNotNull(passwordError("      "))
        assertNotNull(passwordError("a".repeat(33)))
        assertNull(passwordError("test123"))
        assertNotNull(codeError("12345"))
        assertNotNull(codeError("12345x"))
        assertNull(codeError("654321"))
        assertNull(codeError("123456"))
    }
}
