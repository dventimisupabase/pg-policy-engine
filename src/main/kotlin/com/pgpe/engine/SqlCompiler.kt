package com.pgpe.engine

import com.pgpe.model.Policy

object SqlCompiler {
    fun compile(policy: Policy, table: String): String {
        val cmds = policy.commands.joinToString(", ") { it.name }
        val normalizedPolicyName = policy.name.replace("[^a-zA-Z0-9_]+".toRegex(), "_").lowercase()
        return buildString {
            appendLine("ALTER TABLE $table ENABLE ROW LEVEL SECURITY;")
            appendLine("ALTER TABLE $table FORCE ROW LEVEL SECURITY;")
            appendLine("DROP POLICY IF EXISTS $normalizedPolicyName ON $table;")
            append("CREATE POLICY $normalizedPolicyName ON $table FOR $cmds USING (${toSqlExpr(policy.clause)});")
        }
    }

    private fun toSqlExpr(clause: String): String {
        return clause
            .replace("col('", "")
            .replace("')", "")
            .replace("session('app.tenant_id')", "current_setting('app.tenant_id')")
    }
}
