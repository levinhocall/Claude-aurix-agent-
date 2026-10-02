package com.aurix.agent.core.tools

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

class CalculatorTool : Tool {
    override val name = "CALCULATOR"
    override val description = "Evaluate a math expression: + - * / % ^ parentheses, sqrt() abs() round() floor() ceil(), pi, e."
    override val inputSchema = """{"expression":"(1200*12)/4 + sqrt(16)"}"""
    override val outputSchema = "numeric result"
    override val required = listOf("expression")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 5_000L

    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val v = evalExpression(input.optString("expression"))
        val shown = if (v == floor(v) && abs(v) < 1e15) v.toLong().toString() else v.toString()
        return ToolResult.ok(shown)
    }
}

fun evalExpression(src: String): Double = ExprParser(src).parse()

private class ExprParser(private val s: String) {
    private var i = 0
    private fun bad(msg: String): Nothing = throw ToolException(ToolErrorType.INVALID_INPUT, msg)
    private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun match(c: Char): Boolean { skip(); return if (i < s.length && s[i] == c) { i++; true } else false }

    fun parse(): Double {
        val v = expr()
        skip()
        if (i < s.length) bad("Unexpected '${s[i]}' at position $i")
        return v
    }

    private fun expr(): Double {
        var v = term()
        while (true) {
            v = if (match('+')) v + term() else if (match('-')) v - term() else return v
        }
    }

    private fun term(): Double {
        var v = power()
        while (true) {
            if (match('*')) v *= power()
            else if (match('/')) { val d = power(); if (d == 0.0) bad("Division by zero"); v /= d }
            else if (match('%')) { val d = power(); if (d == 0.0) bad("Division by zero"); v %= d }
            else return v
        }
    }

    private fun power(): Double {
        val b = unary()
        return if (match('^')) b.pow(power()) else b
    }

    private fun unary(): Double = if (match('-')) -unary() else if (match('+')) unary() else primary()

    private fun primary(): Double {
        skip()
        if (i >= s.length) bad("Unexpected end of expression")
        if (match('(')) { val v = expr(); if (!match(')')) bad("Missing ')'"); return v }
        val c = s[i]
        if (c.isDigit() || c == '.') {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(st, i).toDoubleOrNull() ?: bad("Bad number")
        }
        if (c.isLetter()) {
            val st = i
            while (i < s.length && s[i].isLetter()) i++
            val id = s.substring(st, i).lowercase()
            if (id == "pi") return Math.PI
            if (id == "e") return Math.E
            if (!match('(')) bad("Unknown identifier '$id'")
            val a = expr()
            if (!match(')')) bad("Missing ')'")
            return when (id) {
                "sqrt" -> { if (a < 0) bad("sqrt of negative"); sqrt(a) }
                "abs" -> abs(a)
                "round" -> round(a)
                "floor" -> floor(a)
                "ceil" -> ceil(a)
                else -> bad("Unknown function '$id'")
            }
        }
        bad("Unexpected '$c' at position $i")
    }
}
