package com.pgpe.parser

import com.pgpe.ast.*
import com.pgpe.parser.generated.PolicyDslBaseVisitor
import com.pgpe.parser.generated.PolicyDslParser

class AstBuilder : PolicyDslBaseVisitor<Any>() {

    override fun visitPolicySet(ctx: PolicyDslParser.PolicySetContext): PolicySet {
        val policies = ctx.policy().map { visitPolicy(it) }
        return PolicySet(policies)
    }

    override fun visitPolicy(ctx: PolicyDslParser.PolicyContext): Policy {
        val name = visitIdentifier(ctx.identifier())
        val type = visitPolicyType(ctx.policyType())
        val commands = visitCommandList(ctx.commandList())
        val selector = visitSelectorClause(ctx.selectorClause())
        val clauses = visitClauseBlock(ctx.clauseBlock())
        return Policy(name, type, commands, selector, clauses)
    }

    override fun visitPolicyType(ctx: PolicyDslParser.PolicyTypeContext): PolicyType {
        return if (ctx.PERMISSIVE() != null) PolicyType.PERMISSIVE else PolicyType.RESTRICTIVE
    }

    override fun visitCommandList(ctx: PolicyDslParser.CommandListContext): Set<Command> {
        return ctx.command().map { visitCommand(it) }.toSet()
    }

    override fun visitCommand(ctx: PolicyDslParser.CommandContext): Command {
        return when {
            ctx.SELECT() != null -> Command.SELECT
            ctx.INSERT() != null -> Command.INSERT
            ctx.UPDATE() != null -> Command.UPDATE
            ctx.DELETE() != null -> Command.DELETE
            else -> throw ParseException("Unknown command: ${ctx.text}")
        }
    }

    override fun visitSelectorClause(ctx: PolicyDslParser.SelectorClauseContext): Selector {
        return visitSelector(ctx.selector())
    }

    private fun visitSelector(ctx: PolicyDslParser.SelectorContext): Selector {
        return when (ctx) {
            is PolicyDslParser.SelectorAndContext ->
                Selector.And(visitSelector(ctx.selector(0)), visitSelector(ctx.selector(1)))
            is PolicyDslParser.SelectorOrContext ->
                Selector.Or(visitSelector(ctx.selector(0)), visitSelector(ctx.selector(1)))
            is PolicyDslParser.SelectorParenContext ->
                visitSelector(ctx.selector())
            is PolicyDslParser.SelectorAllContext ->
                Selector.All
            is PolicyDslParser.SelectorHasColumnContext -> {
                val colName = visitIdentifier(ctx.identifier())
                val type = ctx.columnType()?.text
                Selector.HasColumn(colName, type)
            }
            is PolicyDslParser.SelectorInSchemaContext ->
                Selector.InSchema(visitIdentifier(ctx.identifier()))
            is PolicyDslParser.SelectorNamedContext ->
                Selector.Named(unquote(ctx.stringLiteral().text))
            is PolicyDslParser.SelectorTaggedContext ->
                Selector.Tagged(unquote(ctx.stringLiteral().text))
            else -> throw ParseException("Unknown selector: ${ctx.text}")
        }
    }

    override fun visitClauseBlock(ctx: PolicyDslParser.ClauseBlockContext): List<Clause> {
        return ctx.clause().map { visitClause(it) }
    }

    override fun visitClause(ctx: PolicyDslParser.ClauseContext): Clause {
        val atoms = ctx.atom().map { visitAtom(it) }.toSet()
        return Clause(atoms)
    }

    private fun visitAtom(ctx: PolicyDslParser.AtomContext): Atom {
        return when (ctx) {
            is PolicyDslParser.BinaryAtomContext -> {
                val left = visitValueSource(ctx.valueSource(0))
                val op = visitBinaryOp(ctx.binaryOp())
                val right = visitValueSource(ctx.valueSource(1))
                Atom.BinaryAtom(left, op, right)
            }
            is PolicyDslParser.UnaryAtomContext -> {
                val source = visitValueSource(ctx.valueSource())
                val op = visitUnaryOp(ctx.unaryOp())
                Atom.UnaryAtom(source, op)
            }
            is PolicyDslParser.TraversalAtomRuleContext -> {
                visitTraversalAtom(ctx.traversalAtom())
            }
            else -> throw ParseException("Unknown atom: ${ctx.text}")
        }
    }

