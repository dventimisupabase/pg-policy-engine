package com.pgpe.analysis

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProofRegistryTest {

    @Test
    fun `registry contains tenant-isolation proof`() {
        val proofs = ProofRegistry.allProofs()
        proofs.any { it.id == "tenant-isolation" } shouldBe true
    }

    @Test
    fun `enabledProofs returns all by default`() {
        val config = ProofConfig()
        val enabled = ProofRegistry.enabledProofs(config)
        enabled.map { it.id } shouldBe ProofRegistry.allProofs().filter { it.enabledByDefault }.map { it.id }
    }

    @Test
    fun `enabledProofs filters by enabledProofs config`() {
        val config = ProofConfig(enabledProofs = setOf("tenant-isolation"))
        val enabled = ProofRegistry.enabledProofs(config)
        enabled.map { it.id }.shouldContainExactly("tenant-isolation")
    }

    @Test
    fun `enabledProofs respects disabledProofs config`() {
        val config = ProofConfig(disabledProofs = setOf("tenant-isolation"))
        val enabled = ProofRegistry.enabledProofs(config)
        enabled.none { it.id == "tenant-isolation" } shouldBe true
    }
}
