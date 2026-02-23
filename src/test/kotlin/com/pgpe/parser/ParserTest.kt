package com.pgpe.parser

import com.pgpe.ast.*
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class ParserTest {

    private fun parseResource(path: String): PolicySet {
        val source = this::class.java.classLoader.getResource(path)!!.readText()
        val result = parse(source, path)
        result.errors shouldHaveSize 0
        return result.policySet!!
    }

    @Test
    fun `parse all 3 Appendix A1 policies`() {
        val ps = parseResource("golden/appendix-a1.policy")
        ps.policies shouldHaveSize 3
    }

    @Test
    fun `tenant_isolation policy has correct structure`() {
        val ps = parseResource("golden/appendix-a1.policy")
        val p = ps.policies[0]

        p.name shouldBe "tenant_isolation"
        p.type shouldBe PolicyType.PERMISSIVE
        p.commands shouldBe setOf(Command.SELECT, Command.INSERT, Command.UPDATE, Command.DELETE)
        p.selector.shouldBeInstanceOf<Selector.HasColumn>()
        (p.selector as Selector.HasColumn).name shouldBe "tenant_id"

        p.clauses shouldHaveSize 1
        p.clauses[0].atoms shouldHaveSize 1

        val atom = p.clauses[0].atoms.first()
        atom.shouldBeInstanceOf<Atom.BinaryAtom>()
        val binary = atom as Atom.BinaryAtom
        binary.left shouldBe ValueSource.Col("tenant_id")
        binary.op shouldBe BinaryOp.EQ
        binary.right shouldBe ValueSource.Session("app.tenant_id")
    }

    @Test
    fun `tenant_isolation_via_project policy has traversal atom and compound selector`() {
        val ps = parseResource("golden/appendix-a1.policy")
        val p = ps.policies[1]

        p.name shouldBe "tenant_isolation_via_project"
        p.type shouldBe PolicyType.PERMISSIVE

        // Compound OR selector
        p.selector.shouldBeInstanceOf<Selector.Or>()
        val orSelector = p.selector as Selector.Or
        orSelector.left shouldBe Selector.Named("tasks")
        orSelector.right shouldBe Selector.Named("files")

        // Single clause with traversal atom
        p.clauses shouldHaveSize 1
        p.clauses[0].atoms shouldHaveSize 1

        val atom = p.clauses[0].atoms.first()
        atom.shouldBeInstanceOf<Atom.TraversalAtom>()
        val traversal = atom as Atom.TraversalAtom

        traversal.relationship.sourceTable shouldBe null  // wildcard
        traversal.relationship.sourceCol shouldBe "project_id"
        traversal.relationship.targetTable shouldBe "projects"
        traversal.relationship.targetCol shouldBe "id"

        traversal.clause.atoms shouldHaveSize 1
        val innerAtom = traversal.clause.atoms.first()
        innerAtom.shouldBeInstanceOf<Atom.BinaryAtom>()
        val innerBinary = innerAtom as Atom.BinaryAtom
        innerBinary.left shouldBe ValueSource.Col("tenant_id")
        innerBinary.op shouldBe BinaryOp.EQ
        innerBinary.right shouldBe ValueSource.Session("app.tenant_id")
    }

    @Test
    fun `soft_delete policy is restrictive with single command`() {
        val ps = parseResource("golden/appendix-a1.policy")
        val p = ps.policies[2]

        p.name shouldBe "soft_delete"
        p.type shouldBe PolicyType.RESTRICTIVE
        p.commands shouldBe setOf(Command.SELECT)
        p.selector.shouldBeInstanceOf<Selector.HasColumn>()
        (p.selector as Selector.HasColumn).name shouldBe "is_deleted"

        p.clauses shouldHaveSize 1
        p.clauses[0].atoms shouldHaveSize 1

        val atom = p.clauses[0].atoms.first()
        atom.shouldBeInstanceOf<Atom.BinaryAtom>()
        val binary = atom as Atom.BinaryAtom
        binary.left shouldBe ValueSource.Col("is_deleted")
        binary.op shouldBe BinaryOp.EQ
        binary.right shouldBe ValueSource.Lit(LiteralValue.BoolLit(false))
    }

    @Test
    fun `parse all operators`() {
        val source = """
            POLICY test_ops PERMISSIVE FOR SELECT SELECTOR ALL
            CLAUSE col(a) = lit(1)
                AND col(b) != lit(2)
                AND col(c) < lit(3)
                AND col(d) > lit(4)
                AND col(e) <= lit(5)
                AND col(f) >= lit(6)
                AND col(g) IN lit([1, 2, 3])
                AND col(h) NOT IN lit([4, 5])
                AND col(i) LIKE lit('pattern%')
                AND col(j) NOT LIKE lit('bad%')
        """.trimIndent()

        val result = parse(source)
        result.success shouldBe true
        val clause = result.policySet!!.policies[0].clauses[0]
        clause.atoms shouldHaveSize 10
    }

    @Test
    fun `parse unary operators`() {
        val source = """
            POLICY test_unary PERMISSIVE FOR SELECT SELECTOR ALL
            CLAUSE col(a) IS NULL
            OR CLAUSE col(b) IS NOT NULL
        """.trimIndent()

        val result = parse(source)
        result.success shouldBe true
        val ps = result.policySet!!
        ps.policies[0].clauses shouldHaveSize 2

        val atom1 = ps.policies[0].clauses[0].atoms.first()
        atom1.shouldBeInstanceOf<Atom.UnaryAtom>()
        (atom1 as Atom.UnaryAtom).op shouldBe UnaryOp.IS_NULL

        val atom2 = ps.policies[0].clauses[1].atoms.first()
        atom2.shouldBeInstanceOf<Atom.UnaryAtom>()
        (atom2 as Atom.UnaryAtom).op shouldBe UnaryOp.IS_NOT_NULL
    }

    @Test
    fun `parse multi-clause policy (OR)`() {
        val source = """
            POLICY multi PERMISSIVE FOR SELECT SELECTOR ALL
            CLAUSE col(a) = lit(1)
            OR CLAUSE col(b) = lit(2)
            OR CLAUSE col(c) = lit(3)
        """.trimIndent()

        val result = parse(source)
        result.success shouldBe true
        result.policySet!!.policies[0].clauses shouldHaveSize 3
    }

    @Test
    fun `parse literal types`() {
        val source = """
            POLICY literals PERMISSIVE FOR SELECT SELECTOR ALL
            CLAUSE col(s) = lit('hello')
                AND col(i) = lit(42)
                AND col(n) = lit(-7)
                AND col(b) = lit(true)
                AND col(x) = lit(null)
        """.trimIndent()

        val result = parse(source)
        result.success shouldBe true

        val atoms = result.policySet!!.policies[0].clauses[0].atoms.toList()
        val rights = atoms.filterIsInstance<Atom.BinaryAtom>().map { it.right }

        rights.any { it == ValueSource.Lit(LiteralValue.StringLit("hello")) } shouldBe true
        rights.any { it == ValueSource.Lit(LiteralValue.IntLit(42)) } shouldBe true
        rights.any { it == ValueSource.Lit(LiteralValue.IntLit(-7)) } shouldBe true
        rights.any { it == ValueSource.Lit(LiteralValue.BoolLit(true)) } shouldBe true
        rights.any { it == ValueSource.Lit(LiteralValue.NullLit) } shouldBe true
    }

    @Test
    fun `parse compound AND selector`() {
        val source = """
            POLICY compound PERMISSIVE FOR SELECT
            SELECTOR has_column(tenant_id) AND named('users')
            CLAUSE col(a) = lit(1)
        """.trimIndent()

        val result = parse(source)
        result.success shouldBe true
        val selector = result.policySet!!.policies[0].selector
        selector.shouldBeInstanceOf<Selector.And>()
        val and = selector as Selector.And
        and.left shouldBe Selector.HasColumn("tenant_id")
        and.right shouldBe Selector.Named("users")
    }

    @Test
    fun `error on invalid syntax`() {
        val result = parse("NOT A VALID POLICY")
        result.success shouldBe false
        result.errors.size shouldNotBe 0
    }

    @Test
    fun `error on incomplete policy`() {
        val result = parse("POLICY test PERMISSIVE")
        result.success shouldBe false
        result.errors.size shouldNotBe 0
    }

    @Test
    fun `parse has_column with type`() {
        val source = """
            POLICY typed PERMISSIVE FOR SELECT
            SELECTOR has_column(tenant_id, uuid)
            CLAUSE col(a) = lit(1)
        """.trimIndent()

        val result = parse(source)
        result.success shouldBe true
        val selector = result.policySet!!.policies[0].selector
        selector.shouldBeInstanceOf<Selector.HasColumn>()
        (selector as Selector.HasColumn).name shouldBe "tenant_id"
        selector.type shouldBe "uuid"
    }

    @Test
    fun `parse column to column comparison`() {
        val source = """
            POLICY col_compare PERMISSIVE FOR SELECT SELECTOR ALL
            CLAUSE col(a) = col(b)
        """.trimIndent()

        val result = parse(source)
        result.success shouldBe true
        val atom = result.policySet!!.policies[0].clauses[0].atoms.first()
        atom.shouldBeInstanceOf<Atom.BinaryAtom>()
        val binary = atom as Atom.BinaryAtom
        binary.left shouldBe ValueSource.Col("a")
        binary.right shouldBe ValueSource.Col("b")
    }
}
