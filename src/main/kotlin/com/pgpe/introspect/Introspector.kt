package com.pgpe.introspect

import com.pgpe.ast.ObservedPolicy
import com.pgpe.ast.ObservedState
import com.pgpe.ast.ObservedTableState
import java.sql.Connection

object Introspector {

    fun introspect(connection: Connection, tables: List<String>, schema: String = "public"): ObservedState {
        val tableStates = tables.map { tableName ->
            val rlsStatus = queryRlsStatus(connection, tableName, schema)
            val policies = queryPolicies(connection, tableName, schema)
            ObservedTableState(
                table = tableName,
                rlsEnabled = rlsStatus.first,
                rlsForced = rlsStatus.second,
                policies = policies
            )
        }
        return ObservedState(tableStates)
    }

    private fun queryRlsStatus(connection: Connection, tableName: String, schema: String): Pair<Boolean, Boolean> {
        val sql = """
            SELECT c.relrowsecurity, c.relforcerowsecurity
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relname = ? AND n.nspname = ?
        """.trimIndent()

        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, tableName)
            stmt.setString(2, schema)
            val rs = stmt.executeQuery()
            return if (rs.next()) {
                Pair(rs.getBoolean("relrowsecurity"), rs.getBoolean("relforcerowsecurity"))
            } else {
                Pair(false, false)
            }
        }
    }

    private fun queryPolicies(connection: Connection, tableName: String, schema: String): List<ObservedPolicy> {
        val sql = """
            SELECT polname, polpermissive,
                   CASE polcmd
                       WHEN 'r' THEN 'SELECT'
                       WHEN 'a' THEN 'INSERT'
                       WHEN 'w' THEN 'UPDATE'
                       WHEN 'd' THEN 'DELETE'
                       WHEN '*' THEN 'ALL'
                   END as cmd,
                   pg_get_expr(polqual, polrelid) as using_expr,
                   pg_get_expr(polwithcheck, polrelid) as check_expr
            FROM pg_policy p
            JOIN pg_class c ON c.oid = p.polrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relname = ? AND n.nspname = ?
            ORDER BY polname
        """.trimIndent()

        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, tableName)
            stmt.setString(2, schema)
            val rs = stmt.executeQuery()
            val policies = mutableListOf<ObservedPolicy>()
            while (rs.next()) {
                policies.add(ObservedPolicy(
                    name = rs.getString("polname"),
                    table = tableName,
                    type = if (rs.getBoolean("polpermissive")) "PERMISSIVE" else "RESTRICTIVE",
                    command = rs.getString("cmd"),
                    usingExpr = rs.getString("using_expr"),
                    checkExpr = rs.getString("check_expr")
                ))
            }
            return policies
        }
    }
}
