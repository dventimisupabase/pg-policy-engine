package com.pgpe.ast

fun normalize(policySet: PolicySet): PolicySet {
    val normalizedPolicies = policySet.policies.map { normalizePolicy(it) }
    return PolicySet(normalizedPolicies)
}

private fun normalizePolicy(policy: Policy): Policy {
    var clauses = policy.clauses
    var prev: List<Clause>
    // Fixpoint loop: apply all rules until no changes
    do {
        prev = clauses
        clauses = normalizeClauses(clauses)
    } while (clauses != prev)
    return policy.copy(clauses = clauses)
}

private fun normalizeClauses(clauses: List<Clause>): List<Clause> {
    var result = clauses
        // Rule 4: Remove tautological atoms from each clause
        .map { removeTautologies(it) }
        // Rule 3: Remove contradictory clauses
        .filter { !isContradictory(it) }
        // Rule 6: Merge atoms within each clause
        .map { mergeAtoms(it) }
        // Re-filter after merging (merging can create contradictions)
        .filter { !isContradictory(it) }

    // Rule 2 & 5: Subsumption elimination between clauses
    result = eliminateSubsumedClauses(result)

    return result
}

// Rule 3: Contradiction elimination
// A clause is contradictory if it contains col(x) = lit(v1) AND col(x) = lit(v2) where v1 != v2
private fun isContradictory(clause: Clause): Boolean {
    val eqAtoms = clause.atoms.filterIsInstance<Atom.BinaryAtom>()
        .filter { it.op == BinaryOp.EQ }

    // Check for col(x) = lit(v1) AND col(x) = lit(v2) contradictions
    val colLitAtoms = eqAtoms.filter { it.left is ValueSource.Col && it.right is ValueSource.Lit }
    val byCol = colLitAtoms.groupBy { (it.left as ValueSource.Col).name }
    for ((_, atoms) in byCol) {
        val litValues = atoms.map { (it.right as ValueSource.Lit).value }.toSet()
        if (litValues.size > 1) return true
    }

    // Check for col(x) = session(k1) AND col(x) = session(k2) contradictions
    val colSessionAtoms = eqAtoms.filter { it.left is ValueSource.Col && it.right is ValueSource.Session }
    val byColSession = colSessionAtoms.groupBy { (it.left as ValueSource.Col).name }
    for ((col, atoms) in byColSession) {
        val sessionKeys = atoms.map { (it.right as ValueSource.Session).key }.toSet()
        if (sessionKeys.size > 1) return true
        // Also check for col = session AND col = lit contradiction
        if (byCol.containsKey(col)) return true
    }

    // Check for IN list with empty intersection after merging
    val inAtoms = clause.atoms.filterIsInstance<Atom.BinaryAtom>()
        .filter { it.op == BinaryOp.IN && it.left is ValueSource.Col && it.right is ValueSource.Lit }
    val byColIn = inAtoms.groupBy { (it.left as ValueSource.Col).name }
    for ((col, atoms) in byColIn) {
        if (atoms.size > 1) {
            val lists = atoms.map { ((it.right as ValueSource.Lit).value as LiteralValue.ListLit).values.toSet() }
            val intersection = lists.reduce { acc, set -> acc.intersect(set) }
            if (intersection.isEmpty()) return true
        }
        // Check EQ and IN contradiction
        if (byCol.containsKey(col)) {
            val eqValue = (byCol[col] ?: continue).first().let { (it.right as ValueSource.Lit).value }
            for (inAtom in atoms) {
                val list = ((inAtom.right as ValueSource.Lit).value as LiteralValue.ListLit).values
                if (eqValue !in list) return true
            }
        }
    }

    return false
}

// Rule 4: Tautology detection
// col(x) = col(x) is always true -> remove it
private fun removeTautologies(clause: Clause): Clause {
    val filtered = clause.atoms.filter { !isTautology(it) }
    return Clause(filtered.toSet())
}

private fun isTautology(atom: Atom): Boolean {
    if (atom is Atom.BinaryAtom && atom.op == BinaryOp.EQ) {
        return atom.left == atom.right
    }
    return false
}

