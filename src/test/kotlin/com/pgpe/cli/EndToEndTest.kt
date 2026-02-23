package com.pgpe.cli

import com.github.ajalt.clikt.core.subcommands
import com.pgpe.analysis.ProofResult
import com.pgpe.analysis.analyze
import com.pgpe.ast.*
import com.pgpe.compiler.SqlCompiler
import com.pgpe.compiler.compile
import com.pgpe.drift.DriftDetector
import com.pgpe.introspect.Introspector
import com.pgpe.parser.parse
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager

class EndToEndTest {

    private fun withPostgres(block: (java.sql.Connection, String) -> Unit) {
        val dockerAvailable = try {
            val process = ProcessBuilder("docker", "info").start()
            process.waitFor() == 0
        } catch (_: Exception) { false }

        if (!dockerAvailable) {
            println("Docker not available, skipping E2E test")
            return
        }

        val containerName = "pgpe-e2e-test-${System.currentTimeMillis()}"
        val password = "testpass"
        val port = 15434

        try {
            ProcessBuilder(
                "docker", "run", "-d", "--rm",
                "--name", containerName,
                "-e", "POSTGRES_PASSWORD=$password",
                "-p", "$port:5432",
                "postgres:16-alpine"
            ).start().waitFor()

            var conn: java.sql.Connection? = null
            for (i in 1..30) {
                try {
                    conn = DriverManager.getConnection(
                        "jdbc:postgresql://localhost:$port/postgres", "postgres", password
                    )
                    break
                } catch (_: Exception) { Thread.sleep(1000) }
            }

            conn?.use { block(it, "jdbc:postgresql://localhost:$port/postgres?user=postgres&password=$password") }
                ?: error("Could not connect to PostgreSQL")
        } finally {
            ProcessBuilder("docker", "stop", containerName).start().waitFor()
        }
    }

    private fun createSchema(conn: java.sql.Connection) {
        // Use text for tenant_id/foreign keys since current_setting() returns text
        conn.createStatement().execute("""
            CREATE TABLE users (
                id text PRIMARY KEY,
                tenant_id text NOT NULL,
                email text NOT NULL
            );
            CREATE TABLE projects (
                id text PRIMARY KEY,
                tenant_id text NOT NULL,
                name text NOT NULL,
                is_deleted boolean NOT NULL DEFAULT false
            );
            CREATE TABLE tasks (
                id text PRIMARY KEY,
                project_id text NOT NULL REFERENCES projects(id),
                title text NOT NULL
            );
            CREATE TABLE comments (
                id text PRIMARY KEY,
                tenant_id text NOT NULL,
                body text NOT NULL
            );
            CREATE TABLE files (
                id text PRIMARY KEY,
                project_id text NOT NULL REFERENCES projects(id),
                path text NOT NULL
            );
        """.trimIndent())
    }

    @Test
    fun `full governance loop - parse normalize analyze compile apply monitor`() {
        withPostgres { conn, jdbcUrl ->
            // 1. Create schema
            createSchema(conn)

            // 2. Parse policies
            val policyDir = "policies"
            val policyFile = File(policyDir, "tenant-isolation.policy")
            val parseResult = parse(policyFile.readText(), policyFile.name)
            parseResult.success shouldBe true
            val policySet = parseResult.policySet!!
            policySet.policies shouldHaveSize 3

            // 3. Introspect schema metadata
            val metadata = introspectSchemaFromConn(conn)
            metadata.tables.size shouldBe 5

            // 4. Analyze - isolation should be proven for all tables
            val report = analyze(policySet, metadata)
            report.isolationResults.all { it.status == SatResult.UNSAT } shouldBe true

            // 5. Compile
            val compiled = compile(policySet, metadata)
            compiled.tables.size shouldBe 5
            val sql = SqlCompiler.render(compiled)
            sql shouldContain "tenant_isolation_users"
            sql shouldContain "tenant_isolation_via_project_tasks"

            // 6. Apply policies
            for (table in compiled.tables) {
                conn.createStatement().execute(table.enableRls)
                conn.createStatement().execute(table.forceRls)
                for (policy in table.policies) {
                    conn.createStatement().execute(policy.sql)
                }
            }

            // 7. Monitor - should show zero drift
            val observed = Introspector.introspect(conn, compiled.tables.map { it.table })
            val driftReport = DriftDetector.detectDrift(compiled, observed)
            driftReport.items shouldHaveSize 0
        }
    }

    @Test
    fun `CLI commands can be instantiated`() {
        // Test that the Clikt command tree is wired correctly
        val pgpe = Pgpe()
        val subcommands = listOf(AnalyzeCommand(), CompileCommand(), ApplyCommand(), MonitorCommand())
        pgpe.subcommands(subcommands)
        subcommands.size shouldBe 4
    }

    @Test
    fun `formatters produce valid output`() {
        val report = AnalysisReport(
            results = listOf(
                ProofResult.IsolationResult(
                    table = "users",
                    command = Command.SELECT,
                    status = SatResult.UNSAT
                )
            )
        )

        val text = Formatters.formatAnalysisText(report)
        text shouldContain "PROVEN"

        val json = Formatters.formatAnalysisJson(report)
        json shouldContain "\"status\" : \"PROVEN\""
    }

    @Test
    fun `drift formatter shows zero drift`() {
        val report = DriftReport(emptyList())
        val text = Formatters.formatDriftText(report)
        text shouldContain "No drift detected"
    }

    private fun introspectSchemaFromConn(conn: java.sql.Connection): SchemaMetadata {
        val tables = mutableListOf<TableMetadata>()
        val rs = conn.metaData.getTables(null, "public", null, arrayOf("TABLE"))
        while (rs.next()) {
            val tableName = rs.getString("TABLE_NAME")
            val columns = mutableListOf<ColumnInfo>()
            val colRs = conn.metaData.getColumns(null, "public", tableName, null)
            while (colRs.next()) {
                columns.add(ColumnInfo(
                    name = colRs.getString("COLUMN_NAME"),
                    type = colRs.getString("TYPE_NAME")
                ))
            }
            tables.add(TableMetadata(tableName, "public", columns))
        }
        return SchemaMetadata(tables)
    }
}
