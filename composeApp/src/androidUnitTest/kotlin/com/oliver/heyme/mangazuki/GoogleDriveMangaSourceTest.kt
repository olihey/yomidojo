package com.oliver.heyme.mangazuki

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure half of the Google Drive source (PLAN.md §6.4): config blob, URL builders, DTO→entry
 * mapping. Unlike [OneDriveGraphTest]'s Microsoft Graph equivalent, Drive addresses everything by
 * opaque file id, not a `/`-joined path — these tests are the main thing pinning that shape down. */
class GoogleDriveMangaSourceTest {

    private val json = Json { ignoreUnknownKeys = true }

    // --- GoogleDriveConfig blob ---

    @Test
    fun `config blob round-trips a folder id`() {
        assertEquals(GoogleDriveConfig("1AbCdEfGhIjK"), GoogleDriveConfig.fromBlob(GoogleDriveConfig("1AbCdEfGhIjK").toBlob()))
    }

    @Test
    fun `config blob round-trips the drive root`() {
        assertEquals(GoogleDriveConfig(""), GoogleDriveConfig.fromBlob(GoogleDriveConfig("").toBlob()))
    }

    // --- URL builders ---

    @Test
    fun `blank id addresses the drive root, not a literal empty id`() {
        assertEquals("https://www.googleapis.com/drive/v3/files/root?fields=id%2Cname%2Csize%2CmimeType", driveItemUrl(""))
    }

    @Test
    fun `item URL addresses by id directly, no path segments`() {
        assertEquals(
            "https://www.googleapis.com/drive/v3/files/1AbCdEfGhIjK?fields=id%2Cname%2Csize%2CmimeType",
            driveItemUrl("1AbCdEfGhIjK"),
        )
    }

    @Test
    fun `children URL queries by parent id, excludes trash`() {
        val url = driveChildrenUrl("1AbCdEfGhIjK")
        assertTrue(url.startsWith("https://www.googleapis.com/drive/v3/files?q="))
        // The query fragment is itself URL-encoded -- decode just enough to assert its shape
        // rather than hardcoding percent-escapes for every quote/space in it.
        assertTrue(url.contains("q=%271AbCdEfGhIjK%27+in+parents+and+trashed%3Dfalse"))
        assertTrue(url.contains("pageSize=1000"))
    }

    @Test
    fun `children URL for a blank parent queries the drive root, not a literal empty parent`() {
        assertTrue(driveChildrenUrl("").contains("q=%27root%27+in+parents"))
    }

    // --- DTO parsing + SourceEntry mapping ---

    @Test
    fun `children page parses folders and files into id-addressed entries`() {
        val page = json.decodeFromString<GoogleDriveChildrenPageDto>(
            """
            {
              "files": [
                {"id": "folder-id-1", "name": "A Silent Voice", "mimeType": "application/vnd.google-apps.folder"},
                {"id": "file-id-1", "name": "ch001.cbz", "size": "52428800", "mimeType": "application/zip"}
              ],
              "nextPageToken": "next-page-token"
            }
            """.trimIndent(),
        )

        assertEquals("next-page-token", page.nextPageToken)
        val entries = page.files.map { it.toSourceEntry() }

        val dir = entries[0]
        assertTrue(dir.isDirectory)
        // The locator is the item's own id -- not "parent/name" the way OneDrive's path-based
        // locators are, since Drive has no path addressing to build one from.
        assertEquals("folder-id-1", dir.locator)
        assertEquals("A Silent Voice", dir.name)
        assertNull(dir.size)

        val file = entries[1]
        assertFalse(file.isDirectory)
        assertEquals("file-id-1", file.locator)
        // size arrives as a decimal string in Drive's JSON, not a number.
        assertEquals(52_428_800L, file.size)
    }

    @Test
    fun `folder detection is mimeType-only, not a file-extension guess`() {
        // A real file with no extension (e.g. an extensionless scan folder name) must not be
        // misread as a folder just because it lacks a fileExtension -- mimeType is authoritative.
        val dto = json.decodeFromString<GoogleDriveItemDto>(
            """{"id": "x", "name": "README", "mimeType": "text/plain"}""",
        )
        assertFalse(dto.toSourceEntry().isDirectory)
    }

    @Test
    fun `last page has no nextPageToken`() {
        val page = json.decodeFromString<GoogleDriveChildrenPageDto>("""{"files": []}""")
        assertNull(page.nextPageToken)
    }
}