// Rule 6: Atom merging
// - col(x) = lit(v) AND col(x) IN lit([v, ...]) -> col(x) = lit(v) (if v in list)
// - col(x) IN lit(S1) AND col(x) IN lit(S2) -> col(x) IN lit(S1 ∩ S2)
private fun mergeAtoms(clause: Clause): Clause {
    val atoms = clause.atoms.toMutableSet()
    val binaryAtoms = atoms.filterIsInstance<Atom.BinaryAtom>()

    // Group EQ atoms and IN atoms by column name
    val eqByCol = binaryAtoms
        .filter { it.op == BinaryOp.EQ && it.left is ValueSource.Col && it.right is ValueSource.Lit }
        .groupBy { (it.left as ValueSource.Col).name }

    val inByCol = binaryAtoms
        .filter { it.op == BinaryOp.IN && it.left is ValueSource.Col && it.right is ValueSource.Lit }
        .groupBy { (it.left as ValueSource.Col).name }

    val toRemove = mutableSetOf<Atom>()
    val toAdd = mutableSetOf<Atom>()

    // Rule 6a: EQ + IN -> just EQ (if value in list)
    for ((col, eqAtoms) in eqByCol) {
        val inAtoms = inByCol[col] ?: continue
        val eqValue = (eqAtoms.first().right as ValueSource.Lit).value
        for (inAtom in inAtoms) {
            val list = ((inAtom.right as ValueSource.Lit).value as LiteralValue.ListLit).values
            if (eqValue in list) {
                toRemove.add(inAtom)  // Keep the EQ, remove the IN
            }
            // If not in list, it will be caught by isContradictory
        }
    }

    // Rule 6b: IN ∩ IN -> merged IN
    for ((col, inAtoms) in inByCol) {
        if (inAtoms.size <= 1) continue
        if (eqByCol.containsKey(col)) continue  // Already handled by 6a

        val lists = inAtoms.map { ((it.right as ValueSource.Lit).value as LiteralValue.ListLit).values.toSet() }
        val intersection = lists.reduce { acc, set -> acc.intersect(set) }

        // Remove all original IN atoms
        toRemove.addAll(inAtoms)

        if (intersection.size == 1) {
            // Single value -> EQ
            toAdd.add(Atom.BinaryAtom(
                ValueSource.Col(col),
                BinaryOp.EQ,
                ValueSource.Lit(intersection.first())
            ))
        } else if (intersection.isNotEmpty()) {
            // Multiple values -> IN with intersection
            toAdd.add(Atom.BinaryAtom(
                ValueSource.Col(col),
                BinaryOp.IN,
                ValueSource.Lit(LiteralValue.ListLit(intersection.toList()))
            ))
        }
        // Empty intersection -> contradiction, caught by isContradictory
    }

    if (toRemove.isEmpty() && toAdd.isEmpty()) return clause

    val result = (atoms - toRemove) + toAdd
    return Clause(result)
}

// Rule 2 & 5: Subsumption elimination
// If c1's atoms are a subset of c2's atoms, c1 subsumes c2 (c1 is more general)
// Remove the subsumed (more specific) clause
private fun eliminateSubsumedClauses(clauses: List<Clause>): List<Clause> {
    if (clauses.size <= 1) return clauses

    val result = mutableListOf<Clause>()
    for (i in clauses.indices) {
        var subsumed = false
        for (j in clauses.indices) {
            if (i == j) continue
            // clauses[j] subsumes clauses[i] if atoms(j) ⊆ atoms(i)
            if (clauses[j].atoms.isNotEmpty() && clauses[j].atoms.all { it in clauses[i].atoms }) {
                if (clauses[j].atoms.size < clauses[i].atoms.size ||
                    (clauses[j].atoms.size == clauses[i].atoms.size && j < i)) {
                    subsumed = true
                    break
                }
            }
            // Empty clause subsumes everything
            if (clauses[j].atoms.isEmpty() && clauses[i].atoms.isNotEmpty()) {
                subsumed = true
                break
            }
        }
        if (!subsumed) result.add(clauses[i])
    }
    return result
}
