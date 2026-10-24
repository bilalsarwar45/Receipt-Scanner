package com.receiptscan.scanner.math.engine

import java.math.BigDecimal
import java.math.MathContext

/** Polynomial in one variable, degree ≤ 2, with exact coefficients [c0, c1, c2]. */
internal data class Poly(val c: List<Rational>) {
    init { require(c.size == 3) }
    val degree: Int get() = c.indexOfLast { !it.isZero }.coerceAtLeast(0)
    val isConstant get() = degree == 0
    operator fun plus(o: Poly) = Poly(c.zip(o.c) { a, b -> a + b })
    operator fun minus(o: Poly) = Poly(c.zip(o.c) { a, b -> a - b })
    operator fun times(o: Poly): Poly {
        val r = MutableList(5) { Rational.ZERO }
        for (i in 0..2) for (j in 0..2) r[i + j] = r[i + j] + c[i] * o.c[j]
        if (!r[3].isZero || !r[4].isZero) throw UnsupportedExpressionException("Only equations up to x² are supported")
        return Poly(r.take(3))
    }
    fun scale(k: Rational) = Poly(c.map { it * k })
    fun eval(x: Rational) = c[0] + c[1] * x + c[2] * x * x

    companion object {
        fun const(v: Rational) = Poly(listOf(v, Rational.ZERO, Rational.ZERO))
        val X = Poly(listOf(Rational.ZERO, Rational.ONE, Rational.ZERO))
    }
}

sealed interface MathAnswer {
    val steps: List<String>
    /** Substitution or recomputation that confirms the answer. */
    val check: String?

    data class Value(val exact: Rational?, val approx: BigDecimal?, override val steps: List<String>, override val check: String?) : MathAnswer {
        val display: String get() = exact?.display() ?: ("≈ " + approx!!.round(MathContext(10)).stripTrailingZeros().toPlainString())
    }
    data class Roots(val variable: Char, val roots: List<String>, override val steps: List<String>, override val check: String?) : MathAnswer
    data class NoSolution(val reason: String, override val steps: List<String>, override val check: String? = null) : MathAnswer
    data class AllValues(val variable: Char, override val steps: List<String>, override val check: String? = null) : MathAnswer
}

sealed interface MathResult {
    data class Solved(val readAs: String, val answer: MathAnswer) : MathResult
    data class Error(val readAs: String, val kind: Kind, val message: String, val position: Int? = null) : MathResult
    enum class Kind { SYNTAX, DOMAIN, UNSUPPORTED }
}

/**
 * Deterministic solvers: exact arithmetic, linear equations and quadratics with real roots.
 * Each answer carries rule-generated steps and a check by substitution.
 */
object MathSolver {

    fun solve(input: String): MathResult {
        val readAs = MathParser.normalize(input)
        return try {
            val st = MathParser.parse(input)
            val vars = (variables(st.left) + (st.right?.let(::variables) ?: emptySet()))
            if (vars.size > 1) return MathResult.Error(readAs, MathResult.Kind.UNSUPPORTED, "Only one variable is supported (found ${vars.sorted().joinToString()})")
            if (st.right == null) {
                if (vars.isNotEmpty()) return MathResult.Error(readAs, MathResult.Kind.UNSUPPORTED, "Add “= …” to solve for ${vars.first()}")
                MathResult.Solved(readAs, arithmetic(st.left))
            } else {
                val v = vars.firstOrNull() ?: 'x'
                if (vars.isEmpty()) {
                    val l = arithmetic(st.left) as MathAnswer.Value
                    val r = arithmetic(st.right) as MathAnswer.Value
                    val equal = l.exact != null && r.exact != null && l.exact == r.exact
                    return MathResult.Solved(readAs, MathAnswer.Value(null, null, listOf("Left side = ${l.display}", "Right side = ${r.display}"), if (equal) "The statement is true" else "The statement is false").let {
                        if (equal) MathAnswer.AllValues('x', it.steps, it.check) else MathAnswer.NoSolution("The two sides are not equal", it.steps, it.check)
                    })
                }
                MathResult.Solved(readAs, equation(toPoly(st.left) - toPoly(st.right), v))
            }
        } catch (e: MathSyntaxException) {
            MathResult.Error(readAs, MathResult.Kind.SYNTAX, e.message ?: "Can't read this", e.position)
        } catch (e: MathDomainException) {
            MathResult.Error(readAs, MathResult.Kind.DOMAIN, e.message ?: "Not defined")
        } catch (e: UnsupportedExpressionException) {
            MathResult.Error(readAs, MathResult.Kind.UNSUPPORTED, e.message ?: "Not supported yet")
        }
    }

