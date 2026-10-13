package com.aurix.agent.core.mission

import com.aurix.agent.features.missions.HistoryResult
import com.aurix.agent.features.missions.HistoryUser
import com.aurix.agent.features.missions.historyItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

class ConversationHistoryTest {
    private fun m(id: String, t: Long, result: String? = null, error: String? = null, status: MissionStatus = MissionStatus.COMPLETED) =
        MissionEntity(id = id, objective = "obj-$id", createdAt = t, updatedAt = t, status = status, finalResult = result, error = error, conversationId = "c")

    @Test fun historyShowsOnlyEarlierMessagesOldestFirst() {
        val all = listOf(m("a", 1, "ra"), m("b", 2, error = "boom", status = MissionStatus.FAILED), m("c", 3))
        val items = historyItems(all, "c")
        assertEquals(listOf("hu-a", "hr-a", "hu-b", "hr-b"), items.map { it.key })
        assertTrue((items[3] as HistoryResult).failed)
        assertEquals("obj-a", (items[0] as HistoryUser).text)
        assertTrue(historyItems(all, "a").isEmpty())
        assertTrue(historyItems(all, "missing").isEmpty())
    }

    @Test fun migration4to5BackfillsEveryMissionIntoItsOwnConversation() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE missions (id TEXT NOT NULL PRIMARY KEY, objective TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                st.execute("INSERT INTO missions VALUES ('m1', '${"x".repeat(200)}', 10, 20), ('m2', 'hello', 30, 40)")
                st.execute(Migrations.SQL_4_5[0])
                st.execute(Migrations.SQL_4_5_ADD_COLUMN)
                st.execute(Migrations.SQL_4_5_BACKFILL)
                st.execute(Migrations.SQL_4_5[1])
                st.execute(Migrations.SQL_4_5[1]) // INSERT OR IGNORE: safe to repeat
                st.executeQuery("SELECT COUNT(*), MIN(length(title)), MAX(length(title)) FROM conversations").use { r ->
                    r.next(); assertEquals(2, r.getInt(1)); assertEquals(5, r.getInt(2)); assertEquals(80, r.getInt(3))
                }
                st.executeQuery("SELECT COUNT(*) FROM missions WHERE conversationId = id").use { r -> r.next(); assertEquals(2, r.getInt(1)) }
            }
        }
    }
}
