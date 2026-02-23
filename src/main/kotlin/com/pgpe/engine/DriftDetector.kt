package com.pgpe.engine

object DriftDetector {
    fun detect(expectedSql: String, observedSql: String): List<String> {
        val expected = expectedSql.lines().map { it.trim() }.filter { it.isNotBlank() }
        val observed = observedSql.lines().map { it.trim() }.filter { it.isNotBlank() }

        val findings = mutableListOf<String>()
        expected.filter { it !in observed }.forEach { findings += "Missing in observed: $it" }
        observed.filter { it !in expected }.forEach { findings += "Extra in observed: $it" }
        return findings
    }
}
