// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.logic

import android.util.Log
import java.util.Stack
import kotlin.math.pow
import kotlin.math.sqrt

object CalculatorEngine {
    private const val TAG = "CalculatorEngine"

    fun evaluate(expression: String): String {
        return try {
            val rpn = shuntingYard(expression)
            val result = evaluateRPN(rpn)
            formatResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "Calculation failed: ${e.message}")
            "Error"
        }
    }

    private fun formatResult(value: Double): String {
        return if (value % 1 == 0.0) {
            value.toLong().toString()
        } else {
            String.format("%.2f", value)
        }
    }

    private fun shuntingYard(expression: String): List<String> {
        val outputQueue = mutableListOf<String>()
        val operatorStack = Stack<String>()
        
        // Tokenize: numbers, operators, functions, parentheses
        // Regex splits by operators but keeps them, and handles decimal numbers
        val tokens = expression.replace(" ", "")
            .split(Regex("(?<=[-+*/%^()])|(?=[-+*/%^()])|(?<=sqrt)|(?=sqrt)"))
            .filter { it.isNotEmpty() }

        for (i in tokens.indices) {
            val token = tokens[i]
            when {
                token.matches(Regex("-?\\d+(\\.\\d+)?")) -> outputQueue.add(token) // Number
                token == "sqrt" -> operatorStack.push(token)
                token == "(" -> operatorStack.push(token)
                token == ")" -> {
                    while (operatorStack.isNotEmpty() && operatorStack.peek() != "(") {
                        outputQueue.add(operatorStack.pop())
                    }
                    if (operatorStack.isNotEmpty() && operatorStack.peek() == "(") {
                        operatorStack.pop() // Pop '('
                    }
                    if (operatorStack.isNotEmpty() && operatorStack.peek() == "sqrt") {
                        outputQueue.add(operatorStack.pop()) // Pop function
                    }
                }
                isOperator(token) -> {
                    val op = if (token == "-" && isUnary(i, tokens)) {
                        "u-"
                    } else if (token == "+" && isUnary(i, tokens)) {
                        "u+"
                    } else {
                        token
                    }

                    if (op == "u-" || op == "u+") {
                        while (operatorStack.isNotEmpty() && 
                               isOperatorOrUnary(operatorStack.peek()) && 
                               precedence(operatorStack.peek()) > precedence(op)) {
                            outputQueue.add(operatorStack.pop())
                        }
                    } else {
                        while (operatorStack.isNotEmpty() && 
                               isOperatorOrUnary(operatorStack.peek()) && 
                               precedence(operatorStack.peek()) >= precedence(op)) {
                            outputQueue.add(operatorStack.pop())
                        }
                    }
                    operatorStack.push(op)
                }
            }
        }
        
        while (operatorStack.isNotEmpty()) {
            outputQueue.add(operatorStack.pop())
        }
        
        return outputQueue
    }

    private fun isUnary(index: Int, tokens: List<String>): Boolean {
        if (index == 0) return true
        val prev = tokens[index - 1]
        return prev == "(" || prev == "sqrt" || isOperator(prev)
    }

    private fun isOperatorOrUnary(token: String): Boolean {
        return isOperator(token) || token == "u-" || token == "u+"
    }

    private fun evaluateRPN(tokens: List<String>): Double {
        val stack = Stack<Double>()
        
        for (token in tokens) {
            when {
                token.matches(Regex("-?\\d+(\\.\\d+)?")) -> stack.push(token.toDouble())
                token == "sqrt" -> {
                    if (stack.isEmpty()) throw IllegalArgumentException("Missing operand for sqrt")
                    val a = stack.pop()
                    stack.push(sqrt(a))
                }
                token == "u-" -> {
                    if (stack.isEmpty()) throw IllegalArgumentException("Missing operand for unary minus")
                    val a = stack.pop()
                    stack.push(-a)
                }
                token == "u+" -> {
                    if (stack.isEmpty()) throw IllegalArgumentException("Missing operand for unary plus")
                    // Unary plus is a no-op
                }
                isOperator(token) -> {
                    if (stack.size < 2) throw IllegalArgumentException("Missing operand for operator $token")
                    val b = stack.pop()
                    val a = stack.pop()
                    val result = when (token) {
                        "+" -> a + b
                        "-" -> a - b
                        "*" -> a * b
                        "/" -> if (b != 0.0) a / b else throw ArithmeticException("Division by zero")
                        "%" -> a * (b / 100.0)
                        "^" -> a.pow(b)
                        else -> 0.0
                    }
                    stack.push(result)
                }
            }
        }
        
        if (stack.isEmpty()) throw IllegalArgumentException("Empty evaluation stack")
        return stack.pop()
    }

    private fun isOperator(token: String): Boolean {
        return token == "+" || token == "-" || token == "*" || token == "/" || token == "%" || token == "^"
    }

    private fun precedence(op: String): Int {
        return when (op) {
            "u-", "u+" -> 4
            "%", "^" -> 3
            "*", "/" -> 2
            "+", "-" -> 1
            else -> 0
        }
    }
}
