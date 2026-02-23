package com.pgpe.property

import com.pgpe.ast.*
import com.pgpe.compiler.SqlCompiler
import com.pgpe.compiler.compile
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class CompilationPropertyTest {

    private val fixedMetadata = SchemaMetadata(
        listOf("users", "projects", "tasks", "comments", "files").map { name ->
            TableMetadata(
                name = name,
                schema = "public",
                columns = listOf(
                    ColumnInfo("id", "text"),
                    ColumnInfo("tenant_id", "text"),
                    ColumnInfo("name", "text"),
                    ColumnInfo("email", "text"),
                    ColumnInfo("is_deleted", "bool"),
                    ColumnInfo("project_id", "text"),
                    ColumnInfo("status", "text")
                )
            )
        }
    )

    @Test
    fun `compilation is deterministic over random policies`() = runBlocking<Unit> {
        checkAll(10_000, arbCompilablePolicy) { policy ->
            val policySet = PolicySet(listOf(policy))
            val compiled1 = compile(policySet, fixedMetadata)
            val compiled2 = compile(policySet, fixedMetadata)

            val sql1 = SqlCompiler.render(compiled1)
            val sql2 = SqlCompiler.render(compiled2)

            sql1 shouldBe sql2
        }
    }

    @Test
    fun `compiled SQL contains valid structure`() = runBlocking<Unit> {
        checkAll(10_000, arbCompilablePolicy) { policy ->
            val policySet = PolicySet(listOf(policy))
            val compiled = compile(policySet, fixedMetadata)

            for (table in compiled.tables) {
                table.enableRls.shouldNotBeEmpty()
                table.enableRls shouldBe "ALTER TABLE public.${table.table} ENABLE ROW LEVEL SECURITY;"
                table.forceRls shouldBe "ALTER TABLE public.${table.table} FORCE ROW LEVEL SECURITY;"

                for (compiledPolicy in table.policies) {
                    compiledPolicy.sql.shouldNotBeEmpty()
                    assert(compiledPolicy.sql.contains("CREATE POLICY")) {
                        "SQL should contain CREATE POLICY: ${compiledPolicy.sql}"
                    }
                    assert(compiledPolicy.sql.contains("ON public.${table.table}")) {
                        "SQL should reference table: ${compiledPolicy.sql}"
                    }
                    assert(compiledPolicy.sql.contains("USING")) {
                        "SQL should contain USING clause: ${compiledPolicy.sql}"
                    }
                    assert(compiledPolicy.sql.endsWith(";")) {
                        "SQL should end with semicolon: ${compiledPolicy.sql}"
                    }
                }
            }
        }
    }

    @Test
    fun `compilation preserves AS PERMISSIVE or RESTRICTIVE`() = runBlocking<Unit> {
        checkAll(10_000, arbCompilablePolicy) { policy ->
            val policySet = PolicySet(listOf(policy))
            val compiled = compile(policySet, fixedMetadata)

            for (table in compiled.tables) {
                for (compiledPolicy in table.policies) {
                    val expectedType = "AS ${policy.type.name}"
                    assert(compiledPolicy.sql.contains(expectedType)) {
                        "SQL should contain $expectedType: ${compiledPolicy.sql}"
                    }
                }
            }
        }
    }

    @Test
    fun `render produces non-empty output for any compiled state with tables`() = runBlocking<Unit> {
        checkAll(10_000, arbCompilablePolicy) { policy ->
            val policySet = PolicySet(listOf(policy))
            val compiled = compile(policySet, fixedMetadata)

            if (compiled.tables.isNotEmpty()) {
                val rendered = SqlCompiler.render(compiled)
                rendered.shouldNotBeEmpty()
            }
        }
    }

    @Test
    fun `policy naming follows convention`() = runBlocking<Unit> {
        checkAll(10_000, arbCompilablePolicy) { policy ->
            val policySet = PolicySet(listOf(policy))
            val compiled = compile(policySet, fixedMetadata)

            for (table in compiled.tables) {
                for (compiledPolicy in table.policies) {
                    compiledPolicy.name shouldBe "${policy.name}_${table.table}"
                }
            }
        }
    }

    @Test
    fun `multiple compilations of same policy set produce identical output`() = runBlocking<Unit> {
        val arbMultiPolicySet = Arb.list(arbCompilablePolicy, 1..3)
            .map { PolicySet(it) }

        checkAll(5_000, arbMultiPolicySet) { policySet ->
            val compiled1 = compile(policySet, fixedMetadata)
            val compiled2 = compile(policySet, fixedMetadata)

            SqlCompiler.render(compiled1) shouldBe SqlCompiler.render(compiled2)
        }
    }
}