    private fun variables(e: Expr): Set<Char> = when (e) {
        is Expr.Var -> setOf(e.name)
        is Expr.Num -> emptySet()
        is Expr.Neg -> variables(e.e)
        is Expr.Sqrt -> variables(e.e)
        is Expr.Percent -> variables(e.e)
        is Expr.Bin -> variables(e.l) + variables(e.r)
    }

    // ---------------- arithmetic ----------------

    private sealed interface N {
        data class Ex(val r: Rational) : N
        data class Ap(val d: BigDecimal) : N
    }

    private fun N.dec(): BigDecimal = when (this) { is N.Ex -> r.toBigDecimal(MathContext.DECIMAL128); is N.Ap -> d }
    private fun N.show(): String = when (this) { is N.Ex -> r.display(); is N.Ap -> "≈" + d.round(MathContext(10)).stripTrailingZeros().toPlainString() }

    private fun arithmetic(e: Expr): MathAnswer {
        val steps = mutableListOf<String>()
        val v = evalN(e, steps)
        val check = when (v) {
            is N.Ex -> "Recomputed with decimals: ${v.r.decimal(8)}"
            is N.Ap -> "Approximate; square root of a non-square number"
        }
        return MathAnswer.Value((v as? N.Ex)?.r, (v as? N.Ap)?.d, steps, check)
    }

    private fun evalN(e: Expr, steps: MutableList<String>): N = when (e) {
        is Expr.Num -> N.Ex(e.value)
        is Expr.Var -> throw UnsupportedExpressionException("Variable in an expression without '='")
        is Expr.Neg -> when (val x = evalN(e.e, steps)) { is N.Ex -> N.Ex(-x.r); is N.Ap -> N.Ap(x.d.negate()) }
        is Expr.Percent -> {
            val x = evalN(e.e, steps)
            val r = when (x) { is N.Ex -> N.Ex(x.r / Rational.of(100)); is N.Ap -> N.Ap(x.dec().divide(BigDecimal(100), MathContext.DECIMAL128)) }
            steps += "${x.show()}% = ${x.show()} ÷ 100 = ${r.show()}"
            r
        }
        is Expr.Sqrt -> {
            val x = evalN(e.e, steps)
            if (x.dec().signum() < 0) throw MathDomainException("Square root of a negative number isn't a real number")
            val r = (x as? N.Ex)?.r?.exactSqrt()?.let { N.Ex(it) } ?: N.Ap(x.dec().sqrt(MathContext.DECIMAL128))
            steps += "√${x.show()} = ${r.show()}"
            r
        }
        is Expr.Bin -> {
            val l = evalN(e.l, steps)
            val r = evalN(e.r, steps)
            val out: N = if (l is N.Ex && r is N.Ex) {
                when (e.op) {
                    '+' -> N.Ex(l.r + r.r)
                    '-' -> N.Ex(l.r - r.r)
                    '*' -> N.Ex(l.r * r.r)
                    '/' -> N.Ex(l.r / r.r)
                    '^' -> {
                        if (!r.r.isInteger) {
                            if (l.r.signum < 0) throw MathDomainException("Fractional power of a negative number isn't real")
                            if (r.r == Rational.of(1, 2)) l.r.exactSqrt()?.let { N.Ex(it) } ?: N.Ap(l.r.toBigDecimal(MathContext.DECIMAL128).sqrt(MathContext.DECIMAL128))
                            else throw UnsupportedExpressionException("Only whole-number powers and ½ are supported")
                        } else {
                            if (l.r.isZero && r.r.signum < 0) throw MathDomainException("Division by zero")
                            N.Ex(l.r.pow(r.r.num.intValueExact()))
                        }
                    }
                    else -> throw UnsupportedExpressionException("Operator ${e.op}")
                }
            } else {
                val a = l.dec(); val b = r.dec(); val mc = MathContext.DECIMAL128
                when (e.op) {
                    '+' -> N.Ap(a.add(b, mc)); '-' -> N.Ap(a.subtract(b, mc)); '*' -> N.Ap(a.multiply(b, mc))
                    '/' -> { if (b.signum() == 0) throw MathDomainException("Division by zero"); N.Ap(a.divide(b, mc)) }
                    else -> throw UnsupportedExpressionException("Powers of approximate values aren't supported")
                }
            }
            val sym = when (e.op) { '*' -> "×"; '/' -> "÷"; '-' -> "−"; else -> e.op.toString() }
            steps += "${l.show()} $sym ${r.show()} = ${out.show()}"
            out
        }
    }

    // ---------------- equations ----------------

