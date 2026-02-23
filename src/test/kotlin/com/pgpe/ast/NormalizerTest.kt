package com.pgpe.ast

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NormalizerTest {

    // Helper to build atoms
    private fun colEq(col: String, session: String) =
        Atom.BinaryAtom(ValueSource.Col(col), BinaryOp.EQ, ValueSource.Session(session))

    private fun colEqLit(col: String, value: String) =
        Atom.BinaryAtom(ValueSource.Col(col), BinaryOp.EQ, ValueSource.Lit(LiteralValue.StringLit(value)))

    private fun colEqBool(col: String, value: Boolean) =
        Atom.BinaryAtom(ValueSource.Col(col), BinaryOp.EQ, ValueSource.Lit(LiteralValue.BoolLit(value)))

    private fun colEqInt(col: String, value: Long) =
        Atom.BinaryAtom(ValueSource.Col(col), BinaryOp.EQ, ValueSource.Lit(LiteralValue.IntLit(value)))

    private fun colInList(col: String, values: List<LiteralValue>) =
        Atom.BinaryAtom(ValueSource.Col(col), BinaryOp.IN, ValueSource.Lit(LiteralValue.ListLit(values)))

    private fun makePolicy(clauses: List<Clause>) = Policy(
        name = "test",
        type = PolicyType.PERMISSIVE,
        commands = setOf(Command.SELECT),
        selector = Selector.All,
        clauses = clauses
    )

    private fun makePolicySet(clauses: List<Clause>) =
        PolicySet(listOf(makePolicy(clauses)))

    // --- Rule 1: Idempotence (duplicate atom removal) ---

    @Test
    fun `Rule 1 - duplicate atoms in clause are removed`() {
        val atom = colEq("tenant_id", "app.tenant_id")
        val clause = Clause(setOf(atom, atom))  // already deduplicated by Set
        val ps = makePolicySet(listOf(clause))

        val result = normalize(ps)
        result.policies[0].clauses[0].atoms shouldHaveSize 1
    }

    // --- Rule 2: Absorption ---

    @Test
    fun `Rule 2 - c1 subsumes c1 AND c2 via absorption`() {
        val a1 = colEq("tenant_id", "app.tenant_id")
        val a2 = colEqBool("active", true)

        val c1 = Clause(setOf(a1))            // {tenant_id = session}
        val c2 = Clause(setOf(a1, a2))        // {tenant_id = session, active = true}

        val ps = makePolicySet(listOf(c1, c2))
        val result = normalize(ps)

        // c1 subsumes c2, so c2 should be removed
        result.policies[0].clauses shouldHaveSize 1
        result.policies[0].clauses[0].atoms shouldBe setOf(a1)
    }

    // --- Rule 3: Contradiction elimination ---

    @Test
    fun `Rule 3 - contradictory clause is removed`() {
        val a1 = colEqLit("role", "admin")
        val a2 = colEqLit("role", "viewer")

        val c1 = Clause(setOf(a1, a2))  // role = 'admin' AND role = 'viewer' -> contradiction
        val c2 = Clause(setOf(colEqBool("active", true)))

        val ps = makePolicySet(listOf(c1, c2))
        val result = normalize(ps)

        // c1 is contradictory and should be removed
        result.policies[0].clauses shouldHaveSize 1
        result.policies[0].clauses[0] shouldBe c2
    }

    // --- Rule 4: Tautology detection ---

    @Test
    fun `Rule 4 - col equals itself is a tautology`() {
        val tautology = Atom.BinaryAtom(ValueSource.Col("x"), BinaryOp.EQ, ValueSource.Col("x"))
        val real = colEq("tenant_id", "app.tenant_id")

        val clause = Clause(setOf(tautology, real))
        val ps = makePolicySet(listOf(clause))
        val result = normalize(ps)

        // Tautology should be removed, leaving only the real atom
        result.policies[0].clauses[0].atoms shouldHaveSize 1
        result.policies[0].clauses[0].atoms.first() shouldBe real
    }

    @Test
    fun `Rule 4 - clause with only tautologies becomes empty clause`() {
        val tautology = Atom.BinaryAtom(ValueSource.Col("x"), BinaryOp.EQ, ValueSource.Col("x"))
        val clause = Clause(setOf(tautology))
        val ps = makePolicySet(listOf(clause))
        val result = normalize(ps)

        // An empty clause is TRUE, which subsumes all other clauses
        result.policies[0].clauses shouldHaveSize 1
        result.policies[0].clauses[0].atoms shouldHaveSize 0
    }

    // --- Rule 5: Subsumption elimination in disjunctions ---

    @Test
    fun `Rule 5 - more general clause subsumes more specific`() {
        val a1 = colEq("tenant_id", "app.tenant_id")
        val a2 = colEqBool("active", true)
        val a3 = colEqInt("status", 1)

        val general = Clause(setOf(a1))          // 1 atom
        val specific = Clause(setOf(a1, a2, a3)) // 3 atoms including the one from general

        val ps = makePolicySet(listOf(general, specific))
        val result = normalize(ps)

        result.policies[0].clauses shouldHaveSize 1
        result.policies[0].clauses[0] shouldBe general
    }

    // --- Rule 6: Atom merging (IN intersection) ---

    @Test
    fun `Rule 6 - col eq lit and col IN list merges to col eq lit`() {
        val eq = colEqLit("status", "active")
        val inList = colInList("status", listOf(
            LiteralValue.StringLit("active"),
            LiteralValue.StringLit("pending"),
            LiteralValue.StringLit("closed")
        ))

        val clause = Clause(setOf(eq, inList))
        val ps = makePolicySet(listOf(clause))
        val result = normalize(ps)

        // Should merge to just eq (since 'active' is in the list)
        result.policies[0].clauses[0].atoms shouldHaveSize 1
        result.policies[0].clauses[0].atoms.first() shouldBe eq
    }

    @Test
    fun `Rule 6 - col eq lit not in list means contradiction`() {
        val eq = colEqLit("status", "deleted")
        val inList = colInList("status", listOf(
            LiteralValue.StringLit("active"),
            LiteralValue.StringLit("pending")
        ))
        val other = Clause(setOf(colEqBool("active", true)))

        val clause = Clause(setOf(eq, inList))
        val ps = makePolicySet(listOf(clause, other))
        val result = normalize(ps)

        // 'deleted' NOT IN ['active', 'pending'], so clause is contradictory -> removed
        result.policies[0].clauses shouldHaveSize 1
        result.policies[0].clauses[0] shouldBe other
    }

    @Test
    fun `Rule 6 - IN list intersection`() {
        val in1 = colInList("role", listOf(
            LiteralValue.StringLit("admin"),
            LiteralValue.StringLit("editor"),
            LiteralValue.StringLit("viewer")
        ))
        val in2 = colInList("role", listOf(
            LiteralValue.StringLit("editor"),
            LiteralValue.StringLit("viewer"),
            LiteralValue.StringLit("guest")
        ))

        val clause = Clause(setOf(in1, in2))
        val ps = makePolicySet(listOf(clause))
        val result = normalize(ps)

        // Intersection should be ['editor', 'viewer']
        val atoms = result.policies[0].clauses[0].atoms
        atoms shouldHaveSize 1
        val atom = atoms.first() as Atom.BinaryAtom
        atom.op shouldBe BinaryOp.IN
        val list = (atom.right as ValueSource.Lit).value as LiteralValue.ListLit
        list.values.toSet() shouldBe setOf(
            LiteralValue.StringLit("editor"),
            LiteralValue.StringLit("viewer")
        )
    }

    // --- Section 9.6 Worked Example ---

    @Test
    fun `Section 9_6 worked example - 4 clauses normalize to 2`() {
        val c1 = Clause(setOf(colEq("tenant_id", "tid")))
        val c2 = Clause(setOf(colEq("tenant_id", "tid"), colEqBool("active", true)))
        val c3 = Clause(setOf(colEqLit("role", "admin"), colEqLit("role", "viewer")))
        val c4 = Clause(setOf(colEqBool("is_deleted", false)))

        val ps = makePolicySet(listOf(c1, c2, c3, c4))
        val result = normalize(ps)

        // c3 eliminated by contradiction (role = 'admin' AND role = 'viewer')
        // c2 eliminated by subsumption (c1 subsumes c2)
        // Remaining: {c1, c4}
        result.policies[0].clauses shouldHaveSize 2

        val clauseAtomSets = result.policies[0].clauses.map { it.atoms }.toSet()
        clauseAtomSets shouldBe setOf(
            setOf(colEq("tenant_id", "tid")),
            setOf(colEqBool("is_deleted", false))
        )
    }

    // --- Idempotence property ---

    @Test
    fun `normalize is idempotent`() {
        val c1 = Clause(setOf(colEq("tenant_id", "tid")))
        val c2 = Clause(setOf(colEq("tenant_id", "tid"), colEqBool("active", true)))
        val c3 = Clause(setOf(colEqLit("role", "admin"), colEqLit("role", "viewer")))
        val c4 = Clause(setOf(colEqBool("is_deleted", false)))

        val ps = makePolicySet(listOf(c1, c2, c3, c4))
        val once = normalize(ps)
        val twice = normalize(once)

        once shouldBe twice
    }

    // --- Edge cases ---

    @Test
    fun `empty policy set normalizes to empty`() {
        val ps = PolicySet(emptyList())
        val result = normalize(ps)
        result.policies shouldHaveSize 0
    }

    @Test
    fun `policy with all contradictory clauses has empty clause list`() {
        val c1 = Clause(setOf(colEqLit("a", "x"), colEqLit("a", "y")))
        val c2 = Clause(setOf(colEqLit("b", "1"), colEqLit("b", "2")))

        val ps = makePolicySet(listOf(c1, c2))
        val result = normalize(ps)

        result.policies[0].clauses shouldHaveSize 0
    }
}
