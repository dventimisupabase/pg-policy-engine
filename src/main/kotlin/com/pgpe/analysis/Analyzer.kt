package com.pgpe.analysis

import com.pgpe.ast.*
import com.pgpe.compiler.SelectorEvaluator

fun analyze(
    policySet: PolicySet,
    metadata: SchemaMetadata,
    config: ProofConfig = ProofConfig()
): AnalysisReport {
    val normalized = normalize(policySet)
    val evaluator = SelectorEvaluator(metadata)
    val context = ProofContext(normalized, metadata, evaluator, config)

    val enabledProofs = ProofRegistry.enabledProofs(config)
    val results = enabledProofs.flatMap { it.execute(context) }

    return AnalysisReport(results)
}
