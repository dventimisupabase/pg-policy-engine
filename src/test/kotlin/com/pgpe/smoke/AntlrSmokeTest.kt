package com.pgpe.smoke

import com.pgpe.parser.parse
import org.junit.jupiter.api.Test
import io.kotest.matchers.shouldBe

class AntlrSmokeTest {

    @Test
    fun `parse simple policy with generated lexer and parser`() {
        val source = """
            POLICY test PERMISSIVE FOR SELECT SELECTOR ALL
            CLAUSE col(x) = lit(1)
        """.trimIndent()

        val result = parse(source)
        result.success shouldBe true
        result.policySet!!.policies.size shouldBe 1
    }
}
