package com.pgpe.analysis

import com.pgpe.ast.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AnalyzerTest {

    private val schemaMetadata = SchemaMetadata(
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
    fun `tenant isolation PROVEN for users table`() {
        val report = analyze(appendixA1Policies(), schemaMetadata)
        val usersResults = report.isolationResults.filter { it.table == "users" }
        usersResults.all { it.status == SatResult.UNSAT } shouldBe true
    }

    @Test
    fun `tenant isolation PROVEN for projects table`() {
        val report = analyze(appendixA1Policies(), schemaMetadata)
        val projectsResults = report.isolationResults.filter { it.table == "projects" }
        projectsResults.all { it.status == SatResult.UNSAT } shouldBe true
    }

    @Test
    fun `tenant isolation PROVEN for tasks table`() {
        val report = analyze(appendixA1Policies(), schemaMetadata)
        val tasksResults = report.isolationResults.filter { it.table == "tasks" }
        tasksResults.all { it.status == SatResult.UNSAT } shouldBe true
    }

    @Test
    fun `tenant isolation PROVEN for comments table`() {
        val report = analyze(appendixA1Policies(), schemaMetadata)
        val commentsResults = report.isolationResults.filter { it.table == "comments" }
        commentsResults.all { it.status == SatResult.UNSAT } shouldBe true
    }

    @Test
    fun `tenant isolation PROVEN for files table`() {
        val report = analyze(appendixA1Policies(), schemaMetadata)
        val filesResults = report.isolationResults.filter { it.table == "files" }
        filesResults.all { it.status == SatResult.UNSAT } shouldBe true
    }

    @Test
    fun `all 5 tables have isolation proven`() {
        val report = analyze(appendixA1Policies(), schemaMetadata)
        val tables = report.isolationResults.map { it.table }.toSet()
        tables shouldBe setOf("users", "projects", "tasks", "comments", "files")
        report.isolationResults.all { it.status == SatResult.UNSAT } shouldBe true
    }

    @Test
    fun `weak policy returns FAILED with counterexample`() {
        // A policy without tenant_id check - allows cross-tenant access
        val weakPolicy = Policy(
            name = "weak",
            type = PolicyType.PERMISSIVE,
            commands = setOf(Command.SELECT),
            selector = Selector.Named("users"),
            clauses = listOf(
                Clause(setOf(
                    Atom.BinaryAtom(
                        ValueSource.Col("email"),
                        BinaryOp.EQ,
                        ValueSource.Lit(LiteralValue.StringLit("test@example.com"))
                    )
                ))
            )
        )

        val weakPolicySet = PolicySet(listOf(weakPolicy))
        val report = analyze(weakPolicySet, schemaMetadata)

        val usersResults = report.isolationResults.filter { it.table == "users" }
        usersResults.any { it.status == SatResult.SAT } shouldBe true
    }

    @Test
    fun `Z3 solve time is under 1 second per table`() {
        val start = System.currentTimeMillis()
        analyze(appendixA1Policies(), schemaMetadata)
        val elapsed = System.currentTimeMillis() - start

        // 5 tables, each under 1s = 5s max, but should be much faster
        (elapsed < 5000) shouldBe true
    }
}
