package dev.musicstream.core.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs [Migrations.MIGRATION_1_2_SQL] on a v1 database built from the
 * exported schema and checks the result matches a fresh v2 database, which
 * is what Room validates on open. Also checks progress is carried over.
 */
class MigrationTest {
    private val schemas = File("schemas/dev.musicstream.core.database.AppDatabase")

    private fun createSql(version: Int): List<String> {
        val db = Json.parseToJsonElement(File(schemas, "$version.json").readText()).jsonObject["database"]!!.jsonObject
        return db["entities"]!!.jsonArray.flatMap { e ->
            val o = e.jsonObject
            val table = o["tableName"]!!.jsonPrimitive.content
            val indices = o["indices"]?.jsonArray.orEmpty().map { it.jsonObject["createSql"]!!.jsonPrimitive.content }
            (listOf(o["createSql"]!!.jsonPrimitive.content) + indices).map { it.replace("\${TABLE_NAME}", table) }
        }
    }

    private fun open(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").apply {
        createStatement().execute("PRAGMA foreign_keys = ON")
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val n = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..n).map { rs.getString(it) }) }
        }
    }

    /** Columns, indices and foreign keys per table: what Room's TableInfo compares. */
    private fun Connection.shape(): Map<String, List<Any>> =
        rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").associate { (t) ->
            t!! to listOf(
                rows("PRAGMA table_info(`$t`)").map { it.drop(1) }.sortedBy { it[0] },
                rows("PRAGMA index_list(`$t`)").map { it[1] to rows("PRAGMA index_info(`${it[1]}`)").map { c -> c[2] } }.sortedBy { it.first },
                rows("PRAGMA foreign_key_list(`$t`)").map { it.drop(2) },
            )
        }

    @Test
    fun migrate1To2() {
        val migrated = open()
        createSql(1).forEach { migrated.exec(it) }
        migrated.exec("INSERT INTO podcasts VALUES ('f', 'Show', 'a', 'd', NULL, 1, 1)")
        migrated.exec("INSERT INTO episodes VALUES ('e1', 'f', 'Ep1', '', 'u1', 2, 600000, NULL, 120000, 0, 50)")
        migrated.exec("INSERT INTO episodes VALUES ('e2', 'f', 'Ep2', '', 'u2', 1, 600000, NULL, 0, 1, 40)")
        migrated.exec("INSERT INTO episodes VALUES ('e3', 'f', 'Ep3', '', 'u3', 0, 600000, NULL, 0, 0, 0)")

        Migrations.MIGRATION_1_2_SQL.forEach { migrated.exec(it) }

        val fresh = open()
        createSql(2).forEach { fresh.exec(it) }
        assertEquals(fresh.shape(), migrated.shape())

        assertEquals(listOf(listOf("e1"), listOf("e2"), listOf("e3")), migrated.rows("SELECT id FROM episodes ORDER BY id"))
        assertEquals(
            listOf(listOf("e1", "Show", "Ep1", "120000", "0", "50"), listOf("e2", "Show", "Ep2", "0", "1", "40")),
            migrated.rows("SELECT episodeId, podcastTitle, title, positionMs, completed, lastPlayedAt FROM episode_history ORDER BY episodeId"),
        )
    }
}
