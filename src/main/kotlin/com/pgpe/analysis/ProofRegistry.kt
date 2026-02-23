package com.pgpe.analysis

import com.pgpe.analysis.proofs.*

object ProofRegistry {

    private val proofs = mutableListOf<Proof>()

    init {
        register(TenantIsolationProof())
        register(CoverageProof())
        register(ContradictionProof())
        register(SoftDeleteProof())
        register(SubsumptionProof())
        register(RedundancyProof())
        register(WriteRestrictionProof())
        register(RoleSeparationProof())
        register(PolicyEquivalenceProof())
    }

    fun register(proof: Proof) {
        proofs.add(proof)
    }

    fun allProofs(): List<Proof> = proofs.toList()

    fun enabledProofs(config: ProofConfig): List<Proof> {
        return proofs.filter { proof ->
            when {
                config.enabledProofs.isNotEmpty() -> proof.id in config.enabledProofs
                proof.id in config.disabledProofs -> false
                else -> proof.enabledByDefault
            }
        }
    }
}
