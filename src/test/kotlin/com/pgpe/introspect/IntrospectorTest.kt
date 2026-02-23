package com.pgpe.introspect

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.DriverManager

class IntrospectorTest {

    private fun withPostgres(block: (java.sql.Connection) -> Unit) {
        val dockerAvailable = try {
            val process = ProcessBuilder("docker", "info").start()
            process.waitFor() == 0
        } catch (_: Exception) { false }

        if (!dockerAvailable) {
            println("Docker not available, skipping integration test")
            return
        }

        val containerName = "pgpe-introspect-test-${System.currentTimeMillis()}"
        val password = "testpass"
        val port = 15433

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

            conn?.use { block(it) } ?: error("Could not connect to PostgreSQL")
        } finally {
            ProcessBuilder("docker", "stop", containerName).start().waitFor()
        }
    }

    @Test
    fun `introspect empty table shows RLS disabled`() {
        withPostgres { conn ->
            conn.createStatement().execute("CREATE TABLE test_table (id serial PRIMARY KEY, name text)")
            val state = Introspector.introspect(conn, listOf("test_table"))
            state.tables shouldHaveSize 1
            state.tables[0].rlsEnabled shouldBe false
            state.tables[0].rlsForced shouldBe false
            state.tables[0].policies shouldHaveSize 0
        }
    }

    @Test
    fun `introspect table with RLS and policy`() {
        withPostgres { conn ->
            conn.createStatement().execute("""
                CREATE TABLE users (id serial PRIMARY KEY, tenant_id text);
                ALTER TABLE users ENABLE ROW LEVEL SECURITY;
                ALTER TABLE users FORCE ROW LEVEL SECURITY;
                CREATE POLICY tenant_isolation_users ON users
                  AS PERMISSIVE FOR ALL
                  USING (tenant_id = current_setting('app.tenant_id'));
            """.trimIndent())

            val state = Introspector.introspect(conn, listOf("users"))
            state.tables shouldHaveSize 1
            state.tables[0].rlsEnabled shouldBe true
            state.tables[0].rlsForced shouldBe true
            state.tables[0].policies shouldHaveSize 1
            state.tables[0].policies[0].name shouldBe "tenant_isolation_users"
            state.tables[0].policies[0].type shouldBe "PERMISSIVE"
        }
    }
}
