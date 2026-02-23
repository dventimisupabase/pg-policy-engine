package com.pgpe.property

import com.pgpe.ast.*
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*

// --- Identifier generators (safe SQL-like names) ---

val arbIdentifier: Arb<String> = Arb.stringPattern("[a-z][a-z0-9_]{0,9}")

val arbColumnName: Arb<String> = Arb.element(
    "id", "tenant_id", "name", "email", "status", "created_at",
    "project_id", "user_id", "is_deleted", "path", "body", "title"
)

val arbTableName: Arb<String> = Arb.element(
    "users", "projects", "tasks", "comments", "files", "teams", "roles"
)

val arbSessionKey: Arb<String> = Arb.element(
    "app.tenant_id", "app.user_id", "app.role"
)

// --- Literal generators ---

val arbStringLit: Arb<LiteralValue> = Arb.string(1..10, Codepoint.alphanumeric())
    .map { LiteralValue.StringLit(it) }

val arbIntLit: Arb<LiteralValue> = Arb.long(-1000L..1000L)
    .map { LiteralValue.IntLit(it) }

val arbBoolLit: Arb<LiteralValue> = Arb.boolean()
    .map { LiteralValue.BoolLit(it) }

val arbScalarLiteral: Arb<LiteralValue> = Arb.choice(arbStringLit, arbIntLit, arbBoolLit)

val arbListLit: Arb<LiteralValue> = Arb.list(arbScalarLiteral, 1..5)
    .map { LiteralValue.ListLit(it) }

val arbLiteral: Arb<LiteralValue> = Arb.choice(arbStringLit, arbIntLit, arbBoolLit)

// --- ValueSource generators ---

val arbCol: Arb<ValueSource> = arbColumnName.map { ValueSource.Col(it) }
val arbSession: Arb<ValueSource> = arbSessionKey.map { ValueSource.Session(it) }
val arbLitSource: Arb<ValueSource> = arbLiteral.map { ValueSource.Lit(it) }

val arbValueSource: Arb<ValueSource> = Arb.choice(arbCol, arbSession, arbLitSource)

// --- Operator generators ---

val arbEqOp: Arb<BinaryOp> = Arb.element(BinaryOp.EQ, BinaryOp.NEQ)

val arbComparisonOp: Arb<BinaryOp> = Arb.element(
    BinaryOp.EQ, BinaryOp.NEQ, BinaryOp.LT, BinaryOp.GT, BinaryOp.LTE, BinaryOp.GTE
)

val arbUnaryOp: Arb<UnaryOp> = Arb.element(UnaryOp.IS_NULL, UnaryOp.IS_NOT_NULL)

// --- Atom generators ---

val arbBinaryAtom: Arb<Atom> = Arb.bind(arbCol, arbComparisonOp, arbValueSource) { left, op, right ->
    Atom.BinaryAtom(left, op, right)
}

val arbEqAtom: Arb<Atom> = Arb.bind(arbColumnName, arbEqOp, arbValueSource) { col, op, right ->
    Atom.BinaryAtom(ValueSource.Col(col), op, right)
}

val arbColSessionAtom: Arb<Atom> = Arb.bind(arbColumnName, arbSessionKey) { col, key ->
    Atom.BinaryAtom(ValueSource.Col(col), BinaryOp.EQ, ValueSource.Session(key))
}

val arbUnaryAtom: Arb<Atom> = Arb.bind(arbCol, arbUnaryOp) { source, op ->
    Atom.UnaryAtom(source, op)
}

val arbInAtom: Arb<Atom> = Arb.bind(arbColumnName, arbListLit) { col, list ->
    Atom.BinaryAtom(ValueSource.Col(col), BinaryOp.IN, ValueSource.Lit(list))
}

val arbRelationship: Arb<Relationship> = Arb.bind(
    arbColumnName, arbTableName, arbColumnName
) { sourceCol, targetTable, targetCol ->
    Relationship(null, sourceCol, targetTable, targetCol)
}

val arbSimpleAtom: Arb<Atom> = Arb.choice(arbBinaryAtom, arbUnaryAtom, arbInAtom)

// --- Clause and Policy generators ---

val arbClause: Arb<Clause> = Arb.list(arbSimpleAtom, 1..4)
    .map { Clause(it.toSet()) }

val arbPolicyType: Arb<PolicyType> = Arb.element(PolicyType.PERMISSIVE, PolicyType.RESTRICTIVE)

val arbCommands: Arb<Set<Command>> = Arb.subsequence(Command.entries.toList())
    .filter { it.isNotEmpty() }
    .map { it.toSet() }

val arbSelector: Arb<Selector> = Arb.choice(
    arbColumnName.map { Selector.HasColumn(it) },
    arbTableName.map { Selector.Named(it) },
    Arb.constant(Selector.All)
)

val arbPolicyName: Arb<String> = Arb.stringPattern("[a-z][a-z_]{2,15}")

val arbPolicy: Arb<Policy> = Arb.bind(
    arbPolicyName,
    arbPolicyType,
    arbCommands,
    arbSelector,
    Arb.list(arbClause, 1..3)
) { name, type, commands, selector, clauses ->
    Policy(name, type, commands, selector, clauses)
}

val arbPolicySet: Arb<PolicySet> = Arb.list(arbPolicy, 1..5)
    .map { PolicySet(it) }

// --- Schema metadata generators (for compiler tests) ---

fun arbSchemaMetadata(tableNames: List<String>? = null): Arb<SchemaMetadata> {
    val names = tableNames ?: listOf("users", "projects", "tasks", "comments", "files")
    return Arb.constant(SchemaMetadata(
        names.map { name ->
            TableMetadata(
                name = name,
                schema = "public",
                columns = listOf(
                    ColumnInfo("id", "text"),
                    ColumnInfo("tenant_id", "text"),
                    ColumnInfo("name", "text"),
                    ColumnInfo("is_deleted", "bool")
                )
            )
        }
    ))
}

// Generator for policies that will match at least one table (for meaningful compilation tests)
val arbCompilablePolicy: Arb<Policy> = Arb.bind(
    arbPolicyName,
    arbPolicyType,
    arbCommands,
    Arb.choice(
        Arb.constant(Selector.HasColumn("tenant_id")),
        Arb.constant(Selector.All),
        Arb.element("users", "projects", "tasks").map { Selector.Named(it) }
    ),
    Arb.list(
        Arb.list(arbEqAtom, 1..3).map { Clause(it.toSet()) },
        1..2
    )
) { name, type, commands, selector, clauses ->
    Policy(name, type, commands, selector, clauses)
}
