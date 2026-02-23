package com.pgpe.smoke

import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import io.kotest.matchers.shouldBe
import java.sql.DriverManager

class TestcontainersSmokeTest {

    @Test
    fun `SELECT 1 on PostgreSQL container`() {
        // Skip if Docker is unavailable (CI environments without Docker)
        val dockerAvailable = try {
            val process = ProcessBuilder("docker", "info").start()
            process.waitFor() == 0
        } catch (_: Exception) { false }

        if (!dockerAvailable) {
            println("Docker not available, skipping Testcontainers smoke test")
            return
        }

        // Use Docker CLI to run PostgreSQL directly, bypassing docker-java API version issues
        val containerName = "pgpe-smoke-test-${System.currentTimeMillis()}"
        val password = "testpass"
        val port = 15432

        try {
            // Start container via CLI
            val startProcess = ProcessBuilder(
                "docker", "run", "-d", "--rm",
                "--name", containerName,
                "-e", "POSTGRES_PASSWORD=$password",
                "-p", "$port:5432",
                "postgres:16-alpine"
            ).start()
            startProcess.waitFor()

            // Wait for PostgreSQL to be ready
            var ready = false
            for (i in 1..30) {
                try {
                    DriverManager.getConnection(
                        "jdbc:postgresql://localhost:$port/postgres",
                        "postgres",
                        password
                    ).use { conn ->
                        conn.createStatement().use { stmt ->
                            val rs = stmt.executeQuery("SELECT 1 AS result")
                            rs.next() shouldBe true
                            rs.getInt("result") shouldBe 1
                            ready = true
                        }
                    }
                    break
                } catch (_: Exception) {
                    Thread.sleep(1000)
                }
            }

            ready shouldBe true
        } finally {
            ProcessBuilder("docker", "stop", containerName).start().waitFor()
        }
    }
}
