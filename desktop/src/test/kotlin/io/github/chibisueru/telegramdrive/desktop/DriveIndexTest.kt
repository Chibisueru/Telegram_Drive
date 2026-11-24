package io.github.chibisueru.telegramdrive.desktop

import io.github.chibisueru.telegramdrive.desktop.data.Drive
import io.github.chibisueru.telegramdrive.desktop.data.DriveIndex
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveIndexTest {
    private fun m(vararg pairs: Pair<String, String>) = mapOf(*pairs)

    @Test
    fun `change made only on the other device is taken`() {
        val out = Drive.merge3(m("a" to "old"), m("a" to "old"), m("a" to "renamed there"))
        assertEquals("renamed there", out["a"])
    }

    @Test
    fun `change made only here is kept`() {
        val out = Drive.merge3(m("a" to "old"), m("a" to "renamed here"), m("a" to "old"))
        assertEquals("renamed here", out["a"])
    }

    @Test
    fun `both changed - this device wins`() {
        val out = Drive.merge3(m("a" to "old"), m("a" to "here"), m("a" to "there"))
        assertEquals("here", out["a"])
    }

    @Test
    fun `new items from both sides are kept`() {
        val out = Drive.merge3(m(), m("mine" to "1"), m("theirs" to "2"))
        assertEquals(setOf("mine", "theirs"), out.keys)
    }

    @Test
    fun `deleted on the other device and untouched here is deleted`() {
        val out = Drive.merge3(m("a" to "x"), m("a" to "x"), m())
        assertNull(out["a"])
    }

    @Test
    fun `deleted here stays deleted`() {
        val out = Drive.merge3(m("a" to "x"), m(), m("a" to "x"))
        assertNull(out["a"])
    }

    /** Exactly what the Android app (org.json) writes, including explicit nulls and no "purged". */
    @Test
    fun `reads the Android app's index`() {
        val android = """
            {"version":2,"exportedAt":1764000000000,
             "folders":[{"id":"f1","parentId":null,"name":"Photos","createdAt":1,"deletedAt":null,"trashedRoot":false,"starred":true}],
             "files":[{"id":"d1","folderId":"f1","name":"a.jpg","mimeType":"image/jpeg","size":123,"createdAt":2,"messageId":99,
                       "remoteId":"BQAC","thumbRemoteId":null,"deletedAt":null,"trashedRoot":false,"starred":false}]}
        """.trimIndent()
        val idx = Json { ignoreUnknownKeys = true }.decodeFromString<DriveIndex>(android)
        assertEquals("Photos", idx.folders.single().name)
        assertTrue(idx.folders.single().starred)
        assertEquals("f1", idx.files.single().folderId)
        assertEquals(99L, idx.files.single().messageId)
        assertTrue(idx.purged.isEmpty())
    }

    /** The Android app reads null-able fields with isNull(): they must be written as explicit nulls. */
    @Test
    fun `writes explicit nulls like the Android app expects`() {
        val text = Json { explicitNulls = true; encodeDefaults = true }.encodeToString(
            DriveIndex(folders = listOf(io.github.chibisueru.telegramdrive.desktop.data.Folder("f", null, "Top")))
        )
        assertTrue("\"parentId\":null" in text, text)
        assertTrue("\"version\":2" in text, text)
    }
}
