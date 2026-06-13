// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionExecutorTest {

    @Test
    fun testFormatPhoneNumber_DigitsOnly() {
        val input = "9876543210"
        val expected = "9876543210"
        assertEquals(expected, ActionExecutor.formatPhoneNumber(input))
    }

    @Test
    fun testFormatPhoneNumber_WithDashesAndSpaces() {
        val input = "(555) 123-4567"
        val expected = "5551234567"
        assertEquals(expected, ActionExecutor.formatPhoneNumber(input))
    }

    @Test
    fun testFormatPhoneNumber_WithPlusPrefix() {
        val input = "+91 98765 43210"
        val expected = "919876543210" // Removes +
        assertEquals(expected, ActionExecutor.formatPhoneNumber(input))
    }

    @Test
    fun testFormatTelegramPhone_StandardIndianNumber() {
        val input = "9876543210"
        val expected = "919876543210" // Prepends 91 country code fallback
        assertEquals(expected, ActionExecutor.formatTelegramPhone(input))
    }

    @Test
    fun testFormatTelegramPhone_IndianNumberWithZero() {
        val input = "09876543210"
        val expected = "919876543210" // Removes leading 0 and prepends 91 country code fallback
        assertEquals(expected, ActionExecutor.formatTelegramPhone(input))
    }

    @Test
    fun testFormatTelegramPhone_WithCountryCode() {
        val input = "+91 98765 43210"
        val expected = "919876543210" // Keeps country code and removes formatting
        assertEquals(expected, ActionExecutor.formatTelegramPhone(input))
    }
}
