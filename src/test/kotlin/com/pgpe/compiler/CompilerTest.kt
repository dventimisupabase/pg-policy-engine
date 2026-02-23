package com.pgpe.compiler

import com.pgpe.ast.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class CompilerTest {

    private val metadata = SchemaMetadata(
        tables = listOf(
            TableMetadata("users", "public", listOf(
                ColumnInfo("id", "uuid"),
                ColumnInfo("tenant_id", "uuid"),
                ColumnInfo("email", "text")
            )),
            TableMetadata("projects", "public", listOf(
                ColumnInfo("id", "uuid"),
                ColumnInfo("tenant_id", "uuid"),
                ColumnInfo("name", "text"),
                ColumnInfo("is_deleted", "boolean")
            )),
            TableMetadata("tasks", "public", listOf(
                ColumnInfo("id", "uuid"),
                ColumnInfo("project_id", "uuid"),
                ColumnInfo("title", "text")
            )),
            TableMetadata("comments", "public", listOf(
                ColumnInfo("id", "uuid"),
                ColumnInfo("tenant_id", "uuid"),
                ColumnInfo("body", "text")
            )),
            TableMetadata("files", "public", listOf(
                ColumnInfo("id", "uuid"),
                ColumnInfo("project_id", "uuid"),
                ColumnInfo("path", "text")
            ))
        )
    )

    private fun appendixA1Policies(): PolicySet {
        val tenantIsolation = Policy(
            name = "tenant_isolation",
            type = PolicyType.PERMISSIVE,
            commands = setOf(Command.SELECT, Command.INSERT, Command.UPDATE, Command.DELETE),
            selector = Selector.HasColumn("tenant_id"),
            clauses = listOf(
                Clause(setOf(
                    Atom.BinaryAtom(
                        ValueSource.Col("tenant_id"),
                        BinaryOp.EQ,
                        ValueSource.Session("app.tenant_id")
                    )
                ))
            )
        )

        val tenantIsolationViaProject = Policy(
            name = "tenant_isolation_via_project",
            type = PolicyType.PERMISSIVE,
            commands = setOf(Command.SELECT, Command.INSERT, Command.UPDATE, Command.DELETE),
            selector = Selector.Or(Selector.Named("tasks"), Selector.Named("files")),
            clauses = listOf(
                Clause(setOf(
                    Atom.TraversalAtom(
                        Relationship(null, "project_id", "projects", "id"),
                        Clause(setOf(
                            Atom.BinaryAtom(
                                ValueSource.Col("tenant_id"),
                                BinaryOp.EQ,
                                ValueSource.Session("app.tenant_id")
                            )
                        ))
                    )
                ))
            )
        )

        val softDelete = Policy(
            name = "soft_delete",
            type = PolicyType.RESTRICTIVE,
            commands = setOf(Command.SELECT),
            selector = Selector.HasColumn("is_deleted"),
            clauses = listOf(
                Clause(setOf(
                    Atom.BinaryAtom(
                        ValueSource.Col("is_deleted"),
                        BinaryOp.EQ,
                        ValueSource.Lit(LiteralValue.BoolLit(false))
                    )
                ))
            )
        )

        return PolicySet(listOf(tenantIsolation, tenantIsolationViaProject, softDelete))
    }

    @Test
    fun `compile matches golden file character-identical`() {
        val compiled = compile(appendixA1Policies(), metadata)
        val actual = SqlCompiler.render(compiled)
        val expected = this::class.java.classLoader.getResource("golden/appendix-a5.sql")!!.readText()

        actual.trim() shouldBe expected.trim()
    }

    @Test
    fun `compilation is deterministic`() {
        val compiled1 = compile(appendixA1Policies(), metadata)
        val compiled2 = compile(appendixA1Policies(), metadata)

        SqlCompiler.render(compiled1) shouldBe SqlCompiler.render(compiled2)
    }

    @Test
    fun `compile simple atom`() {
        val atom = Atom.BinaryAtom(
            ValueSource.Col("tenant_id"),
            BinaryOp.EQ,
            ValueSource.Session("app.tenant_id")
        )
        val sql = SqlCompiler.compileAtom(atom, "test_table")
        sql shouldBe "tenant_id = current_setting('app.tenant_id')"
    }

    @Test
    fun `compile boolean literal`() {
        val atom = Atom.BinaryAtom(
            ValueSource.Col("is_deleted"),
            BinaryOp.EQ,
            ValueSource.Lit(LiteralValue.BoolLit(false))
        )
        val sql = SqlCompiler.compileAtom(atom, "test_table")
        sql shouldBe "is_deleted = false"
    }

    @Test
    fun `compile traversal atom produces EXISTS subquery`() {
        val atom = Atom.TraversalAtom(
            Relationship(null, "project_id", "projects", "id"),
            Clause(setOf(
                Atom.BinaryAtom(
                    ValueSource.Col("tenant_id"),
                    BinaryOp.EQ,
                    ValueSource.Session("app.tenant_id")
                )
            ))
        )
        val sql = SqlCompiler.compileAtom(atom, "tasks")
        sql shouldContain "EXISTS ("
        sql shouldContain "SELECT 1 FROM public.projects"
        sql shouldContain "public.projects.id = public.tasks.project_id"
        sql shouldContain "public.projects.tenant_id = current_setting('app.tenant_id')"
    }

    @Test
    fun `naming convention is policy_name underscore table_name`() {
        val compiled = compile(appendixA1Policies(), metadata)
        val policyNames = compiled.tables.flatMap { t -> t.policies.map { it.name } }
        policyNames shouldBe listOf(
            "tenant_isolation_users",
            "tenant_isolation_projects",
            "soft_delete_projects",
            "tenant_isolation_via_project_tasks",
            "tenant_isolation_comments",
            "tenant_isolation_via_project_files"
        )
    }

    @Test
    fun `all 5 tables are compiled`() {
        val compiled = compile(appendixA1Policies(), metadata)
        compiled.tables.map { it.table } shouldBe listOf("users", "projects", "tasks", "comments", "files")
    }
}
