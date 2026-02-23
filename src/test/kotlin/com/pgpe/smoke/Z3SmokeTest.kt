package com.pgpe.smoke

import com.microsoft.z3.Context
import com.microsoft.z3.Status
import org.junit.jupiter.api.Test
import io.kotest.matchers.shouldBe

class Z3SmokeTest {

    @Test
    fun `solve x greater than 0 AND x less than 2 yields x equals 1`() {
        val ctx = Context()
        try {
            val solver = ctx.mkSolver()
            val x = ctx.mkIntConst("x")
            val zero = ctx.mkInt(0)
            val two = ctx.mkInt(2)

            solver.add(ctx.mkGt(x, zero))
            solver.add(ctx.mkLt(x, two))

            val status = solver.check()
            status shouldBe Status.SATISFIABLE

            val model = solver.model
            val xVal = model.evaluate(x, false)
            xVal.toString() shouldBe "1"
        } finally {
            ctx.close()
        }
    }
}
