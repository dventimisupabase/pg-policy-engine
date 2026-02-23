package com.pgpe.cli

import com.pgpe.engine.PolicyParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MvpSmokeTest {
    @Test
    fun parsesTenantPolicy() {
        val input = """
            POLICY tenant_isolation
            FOR SELECT, UPDATE
            SELECTOR has_column('tenant_id')
            CLAUSE col('tenant_id') = session('app.tenant_id')
        """.trimIndent()

        val parsed = PolicyParser.parse(input)
        assertTrue(parsed.issues.isEmpty())
        assertEquals("tenant_isolation", parsed.policy?.name)
    }
}
