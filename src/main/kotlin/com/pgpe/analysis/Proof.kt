package com.pgpe.analysis

import com.pgpe.ast.*
import com.pgpe.compiler.SelectorEvaluator

// --- Core Proof Abstractions ---

interface Proof {
    val id: String
    val displayName: String
    val description: String
    val enabledByDefault: Boolean
    fun execute(context: ProofContext): List<ProofResult>
}

data class ProofContext(
    val normalizedPolicySet: PolicySet,
    val metadata: SchemaMetadata,
    val evaluator: SelectorEvaluator,
    val config: ProofConfig
)

data class ProofConfig(
    val enabledProofs: Set<String> = emptySet(),
    val disabledProofs: Set<String> = emptySet(),
    val timeoutMs: Int = 5000,
    val extras: Map<String, Any> = emptyMap()
)

// --- Proof Results ---

sealed class ProofResult {
    abstract val proofId: String

    data class IsolationResult(
        override val proofId: String = "tenant-isolation",
        val table: String,
        val command: Command,
        val status: SatResult,
        val counterexample: Map<String, String>? = null
    ) : ProofResult()

    data class CoverageResult(
        override val proofId: String = "coverage",
        val table: String,
        val hasPolicies: Boolean,
        val missingCommands: Set<Command> = emptySet()
    ) : ProofResult()

    data class ContradictionResult(
        override val proofId: String = "contradiction",
        val table: String,
        val command: Command,
        val message: String
    ) : ProofResult()

    data class SoftDeleteResult(
        override val proofId: String = "soft-delete",
        val table: String,
        val status: SatResult,
        val counterexample: Map<String, String>? = null
    ) : ProofResult()

    data class SubsumptionResult(
        override val proofId: String = "subsumption",
        val subsumingPolicy: String,
        val subsumedPolicy: String,
        val table: String
    ) : ProofResult()

    data class RedundancyResult(
        override val proofId: String = "redundancy",
        val redundantPolicy: String,
        val table: String,
        val command: Command
    ) : ProofResult()

    data class WriteRestrictionResult(
        override val proofId: String = "write-restriction",
        val table: String,
        val command: Command,
        val status: SatResult,
        val counterexample: Map<String, String>? = null
    ) : ProofResult()

    data class RoleSeparationResult(
        override val proofId: String = "role-separation",
        val table: String,
        val role1: String,
        val role2: String,
        val status: SatResult,
        val counterexample: Map<String, String>? = null
    ) : ProofResult()

    data class PolicyEquivalenceResult(
        override val proofId: String = "policy-equivalence",
        val table: String,
        val command: Command,
        val equivalent: Boolean,
        val counterexample: Map<String, String>? = null
    ) : ProofResult()
}