    override fun visitTraversalAtom(ctx: PolicyDslParser.TraversalAtomContext): Atom.TraversalAtom {
        val rel = visitRelationship(ctx.relationship())
        val clause = visitClause(ctx.clause())
        return Atom.TraversalAtom(rel, clause)
    }

    override fun visitRelationship(ctx: PolicyDslParser.RelationshipContext): Relationship {
        val source = if (ctx.relSource().UNDERSCORE() != null) null
                     else visitIdentifier(ctx.relSource().identifier())
        val sourceCol = visitIdentifier(ctx.identifier(0))
        val targetTable = visitIdentifier(ctx.identifier(1))
        val targetCol = visitIdentifier(ctx.identifier(2))
        return Relationship(source, sourceCol, targetTable, targetCol)
    }

    private fun visitValueSource(ctx: PolicyDslParser.ValueSourceContext): ValueSource {
        return when (ctx) {
            is PolicyDslParser.ColSourceContext ->
                ValueSource.Col(visitIdentifier(ctx.identifier()))
            is PolicyDslParser.SessionSourceContext ->
                ValueSource.Session(unquote(ctx.stringLiteral().text))
            is PolicyDslParser.LitSourceContext ->
                ValueSource.Lit(visitLiteralValue(ctx.literalValue()))
            is PolicyDslParser.FnSourceContext -> {
                val name = visitIdentifier(ctx.identifier())
                val args = ctx.argList()?.valueSource()?.map { visitValueSource(it) } ?: emptyList()
                ValueSource.Fn(name, args)
            }
            else -> throw ParseException("Unknown value source: ${ctx.text}")
        }
    }

    override fun visitBinaryOp(ctx: PolicyDslParser.BinaryOpContext): BinaryOp {
        return when {
            ctx.EQ() != null -> BinaryOp.EQ
            ctx.NEQ() != null -> BinaryOp.NEQ
            ctx.LT() != null -> BinaryOp.LT
            ctx.GT() != null -> BinaryOp.GT
            ctx.LTE() != null -> BinaryOp.LTE
            ctx.GTE() != null -> BinaryOp.GTE
            ctx.IN() != null -> BinaryOp.IN
            ctx.NOT_IN() != null -> BinaryOp.NOT_IN
            ctx.LIKE() != null -> BinaryOp.LIKE
            ctx.NOT_LIKE() != null -> BinaryOp.NOT_LIKE
            else -> throw ParseException("Unknown binary operator: ${ctx.text}")
        }
    }

    override fun visitUnaryOp(ctx: PolicyDslParser.UnaryOpContext): UnaryOp {
        return when {
            ctx.IS_NULL() != null -> UnaryOp.IS_NULL
            ctx.IS_NOT_NULL() != null -> UnaryOp.IS_NOT_NULL
            else -> throw ParseException("Unknown unary operator: ${ctx.text}")
        }
    }

    private fun visitLiteralValue(ctx: PolicyDslParser.LiteralValueContext): LiteralValue {
        return when (ctx) {
            is PolicyDslParser.StringLiteralValueContext ->
                LiteralValue.StringLit(unquote(ctx.stringLiteral().text))
            is PolicyDslParser.IntegerLiteralValueContext -> {
                val text = ctx.integerLiteral().text
                LiteralValue.IntLit(text.toLong())
            }
            is PolicyDslParser.BooleanLiteralValueContext ->
                LiteralValue.BoolLit(ctx.booleanLiteral().TRUE() != null)
            is PolicyDslParser.NullLiteralValueContext ->
                LiteralValue.NullLit
            is PolicyDslParser.ListLiteralValueContext -> {
                val values = ctx.listLiteral().literalValue().map { visitLiteralValue(it) }
                LiteralValue.ListLit(values)
            }
            else -> throw ParseException("Unknown literal: ${ctx.text}")
        }
    }

    override fun visitIdentifier(ctx: PolicyDslParser.IdentifierContext): String {
        return ctx.text
    }

    private fun unquote(s: String): String {
        return s.removeSurrounding("'").replace("\\'", "'")
    }
}
