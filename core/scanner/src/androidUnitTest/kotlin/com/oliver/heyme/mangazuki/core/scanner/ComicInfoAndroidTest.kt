package com.oliver.heyme.mangazuki.core.scanner

import com.oliver.heyme.mangazuki.core.domain.SourceCapability
import com.oliver.heyme.mangazuki.core.source.ChangeEvent
import com.oliver.heyme.mangazuki.core.source.ChangeSet
import com.oliver.heyme.mangazuki.core.source.MangaSource
import com.oliver.heyme.mangazuki.core.source.RandomAccessHandle
import com.oliver.heyme.mangazuki.core.source.SourceEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Source
import kotlin.test.Test
import kotlin.test.assertEquals

class ComicInfoAndroidTest {

    @Test
    fun `range-capable source reads comicinfo without full open`() = runTest {
        val xml = "<ComicInfo><Series>Dandadan</Series><Title>Chapter 1</Title></ComicInfo>"
        val cbzBytes = cbzWithComicInfo(xml)
        val source = object : MangaSource {
            override val id: String = "test"
            override val capabilities: Set<SourceCapability> =
                setOf(SourceCapability.RANDOM_ACCESS, SourceCapability.RANGE_READ)

            override suspend fun list(path: String): List<SourceEntry> = emptyList()

            override suspend fun open(locator: String): Source =
                error("range path should not fall back to open()")

            override suspend fun openRandomAccess(locator: String): RandomAccessHandle =
                object : RandomAccessHandle {
                    override suspend fun readAt(offset: Long, length: Int): ByteArray {
                        val start = offset.toInt()
                        val end = minOf(cbzBytes.size, start + length)
                        return cbzBytes.copyOfRange(start, end)
                    }

                    override fun close() {}
                }

            override suspend fun changesSince(token: String?): ChangeSet = ChangeSet(emptyList(), null)

            override fun watch(path: String): Flow<ChangeEvent> = emptyFlow()
        }

        assertEquals(xml, readComicInfoXml(source, "cbz", cbzBytes.size.toLong()))
    }
}

private fun cbzWithComicInfo(xml: String): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(out).use { zip ->
        zip.putNextEntry(java.util.zip.ZipEntry("ComicInfo.xml"))
        zip.write(xml.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(java.util.zip.ZipEntry("001.jpg"))
        zip.write(byteArrayOf(1, 2, 3))
        zip.closeEntry()
    }
    return out.toByteArray()
}
