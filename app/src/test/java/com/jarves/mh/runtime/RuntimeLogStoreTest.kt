package com.jarves.mh.runtime

import java.nio.file.Files
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLogStoreTest {
    @Test
    fun recordAppendsFooterAndIndexesRun() = withTempDirectory { root ->
        val store = RuntimeLogStore(root)
        val file = store.newLogFile("qwen-code", "demo", "11111111-2222")
        file.writeText("stdout line\n")
        store.record(file, "qwen-code", "demo", 1_000L, exitCode = 53, failed = true, reason = "turn limit")

        val entries = store.entries()
        assertEquals(1, entries.size)
        assertEquals("qwen-code", entries.single().agentId)
        assertEquals("demo", entries.single().projectSlug)
        assertEquals(53, entries.single().exitCode)
        assertTrue(entries.single().failed)
        val text = store.read(entries.single().id).orEmpty()
        assertTrue(text.contains("stdout line"))
        assertTrue(text.contains("exit=53 FAILED"))
        assertTrue(text.contains("turn limit"))
    }

    @Test
    fun recordingTwiceKeepsSingleIndexRow() = withTempDirectory { root ->
        val store = RuntimeLogStore(root)
        val file = store.newLogFile("claude-code", "p", "aaaa")
        file.writeText("out\n")
        store.record(file, "claude-code", "p", 2_000L, exitCode = 0, failed = false)
        store.record(file, "claude-code", "p", 2_000L, exitCode = null, failed = true, reason = "late unwind")

        val entry = store.entries().single()
        assertEquals(2_000L, entry.startedAtMillis)
        assertEquals(0, entry.exitCode)
        assertFalse(entry.failed)
    }

    @Test
    fun entriesNewestFirstAndPruneOldLogs() = withTempDirectory { root ->
        val store = RuntimeLogStore(root)
        val files = (1..14).map { index ->
            val file = store.newLogFile("qwen-code", "p", "id$index")
            file.writeText("run $index\n")
            store.record(file, "qwen-code", "p", startedAtMillis = index * 1_000L, exitCode = 0, failed = false)
            file
        }
        val entries = store.entries()
        assertEquals(12, entries.size)
        assertEquals(14_000L, entries.first().startedAtMillis)
        assertFalse("oldest pruned file is deleted", files[0].exists())
        assertFalse(files[1].exists())
        assertTrue(files[2].exists())
    }

    @Test
    fun entriesIgnoreRowsWhoseFileWasDeleted() = withTempDirectory { root ->
        val store = RuntimeLogStore(root)
        val file = store.newLogFile("deepseek-harness", "p", "gone")
        file.writeText("data\n")
        store.record(file, "deepseek-harness", "p", 5_000L, exitCode = 1, failed = true)
        file.delete()
        assertTrue(store.entries().isEmpty())
        assertNull(store.read(file.name))
    }

    @Test
    fun readStripsAnsiEscapes() = withTempDirectory { root ->
        val store = RuntimeLogStore(root)
        val file = store.newLogFile("qwen-code", "p", "ansi")
        file.writeText("\u001B[31merror line\u001B[0m\n")
        store.record(file, "qwen-code", "p", 7_000L, exitCode = 1, failed = true)
        val text = store.read(file.name).orEmpty()
        assertTrue(text.contains("error line"))
        assertFalse(text.contains("\u001B"))
    }

    @Test
    fun readRejectsForeignIds() = withTempDirectory { root ->
        val store = RuntimeLogStore(root)
        assertNull(store.read("../secrets.log"))
        assertNull(store.read(""))
        assertNull(store.read("notes.txt"))
    }

    @Test
    fun clearRemovesIndexAndLogs() = withTempDirectory { root ->
        val store = RuntimeLogStore(root)
        val file = store.newLogFile("qwen-code", "p", "clr")
        file.writeText("x\n")
        store.record(file, "qwen-code", "p", 9_000L, exitCode = 0, failed = false)
        store.clear()
        assertTrue(store.entries().isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun entryCarriesNotableIssuesButNotConversationJson() = withTempDirectory { root ->
        val store = RuntimeLogStore(root)
        val file = store.newLogFile("qwen-code", "demo", "aaaa1111")
        file.writeText(
            """
            {"type":"assistant","message":{"content":[{"type":"text","text":"The build failed once, retrying"}]}}
            Warning: NPM config key node_version is deprecated
            npm ERR! code ENOENT
            {"type":"result","is_error":true,"result":"Upstream 429 from provider"}
            plain progress chatter without diagnostics
            """.trimIndent() + "\n",
        )
        store.record(file, "qwen-code", "demo", 1_000L, exitCode = 0, failed = false)

        val issues = store.entries().single().errors
        assertTrue(issues.any { it.contains("deprecated") })
        assertTrue(issues.any { it.contains("ENOENT") })
        assertTrue(issues.any { it.contains("Upstream 429") })
        assertFalse(issues.any { it.contains("The build failed once") })
        assertFalse(issues.any { it.contains("plain progress chatter") })
    }

    @Test
    fun failureFooterBecomesAnIssue() = withTempDirectory { root ->
        val store = RuntimeLogStore(root)
        val file = store.newLogFile("dsh", "demo", "bbbb2222")
        file.writeText("nothing notable here\n")
        store.record(file, "dsh", "demo", 1_000L, exitCode = 1, failed = true, reason = "provider 500")

        assertTrue(store.entries().single().errors.any { it.contains("FAILED") })
    }

    private fun <T> withTempDirectory(block: (File) -> T): T {
        val temp = Files.createTempDirectory("mh-runtime-logs").toFile()
        return try {
            block(temp)
        } finally {
            runCatching { temp.deleteRecursively() }
        }
    }
}
