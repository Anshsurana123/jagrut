// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class CalculatorEngineTest {

    @Test
    fun testBasicAdditionAndSubtraction() {
        assertEquals("8", CalculatorEngine.evaluate("5 + 3"))
        assertEquals("2", CalculatorEngine.evaluate("5 - 3"))
    }

    @Test
    fun testMultiplicationAndDivision() {
        assertEquals("15", CalculatorEngine.evaluate("5 * 3"))
        assertEquals("2.50", CalculatorEngine.evaluate("5 / 2"))
    }

    @Test
    fun testExponentiation() {
        assertEquals("8", CalculatorEngine.evaluate("2 ^ 3"))
    }

    @Test
    fun testUnaryMinusAndNegativeNumbers() {
        assertEquals("-5", CalculatorEngine.evaluate("-5"))
        assertEquals("-15", CalculatorEngine.evaluate("5 * -3"))
        assertEquals("-8", CalculatorEngine.evaluate("-2 * 4"))
    }

    @Test
    fun testUnaryPlus() {
        assertEquals("5", CalculatorEngine.evaluate("+5"))
        assertEquals("8", CalculatorEngine.evaluate("5 + +3"))
    }

    @Test
    fun testSquareRoot() {
        assertEquals("12", CalculatorEngine.evaluate("sqrt(144)"))
        assertEquals("15", CalculatorEngine.evaluate("3 + sqrt(144)"))
    }

    @Test
    fun testDivisionByZero() {
        assertEquals("Error", CalculatorEngine.evaluate("5 / 0"))
    }
}
