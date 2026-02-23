package com.pgpe.cli

import com.pgpe.engine.Analyzer
import com.pgpe.engine.DriftDetector
import com.pgpe.engine.PolicyParser
import com.pgpe.engine.SqlCompiler
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        exitProcess(2)
    }

    when (args[0]) {
        "parse" -> parseCmd(args.drop(1))
        "analyze" -> analyzeCmd(args.drop(1))
        "compile" -> compileCmd(args.drop(1))
        "monitor" -> monitorCmd(args.drop(1))
        else -> {
            println("Unknown command: ${args[0]}")
            printUsage()
            exitProcess(2)
        }
    }
}

private fun parseCmd(args: List<String>) {
    val file = requireFlag(args, "--file") ?: return
    val content = File(file).readText()
    val result = PolicyParser.parse(content)
    if (result.issues.isNotEmpty()) {
        result.issues.forEach { println("${file}:${it.line}: ${it.message}") }
        exitProcess(1)
    }
    println("OK: parsed policy '${result.policy!!.name}'")
}

private fun analyzeCmd(args: List<String>) {
    val file = requireFlag(args, "--file") ?: return
    val format = optionalFlag(args, "--format") ?: "text"
    val parsed = PolicyParser.parse(File(file).readText())
    if (parsed.policy == null) {
        parsed.issues.forEach { println("${file}:${it.line}: ${it.message}") }
        exitProcess(2)
    }
    val analysis = Analyzer.analyze(parsed.policy)
    if (format == "json") {
        println(
            """{"policy":"${analysis.policy.name}","tenantIsolationLikely":${analysis.tenantIsolationLikely},"reasons":[${analysis.reasons.joinToString(",") { "\"$it\"" }}]}""",
        )
    } else {
        println("Policy: ${analysis.policy.name}")
        println("tenantIsolationLikely: ${analysis.tenantIsolationLikely}")
        analysis.reasons.forEach { println("- $it") }
    }
    exitProcess(if (analysis.tenantIsolationLikely) 0 else 1)
}

private fun compileCmd(args: List<String>) {
    val file = requireFlag(args, "--file") ?: return
    val table = optionalFlag(args, "--table") ?: "public.accounts"
    val parsed = PolicyParser.parse(File(file).readText())
    if (parsed.policy == null) {
        parsed.issues.forEach { println("${file}:${it.line}: ${it.message}") }
        exitProcess(2)
    }
    println(SqlCompiler.compile(parsed.policy, table))
}

private fun monitorCmd(args: List<String>) {
    val expected = requireFlag(args, "--expected") ?: return
    val observed = requireFlag(args, "--observed") ?: return

    val drift = DriftDetector.detect(File(expected).readText(), File(observed).readText())
    if (drift.isEmpty()) {
        println("No drift detected")
        exitProcess(0)
    }

    println("Drift detected:")
    drift.forEach { println("- $it") }
    exitProcess(1)
}

private fun requireFlag(args: List<String>, flag: String): String? {
    val value = optionalFlag(args, flag)
    if (value == null) {
        println("Missing required flag: $flag")
        exitProcess(2)
    }
    return value
}

private fun optionalFlag(args: List<String>, flag: String): String? {
    val idx = args.indexOf(flag)
    return if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else null
}

private fun printUsage() {
    println("pgpe MVP commands:")
    println("  parse --file <policy-file>")
    println("  analyze --file <policy-file> [--format text|json]")
    println("  compile --file <policy-file> [--table <schema.table>]")
    println("  monitor --expected <compiled.sql> --observed <introspected.sql>")
}
