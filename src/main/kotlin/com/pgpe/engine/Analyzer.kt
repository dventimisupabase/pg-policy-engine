package com.pgpe.engine

import com.pgpe.model.AnalysisResult
import com.pgpe.model.Policy

object Analyzer {
    fun analyze(policy: Policy): AnalysisResult {
        val clause = policy.clause.lowercase()
        val reasons = mutableListOf<String>()

        val hasTenantColumn = clause.contains("tenant_id")
        val hasSessionTenant = clause.contains("session('app.tenant_id')") || clause.contains("session(\"app.tenant_id\")")

        if (!hasTenantColumn) reasons += "Clause does not reference tenant_id column"
        if (!hasSessionTenant) reasons += "Clause does not bind to session app.tenant_id"

        return AnalysisResult(
            policy = policy,
            tenantIsolationLikely = hasTenantColumn && hasSessionTenant,
            reasons = reasons,
        )
    }
}
