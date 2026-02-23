package com.pgpe.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.pgpe.analysis.analyze
import com.pgpe.ast.*
import com.pgpe.compiler.SqlCompiler
import com.pgpe.compiler.compile
import com.pgpe.drift.DriftDetector
import com.pgpe.drift.Reconciler
import com.pgpe.introspect.Introspector
import com.pgpe.parser.parse
import java.io.File
import java.sql.DriverManager

class Pgpe : CliktCommand(name = "pgpe") {
    override fun run() = Unit
}

class AnalyzeCommand : CliktCommand(name = "analyze") {
    private val policyDir by option("--policy-dir").required()
    private val target by option("--target").required()
    private val format by option("--format").default("text")

    override fun run() {
        val policySet = loadPolicies(policyDir)
        val metadata = introspectSchema(target)
        val report = analyze(policySet, metadata)

        if (format == "json") {
            echo(Formatters.formatAnalysisJson(report))
        } else {
            echo(Formatters.formatAnalysisText(report))
        }

        if (report.isolationResults.any { it.status != SatResult.UNSAT }) {
            throw com.github.ajalt.clikt.core.ProgramResult(1)
        }
    }
}

class CompileCommand : CliktCommand(name = "compile") {
    private val policyDir by option("--policy-dir").required()
    private val target by option("--target").required()
    private val format by option("--format").default("text")

    override fun run() {
        val policySet = loadPolicies(policyDir)
        val metadata = introspectSchema(target)
        val compiled = compile(policySet, metadata)

        if (format == "json") {
            echo(Formatters.formatCompiledJson(compiled))
        } else {
            echo(SqlCompiler.render(compiled))
        }
    }
}

class ApplyCommand : CliktCommand(name = "apply") {
    private val policyDir by option("--policy-dir").required()
    private val target by option("--target").required()
    private val dryRun by option("--dry-run").flag()

    override fun run() {
        val policySet = loadPolicies(policyDir)
        val metadata = introspectSchema(target)
        val compiled = compile(policySet, metadata)

        if (dryRun) {
            echo("-- DRY RUN: Would execute the following SQL:")
            echo(SqlCompiler.render(compiled))
            return
        }

        val (url, user, password) = parseJdbcUrl(target)
        DriverManager.getConnection(url, user, password).use { conn ->
            for (table in compiled.tables) {
                conn.createStatement().execute(table.enableRls)
                conn.createStatement().execute(table.forceRls)
                for (policy in table.policies) {
                    conn.createStatement().execute(policy.sql)
                }
            }
        }
        echo("Applied policies to ${compiled.tables.size} tables.")
    }
}

class MonitorCommand : CliktCommand(name = "monitor") {
    private val policyDir by option("--policy-dir").required()
    private val target by option("--target").required()
    private val format by option("--format").default("text")
    private val reconcile by option("--reconcile").flag()

    override fun run() {
        val policySet = loadPolicies(policyDir)
        val metadata = introspectSchema(target)
        val compiled = compile(policySet, metadata)

        val (url, user, password) = parseJdbcUrl(target)
        val observed = DriverManager.getConnection(url, user, password).use { conn ->
            Introspector.introspect(conn, compiled.tables.map { it.table })
        }

        val report = DriftDetector.detectDrift(compiled, observed)

        if (format == "json") {
            echo(Formatters.formatDriftJson(report))
        } else {
            echo(Formatters.formatDriftText(report))
        }

        if (reconcile && report.items.isNotEmpty()) {
            val remediationSql = Reconciler.reconcile(report.items, compiled)
            echo("\n-- Remediation SQL:")
            for (stmt in remediationSql) {
                echo(stmt)
            }
        }

        if (report.items.isNotEmpty()) {
            throw com.github.ajalt.clikt.core.ProgramResult(1)
        }
    }
}

fun loadPolicies(dir: String): PolicySet {
    val policyDir = File(dir)
    if (!policyDir.isDirectory) error("Policy directory not found: $dir")

    val allPolicies = mutableListOf<com.pgpe.ast.Policy>()
    for (file in policyDir.listFiles()?.filter { it.extension == "policy" }?.sorted() ?: emptyList()) {
        val result = parse(file.readText(), file.name)
        if (!result.success) {
            val errors = result.errors.joinToString("\n") { "  ${it.fileName}:${it.line}:${it.column}: ${it.message}" }
            error("Parse errors in ${file.name}:\n$errors")
        }
        allPolicies.addAll(result.policySet?.policies ?: error("Parse succeeded but policySet is null for ${file.name}"))
    }
    return PolicySet(allPolicies)
}

fun introspectSchema(target: String): SchemaMetadata {
    val (url, user, password) = parseJdbcUrl(target)
    return DriverManager.getConnection(url, user, password).use { conn ->
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
        SchemaMetadata(tables)
    }
}

data class JdbcParts(val url: String, val user: String, val password: String)

fun parseJdbcUrl(target: String): JdbcParts {
    val regex = Regex("postgresql://([^:]+):([^@]+)@(.+)")
    val match = regex.matchEntire(target)
    return if (match != null) {
        val user = match.groupValues[1]
        val password = match.groupValues[2]
        val hostAndDb = match.groupValues[3]
        JdbcParts("jdbc:postgresql://$hostAndDb", user, password)
    } else if (target.startsWith("jdbc:")) {
        JdbcParts(target, "postgres", "postgres")
    } else {
        JdbcParts("jdbc:postgresql://$target", "postgres", "postgres")
    }
}

fun main(args: Array<String>) {
    Pgpe()
        .subcommands(AnalyzeCommand(), CompileCommand(), ApplyCommand(), MonitorCommand())
        .main(args)
}
