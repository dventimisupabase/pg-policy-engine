package com.pgpe.compiler

import com.pgpe.ast.*
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SelectorEvaluatorTest {

    private val metadata = SchemaMetadata(
        tables = listOf(
            TableMetadata("users", "public", listOf(
                ColumnInfo("id", "uuid"),
                ColumnInfo("tenant_id", "uuid"),
                ColumnInfo("email", "text")
            )),
            TableMetadata("projects", "public", listOf(
                ColumnInfo("id", "uuid"),
                ColumnInfo("tenant_id", "uuid"),
                ColumnInfo("is_deleted", "boolean")
            )),
            TableMetadata("tasks", "public", listOf(
                ColumnInfo("id", "uuid"),
                ColumnInfo("project_id", "uuid")
            )),
            TableMetadata("comments", "public", listOf(
                ColumnInfo("id", "uuid"),
                ColumnInfo("tenant_id", "uuid")
            )),
            TableMetadata("files", "public", listOf(
                ColumnInfo("id", "uuid"),
                ColumnInfo("project_id", "uuid")
            ))
        )
    )

    private val evaluator = SelectorEvaluator(metadata)

    @Test
    fun `has_column matches tables with that column`() {
        val tables = evaluator.governedTables(Selector.HasColumn("tenant_id"))
        tables.map { it.name } shouldContainExactlyInAnyOrder listOf("users", "projects", "comments")
    }

    @Test
    fun `named matches exact table name`() {
        val tables = evaluator.governedTables(Selector.Named("tasks"))
        tables.map { it.name } shouldContainExactlyInAnyOrder listOf("tasks")
    }

    @Test
    fun `OR selector unions matches`() {
        val selector = Selector.Or(Selector.Named("tasks"), Selector.Named("files"))
        val tables = evaluator.governedTables(selector)
        tables.map { it.name } shouldContainExactlyInAnyOrder listOf("tasks", "files")
    }

    @Test
    fun `AND selector intersects matches`() {
        val selector = Selector.And(
            Selector.HasColumn("tenant_id"),
            Selector.HasColumn("is_deleted")
        )
        val tables = evaluator.governedTables(selector)
        tables.map { it.name } shouldContainExactlyInAnyOrder listOf("projects")
    }

    @Test
    fun `ALL matches all tables`() {
        val tables = evaluator.governedTables(Selector.All)
        tables.map { it.name } shouldContainExactlyInAnyOrder
            listOf("users", "projects", "tasks", "comments", "files")
    }

    @Test
    fun `has_column with type filters by column type`() {
        val tables = evaluator.governedTables(Selector.HasColumn("is_deleted", "boolean"))
        tables.map { it.name } shouldContainExactlyInAnyOrder listOf("projects")
    }
}
