package com.pgpe.compiler

import com.pgpe.ast.*

fun compile(policySet: PolicySet, metadata: SchemaMetadata): CompiledState {
    val normalized = normalize(policySet)
    val evaluator = SelectorEvaluator(metadata)

    // Build table -> policies mapping, preserving table order from metadata
    val tableArtifacts = mutableListOf<TableArtifacts>()

    for (table in metadata.tables) {
        val applicablePolicies = normalized.policies.filter { policy ->
            evaluator.matches(policy.selector, table)
        }
        if (applicablePolicies.isEmpty()) continue

        val qualifiedName = "${table.schema}.${table.name}"

        val compiledPolicies = applicablePolicies.map { policy ->
            val policyName = "${policy.name}_${table.name}"
            val sql = SqlCompiler.compilePolicy(policy, table)
            CompiledPolicy(policyName, table.name, sql)
        }

        tableArtifacts.add(TableArtifacts(
            table = table.name,
            schema = table.schema,
            enableRls = "ALTER TABLE $qualifiedName ENABLE ROW LEVEL SECURITY;",
            forceRls = "ALTER TABLE $qualifiedName FORCE ROW LEVEL SECURITY;",
            policies = compiledPolicies
        ))
    }

    return CompiledState(tableArtifacts)
}

object SqlCompiler {

    fun compilePolicy(policy: Policy, table: TableMetadata): String {
        val policyName = "${policy.name}_${table.name}"
        val qualifiedTable = "${table.schema}.${table.name}"
        val typeClause = "AS ${policy.type.name}"
        val cmdClause = compileCmdClause(policy.commands)
        val usingExpr = compileClauses(policy.clauses, table.name)

        return buildString {
            append("CREATE POLICY $policyName\n")
            append("  ON $qualifiedTable\n")
            append("  $typeClause\n")
            append("  FOR $cmdClause\n")
            append("  USING ($usingExpr);")
        }
    }

    fun compileCmdClause(commands: Set<Command>): String {
        val allCommands = setOf(Command.SELECT, Command.INSERT, Command.UPDATE, Command.DELETE)
        return if (commands == allCommands) "ALL"
        else commands.joinToString(", ") { it.name }
    }

    fun compileClauses(clauses: List<Clause>, tableName: String): String {
        if (clauses.isEmpty()) return "false"
        return clauses.joinToString(" OR ") { compileClause(it, tableName) }
    }

    fun compileClause(clause: Clause, tableName: String): String {
        if (clause.atoms.isEmpty()) return "true"
        val sorted = clause.atoms.sortedBy { atomSortKey(it) }
        return sorted.joinToString(" AND ") { compileAtom(it, tableName) }
    }

    fun compileAtom(atom: Atom, tableName: String): String {
        return when (atom) {
            is Atom.BinaryAtom -> compileBinaryAtom(atom, tableName)
            is Atom.UnaryAtom -> compileUnaryAtom(atom)
            is Atom.TraversalAtom -> compileTraversalAtom(atom, tableName)
        }
    }

    private fun compileBinaryAtom(atom: Atom.BinaryAtom, tableName: String): String {
        val left = compileValueSource(atom.left, tableName, qualified = false)
        val right = compileValueSource(atom.right, tableName, qualified = false)
        val op = compileBinaryOp(atom.op)
        return "$left $op $right"
    }

    private fun compileUnaryAtom(atom: Atom.UnaryAtom): String {
        val source = compileValueSource(atom.source, "", qualified = false)
        return when (atom.op) {
            UnaryOp.IS_NULL -> "$source IS NULL"
            UnaryOp.IS_NOT_NULL -> "$source IS NOT NULL"
        }
    }

    private fun compileTraversalAtom(atom: Atom.TraversalAtom, sourceTable: String): String {
        val rel = atom.relationship
        val targetQualified = "public.${rel.targetTable}"
        val sourceQualified = "public.$sourceTable"

        // Compile inner clause with columns scoped to target table
        val innerAtoms = atom.clause.atoms.sortedBy { atomSortKey(it) }
        val innerParts = innerAtoms.map { innerAtom ->
            compileAtomForTarget(innerAtom, rel.targetTable)
        }

        val joinCondition = "$targetQualified.${rel.targetCol} = $sourceQualified.${rel.sourceCol}"

        val allConditions = listOf(joinCondition) + innerParts
        val whereClause = allConditions.joinToString("\n      AND ")

        return "EXISTS (\n    SELECT 1 FROM $targetQualified\n    WHERE $whereClause\n  )"
    }

    private fun compileAtomForTarget(atom: Atom, targetTable: String): String {
        return when (atom) {
            is Atom.BinaryAtom -> {
                val left = compileValueSource(atom.left, targetTable, qualified = true)
                val right = compileValueSource(atom.right, targetTable, qualified = true)
                val op = compileBinaryOp(atom.op)
                "$left $op $right"
            }
            is Atom.UnaryAtom -> {
                val source = compileValueSource(atom.source, targetTable, qualified = true)
                when (atom.op) {
                    UnaryOp.IS_NULL -> "$source IS NULL"
                    UnaryOp.IS_NOT_NULL -> "$source IS NOT NULL"
                }
            }
            is Atom.TraversalAtom -> compileTraversalAtom(atom, targetTable)
        }
    }

    private fun compileValueSource(vs: ValueSource, tableName: String, qualified: Boolean): String {
        return when (vs) {
            is ValueSource.Col -> if (qualified) "public.$tableName.${vs.name}" else vs.name
            is ValueSource.Session -> "current_setting('${vs.key}')"
            is ValueSource.Lit -> compileLiteral(vs.value)
            is ValueSource.Fn -> "${vs.name}(${vs.args.joinToString(", ") { compileValueSource(it, tableName, qualified) }})"
        }
    }

    private fun compileLiteral(lit: LiteralValue): String {
        return when (lit) {
            is LiteralValue.StringLit -> "'${lit.value}'"
            is LiteralValue.IntLit -> lit.value.toString()
            is LiteralValue.BoolLit -> lit.value.toString()
            is LiteralValue.NullLit -> "NULL"
            is LiteralValue.ListLit -> "(${lit.values.joinToString(", ") { compileLiteral(it) }})"
        }
    }

    private fun compileBinaryOp(op: BinaryOp): String {
        return when (op) {
            BinaryOp.EQ -> "="
            BinaryOp.NEQ -> "<>"
            BinaryOp.LT -> "<"
            BinaryOp.GT -> ">"
            BinaryOp.LTE -> "<="
            BinaryOp.GTE -> ">="
            BinaryOp.IN -> "IN"
            BinaryOp.NOT_IN -> "NOT IN"
            BinaryOp.LIKE -> "LIKE"
            BinaryOp.NOT_LIKE -> "NOT LIKE"
        }
    }

    private fun atomSortKey(atom: Atom): String {
        return when (atom) {
            is Atom.BinaryAtom -> "0_${atom.left}_${atom.op}_${atom.right}"
            is Atom.UnaryAtom -> "1_${atom.source}_${atom.op}"
            is Atom.TraversalAtom -> "2_${atom.relationship.targetTable}"
        }
    }

    fun render(compiled: CompiledState): String {
        return buildString {
            for ((index, table) in compiled.tables.withIndex()) {
                if (index > 0) append("\n")
                append("-- ============================================================\n")
                append("-- ${table.table}\n")
                append("-- ============================================================\n")
                append("${table.enableRls}\n")
                append("${table.forceRls}\n")
                for (policy in table.policies) {
                    append("\n")
                    append("${policy.sql}\n")
                }
            }
        }
    }
}