    private fun toPoly(e: Expr): Poly = when (e) {
        is Expr.Num -> Poly.const(e.value)
        is Expr.Var -> Poly.X
        is Expr.Neg -> Poly.const(Rational.ZERO) - toPoly(e.e)
        is Expr.Percent -> toPoly(e.e).scale(Rational.of(1, 100))
        is Expr.Sqrt -> {
            val p = toPoly(e.e)
            if (!p.isConstant) throw UnsupportedExpressionException("Square roots of the variable aren't supported")
            Poly.const(p.c[0].exactSqrt() ?: throw UnsupportedExpressionException("Use an exact square root inside equations"))
        }
        is Expr.Bin -> {
            val l = toPoly(e.l)
            val r = toPoly(e.r)
            when (e.op) {
                '+' -> l + r
                '-' -> l - r
                '*' -> l * r
                '/' -> {
                    if (!r.isConstant) throw UnsupportedExpressionException("Dividing by the variable isn't supported")
                    l.scale(Rational.ONE / r.c[0])
                }
                '^' -> {
                    if (!r.isConstant || !r.c[0].isInteger || r.c[0].signum < 0) throw UnsupportedExpressionException("Only whole-number powers are supported")
                    val n = r.c[0].num.intValueExact()
                    if (l.isConstant) Poly.const(l.c[0].pow(n))
                    else (1..n).fold(Poly.const(Rational.ONE)) { acc, _ -> acc * l }
                }
                else -> throw UnsupportedExpressionException("Operator ${e.op}")
            }
        }
    }

    private fun term(coef: Rational, v: Char, power: Int): String = when {
        power == 0 -> coef.display()
        coef == Rational.ONE -> if (power == 1) "$v" else "$v²"
        coef == -Rational.ONE -> if (power == 1) "−$v" else "−$v²"
        else -> coef.display() + if (power == 1) "$v" else "$v²"
    }

    private fun equation(p: Poly, v: Char): MathAnswer {
        val (c0, c1, c2) = p.c
        val steps = mutableListOf<String>()
        return when (p.degree) {
            0 -> if (c0.isZero) MathAnswer.AllValues(v, listOf("Both sides simplify to the same expression")) else MathAnswer.NoSolution("No value of $v makes both sides equal", listOf("Simplifies to ${c0.display()} = 0, which is false"))
            1 -> {
                steps += "Move everything to one side: ${term(c1, v, 1)} + ${c0.display()} = 0".replace("+ -", "− ")
                steps += "Subtract ${c0.display()}: ${term(c1, v, 1)} = ${(-c0).display()}"
                val x = -c0 / c1
                steps += "Divide by ${c1.display()}: $v = ${x.display()}"
                val check = p.eval(x)
                MathAnswer.Roots(v, listOf(x.display()), steps, "Substitute $v = ${x.display()}: both sides equal${if (check.isZero) "" else " (check failed)"}")
            }
            else -> {
                steps += "Write as a$v² + b$v + c = 0 with a = ${c2.display()}, b = ${c1.display()}, c = ${c0.display()}"
                val disc = c1 * c1 - Rational.of(4) * c2 * c0
                steps += "Discriminant b² − 4ac = ${disc.display()}"
                if (disc.signum < 0) return MathAnswer.NoSolution("No real solutions (the discriminant is negative)", steps)
                val two = Rational.of(2) * c2
                val exact = disc.exactSqrt()
                if (exact != null) {
                    val r1 = (-c1 + exact) / two
                    val r2 = (-c1 - exact) / two
                    val roots = listOf(r1, r2).distinct().sorted()
                    steps += "$v = (−b ± √${disc.display()}) ÷ 2a = " + roots.joinToString(" or ") { it.display() }
                    val ok = roots.all { p.eval(it).isZero }
                    MathAnswer.Roots(v, roots.map { it.display() }, steps, "Substituting each root gives 0${if (ok) "" else " (check failed)"}")
                } else {
                    val mc = MathContext.DECIMAL128
                    val sq = disc.toBigDecimal(mc).sqrt(mc)
                    val b = c1.toBigDecimal(mc); val a2 = two.toBigDecimal(mc)
                    val roots = listOf(b.negate().add(sq).divide(a2, mc), b.negate().subtract(sq).divide(a2, mc)).sorted()
                    steps += "$v = (−${c1.display()} ± √${disc.display()}) ÷ ${two.display()}"
                    val residual = roots.maxOf { r ->
                        c2.toBigDecimal(mc).multiply(r).multiply(r).add(b.multiply(r)).add(c0.toBigDecimal(mc)).abs()
                    }
                    MathAnswer.Roots(
                        v,
                        roots.map { "≈ " + it.round(MathContext(8)).stripTrailingZeros().toPlainString() },
                        steps,
                        "Substituting the rounded roots leaves less than ${residual.round(MathContext(2)).toPlainString().let { if (residual.signum() == 0) "0" else "1e-20" }}",
                    )
                }
            }
        }
    }
}
