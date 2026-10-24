package com.receiptscan.scanner.math.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MathSolverTest {
    private fun value(s: String) = ((MathSolver.solve(s) as MathResult.Solved).answer as MathAnswer.Value).display
    private fun roots(s: String) = ((MathSolver.solve(s) as MathResult.Solved).answer as MathAnswer.Roots).roots
    private fun error(s: String) = MathSolver.solve(s) as MathResult.Error

    @Test fun `exact arithmetic with precedence fractions and percent`() {
        assertThat(value("2 + 3 × 4")).isEqualTo("14")
        assertThat(value("(2 + 3) × 4")).isEqualTo("20")
        assertThat(value("1/3 + 1/6")).isEqualTo("0.5")
        assertThat(value("1/3 + 1/3")).isEqualTo("2/3")
        assertThat(value("0.1 + 0.2")).isEqualTo("0.3")
        assertThat(value("20% of 500")).isEqualTo("100")
        assertThat(value("2^10")).isEqualTo("1024")
        assertThat(value("√144")).isEqualTo("12")
        assertThat(value("-3^2")).isEqualTo("-9")
        assertThat(value("√2")).startsWith("≈ 1.414213562")
    }

    @Test fun `linear equations with check`() {
        assertThat(roots("2x + 3 = 11")).containsExactly("4")
        assertThat(roots("3(x - 2) = x + 4")).containsExactly("5")
        assertThat(roots("x/4 = 0.5")).containsExactly("2")
        val a = (MathSolver.solve("2x + 3 = 11") as MathResult.Solved).answer
        assertThat(a.check).isEqualTo("Substitute x = 4: both sides equal")
        assertThat(a.steps).isNotEmpty()
    }

    @Test fun `quadratics with exact and approximate roots`() {
        assertThat(roots("x² - 5x + 6 = 0")).containsExactly("2", "3").inOrder()
        assertThat(roots("x^2 = 4")).containsExactly("-2", "2").inOrder()
        assertThat(roots("x^2 - 2 = 0")).containsExactly("≈ -1.4142136", "≈ 1.4142136").inOrder()
        assertThat((MathSolver.solve("x^2 + 1 = 0") as MathResult.Solved).answer).isInstanceOf(MathAnswer.NoSolution::class.java)
    }

    @Test fun `errors are explicit`() {
        assertThat(error("1/0").kind).isEqualTo(MathResult.Kind.DOMAIN)
        assertThat(error("√-4").kind).isEqualTo(MathResult.Kind.DOMAIN)
        assertThat(error("x^3 = 8").kind).isEqualTo(MathResult.Kind.UNSUPPORTED)
        assertThat(error("x + y = 3").kind).isEqualTo(MathResult.Kind.UNSUPPORTED)
        assertThat(error("2 + * 3").kind).isEqualTo(MathResult.Kind.SYNTAX)
        assertThat(error("system.exit(0)").kind).isEqualTo(MathResult.Kind.SYNTAX)
        assertThat(error("(2+3").kind).isEqualTo(MathResult.Kind.SYNTAX)
    }

    @Test fun `identities and contradictions`() {
        assertThat((MathSolver.solve("2x + 2 = 2(x + 1)") as MathResult.Solved).answer).isInstanceOf(MathAnswer.AllValues::class.java)
        assertThat((MathSolver.solve("x + 1 = x + 2") as MathResult.Solved).answer).isInstanceOf(MathAnswer.NoSolution::class.java)
    }
}
