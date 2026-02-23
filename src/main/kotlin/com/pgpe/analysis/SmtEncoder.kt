package com.pgpe.analysis

import com.microsoft.z3.*
import com.pgpe.ast.*

class SmtEncoder(private val ctx: Context) {

    private val sortMap = mutableMapOf<String, Sort>()
    private val varCache = mutableMapOf<String, Expr<*>>()

    fun freshVar(name: String): Expr<*> {
        return varCache.getOrPut(name) {
            ctx.mkConst(name, ctx.mkUninterpretedSort(ctx.mkSymbol("Val")))
        }
    }

    private fun getValSort(): Sort {
        return sortMap.getOrPut("Val") {
            ctx.mkUninterpretedSort(ctx.mkSymbol("Val"))
        }
    }

    fun encodeValueSource(
        vs: ValueSource,
        sessionPrefix: String,
        tablePrefix: String
    ): Expr<*> {
        return when (vs) {
            is ValueSource.Col -> freshVar("${tablePrefix}_col_${vs.name}")
            is ValueSource.Session -> freshVar("${sessionPrefix}_session_${vs.key}")
            is ValueSource.Lit -> encodeLiteral(vs.value)
            is ValueSource.Fn -> freshVar("fn_${vs.name}_${vs.hashCode()}")
        }
    }

    fun encodeLiteral(lit: LiteralValue): Expr<*> {
        return when (lit) {
            is LiteralValue.StringLit -> freshVar("lit_str_${lit.value}")
            is LiteralValue.IntLit -> freshVar("lit_int_${lit.value}")
            is LiteralValue.BoolLit -> freshVar("lit_bool_${lit.value}")
            is LiteralValue.NullLit -> freshVar("lit_null")
            is LiteralValue.ListLit -> freshVar("lit_list_${lit.hashCode()}")
        }
    }

    fun encodeAtom(
        atom: Atom,
        sessionPrefix: String,
        tablePrefix: String
    ): BoolExpr {
        return when (atom) {
            is Atom.BinaryAtom -> encodeBinaryAtom(atom, sessionPrefix, tablePrefix)
            is Atom.UnaryAtom -> encodeUnaryAtom(atom, sessionPrefix, tablePrefix)
            is Atom.TraversalAtom -> encodeTraversalAtom(atom, sessionPrefix, tablePrefix)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun encodeBinaryAtom(
        atom: Atom.BinaryAtom,
        sessionPrefix: String,
        tablePrefix: String
    ): BoolExpr {
        val left = encodeValueSource(atom.left, sessionPrefix, tablePrefix)
        val right = encodeValueSource(atom.right, sessionPrefix, tablePrefix)

        return when (atom.op) {
            BinaryOp.EQ -> ctx.mkEq(left, right)
            BinaryOp.NEQ -> ctx.mkNot(ctx.mkEq(left, right))
            BinaryOp.LT, BinaryOp.GT, BinaryOp.LTE, BinaryOp.GTE -> {
                // For ordering ops, use uninterpreted - conservatively model as unconstrained
                // This is sound: if we can't prove isolation with this model, we report UNKNOWN
                val ordering = ctx.mkBoolConst("ordering_${left}_${atom.op}_${right}_${System.identityHashCode(atom)}")
                ordering
            }
            BinaryOp.IN -> {
                // IN is disjunction: left = v1 OR left = v2 OR ...
                if (atom.right is ValueSource.Lit && (atom.right as ValueSource.Lit).value is LiteralValue.ListLit) {
                    val list = ((atom.right as ValueSource.Lit).value as LiteralValue.ListLit).values
                    if (list.isEmpty()) return ctx.mkFalse()
                    val disjuncts = list.map { ctx.mkEq(left, encodeLiteral(it)) }.toTypedArray()
                    ctx.mkOr(*disjuncts)
                } else {
                    ctx.mkBoolConst("in_${System.identityHashCode(atom)}")
                }
            }
            BinaryOp.NOT_IN -> {
                if (atom.right is ValueSource.Lit && (atom.right as ValueSource.Lit).value is LiteralValue.ListLit) {
                    val list = ((atom.right as ValueSource.Lit).value as LiteralValue.ListLit).values
                    if (list.isEmpty()) return ctx.mkTrue()
                    val conjuncts = list.map { ctx.mkNot(ctx.mkEq(left, encodeLiteral(it))) }.toTypedArray()
                    ctx.mkAnd(*conjuncts)
                } else {
                    ctx.mkBoolConst("not_in_${System.identityHashCode(atom)}")
                }
            }
            BinaryOp.LIKE, BinaryOp.NOT_LIKE -> {
                ctx.mkBoolConst("like_${System.identityHashCode(atom)}")
            }
        }
    }

    private fun encodeUnaryAtom(
        atom: Atom.UnaryAtom,
        sessionPrefix: String,
        tablePrefix: String
    ): BoolExpr {
        val colName = if (atom.source is ValueSource.Col) (atom.source as ValueSource.Col).name
                      else atom.source.hashCode().toString()
        val nullFlag = ctx.mkBoolConst("${tablePrefix}_is_null_$colName")

        return when (atom.op) {
            UnaryOp.IS_NULL -> nullFlag
            UnaryOp.IS_NOT_NULL -> ctx.mkNot(nullFlag)
        }
    }

    private fun encodeTraversalAtom(
        atom: Atom.TraversalAtom,
        sessionPrefix: String,
        tablePrefix: String
    ): BoolExpr {
        // Traversal: EXISTS (SELECT 1 FROM target WHERE target.tc = source.sc AND inner_clause)
        val targetPrefix = "traversal_${atom.relationship.targetTable}"

        // Join condition: target.tc = source.sc
        val sourceCol = freshVar("${tablePrefix}_col_${atom.relationship.sourceCol}")
        val targetCol = freshVar("${targetPrefix}_col_${atom.relationship.targetCol}")
        val joinCond = ctx.mkEq(sourceCol, targetCol)

        // Inner clause condition
        val innerCond = encodeClause(atom.clause, sessionPrefix, targetPrefix)

        return ctx.mkAnd(joinCond, innerCond)
    }

    fun encodeClause(
        clause: Clause,
        sessionPrefix: String,
        tablePrefix: String
    ): BoolExpr {
        if (clause.atoms.isEmpty()) return ctx.mkTrue()
        val conjuncts = clause.atoms.map { encodeAtom(it, sessionPrefix, tablePrefix) }.toTypedArray()
        return ctx.mkAnd(*conjuncts)
    }

    fun encodeClauses(
        clauses: List<Clause>,
        sessionPrefix: String,
        tablePrefix: String
    ): BoolExpr {
        if (clauses.isEmpty()) return ctx.mkFalse()  // No permissive clauses = no access
        val disjuncts = clauses.map { encodeClause(it, sessionPrefix, tablePrefix) }.toTypedArray()
        return ctx.mkOr(*disjuncts)
    }
}
