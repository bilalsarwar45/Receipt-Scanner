package com.receiptscan.scanner.math.engine

/** Typed expression tree. Recognised text is parsed into this; it is never evaluated as code. */
sealed interface Expr {
    data class Num(val value: Rational, val text: String) : Expr
    data class Var(val name: Char) : Expr
    data class Neg(val e: Expr) : Expr
    data class Bin(val op: Char, val l: Expr, val r: Expr) : Expr
    data class Sqrt(val e: Expr) : Expr
    data class Percent(val e: Expr) : Expr
}

/** "expr" or "left = right". */
data class Statement(val left: Expr, val right: Expr?)

/**
 * Strict tokenizer and recursive-descent parser for: numbers (decimal), one variable letter,
 * + − × ÷ / * ^, √ / sqrt(), %, parentheses and implicit multiplication (2x, 3(x+1)).
 * Anything else is a [MathSyntaxException] with the position, so the person can fix "We read".
 */
class MathParser(input: String) {
    private val src = normalize(input)
    private var pos = 0

    companion object {
        fun normalize(s: String): String = s
            .replace('×', '*').replace('·', '*').replace('÷', '/').replace('−', '-').replace('–', '-')
            .replace("²", "^2").replace("³", "^3").replace("√", "sqrt")
            .replace(Regex("(?i)\\bof\\b"), "*")
            .replace(Regex("\\s+"), "")
            .let { it.replace(Regex("(?<=\\d)[xX](?=\\d)"), "*") } // "3x4" written with x as times between digits

        fun parse(input: String): Statement = MathParser(input).statement()
    }

    fun statement(): Statement {
        if (src.isEmpty()) throw MathSyntaxException("Nothing to solve", 0)
        val left = expr()
        var right: Expr? = null
        if (peek() == '=') {
            pos++
            right = expr()
        }
        if (pos < src.length) throw MathSyntaxException("Unexpected '${src[pos]}'", pos)
        return Statement(left, right)
    }

    private fun peek(): Char? = src.getOrNull(pos)

    private fun expr(): Expr {
        var e = term()
        while (peek() == '+' || peek() == '-') {
            val op = src[pos++]
            e = Expr.Bin(op, e, term())
        }
        return e
    }

    private fun term(): Expr {
        var e = unary()
        while (true) {
            val c = peek()
            e = when {
                c == '*' || c == '/' -> { pos++; Expr.Bin(c, e, unary()) }
                // Implicit multiplication: 2x, 2(…), )(, x(…), 2sqrt(…)
                c != null && (c.isLetter() || c == '(') -> Expr.Bin('*', e, unary())
                else -> return e
            }
        }
    }

    private fun unary(): Expr = when (peek()) {
        '-' -> { pos++; Expr.Neg(unary()) }
        '+' -> { pos++; unary() }
        else -> power()
    }

    private fun power(): Expr {
        val base = postfix()
        if (peek() == '^') {
            pos++
            return Expr.Bin('^', base, unary())
        }
        return base
    }

    private fun postfix(): Expr {
        var e = primary()
        while (peek() == '%') { pos++; e = Expr.Percent(e) }
        return e
    }

    private fun primary(): Expr {
        val c = peek() ?: throw MathSyntaxException("Expression ends too early", pos)
        return when {
            c.isDigit() || c == '.' -> number()
            c == '(' -> {
                pos++
                val e = expr()
                if (peek() != ')') throw MathSyntaxException("Missing ')'", pos)
                pos++
                e
            }
            src.startsWith("sqrt", pos) -> {
                pos += 4
                if (peek() == '(') primary().let(Expr::Sqrt) else Expr.Sqrt(if (peek() == '-') unary() else postfix())
            }
            c.isLetter() -> {
                pos++
                if (peek()?.isLetter() == true && !src.startsWith("sqrt", pos)) throw MathSyntaxException("Only single-letter variables are supported", pos - 1)
                Expr.Var(c.lowercaseChar())
            }
            else -> throw MathSyntaxException("Unexpected '$c'", pos)
        }
    }

    private fun number(): Expr {
        val start = pos
        while (peek()?.let { it.isDigit() || it == '.' || it == ',' } == true) pos++
        val text = src.substring(start, pos).replace(",", "")
        if (text.count { it == '.' } > 1 || text == ".") throw MathSyntaxException("Malformed number", start)
        return Expr.Num(Rational.parse(if (text.startsWith(".")) "0$text" else text), text)
    }
}
