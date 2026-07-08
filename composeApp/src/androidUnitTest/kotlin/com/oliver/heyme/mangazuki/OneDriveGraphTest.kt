package com.oliver.heyme.mangazuki

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure half of the OneDrive source (PLAN.md §6.3): config blob, URL builders, DTO→entry
 * mapping. Network behavior is covered separately in [OneDriveMangaSourceTest]. */
class OneDriveGraphTest {

    private val json = Json { ignoreUnknownKeys = true }

    // --- OneDriveConfig blob ---

    @Test
    fun `config blob round-trips`() {
        assertEquals(OneDriveConfig("Documents/Manga"), OneDriveConfig.fromBlob(OneDriveConfig("Documents/Manga").toBlob()))
    }

    @Test
    fun `config blob round-trips the drive root`() {
        assertEquals(OneDriveConfig(""), OneDriveConfig.fromBlob(OneDriveConfig("").toBlob()))
    }

    // --- URL builders ---

    @Test
    fun `root item and children URLs use the plain root form`() {
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root", graphItemUrl(""))
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root/children", graphChildrenUrl(""))
    }

    @Test
    fun `nested paths use the colon addressing form`() {
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root:/Manga/Series:", graphItemUrl("Manga/Series"))
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root:/Manga/Series:/children", graphChildrenUrl("Manga/Series"))
    }

    @Test
    fun `path segments are percent-encoded`() {
        // Space, hash, percent, plus, apostrophe, unicode -- the characters that break naive URLs.
        assertEquals("A%20Silent%20Voice", encodeGraphPathSegment("A Silent Voice"))
        assertEquals("Ch%20%231", encodeGraphPathSegment("Ch #1"))
        assertEquals("50%25%20off", encodeGraphPathSegment("50% off"))
        assertEquals("a%2Bb", encodeGraphPathSegment("a+b"))
        assertEquals("Komi%27s", encodeGraphPathSegment("Komi's"))
        assertEquals("%E3%82%A2%E3%82%AD%E3%83%A9", encodeGraphPathSegment("アキラ"))
        assertEquals(
            "https://graph.microsoft.com/v1.0/me/drive/root:/My%20Manga/Ch%20%231:",
            graphItemUrl("My Manga/Ch #1"),
        )
    }

    // --- DTO parsing + SourceEntry mapping ---

    @Test
    fun `children page parses folders and files into entries`() {
        val page = json.decodeFromString<DriveChildrenPageDto>(
            """
            {
              "value": [
                {"name": "A Silent Voice", "size": 123456, "folder": {"childCount": 7}, "eTag": "\"etag-dir\""},
                {"name": "ch001.cbz", "size": 52428800, "file": {"mimeType": "application/zip"}, "eTag": "\"etag-file\""}
              ],
              "@odata.nextLink": "https://graph.microsoft.com/v1.0/next-page"
            }
            """.trimIndent(),
        )

        assertEquals("https://graph.microsoft.com/v1.0/next-page", page.nextLink)
        val entries = page.value.map { it.toSourceEntry("Manga") }

        val dir = entries[0]
        assertTrue(dir.isDirectory)
        assertEquals("Manga/A Silent Voice", dir.locator)
        // Graph reports a recursive size for folders too -- must be nulled, dirs have no file size.
        assertNull(dir.size)
        assertEquals("\"etag-dir\"", dir.changeToken)

        val file = entries[1]
        assertFalse(file.isDirectory)
        assertEquals("Manga/ch001.cbz", file.locator)
        // Required for files: CbzArchive silently buffers whole archives without a size.
        assertEquals(52_428_800L, file.size)
        assertEquals("\"etag-file\"", file.changeToken)
    }

    @Test
    fun `root-level entries use bare names as locators`() {
        val dto = json.decodeFromString<DriveItemDto>("""{"name": "Manga", "folder": {}}""")
        assertEquals("Manga", dto.toSourceEntry("").locator)
    }

    @Test
    fun `download URL annotation is captured`() {
        val dto = json.decodeFromString<DriveItemDto>(
            """{"name": "ch001.cbz", "size": 1, "file": {}, "@microsoft.graph.downloadUrl": "https://public.dn.files.1drv.com/x"}""",
        )
        assertEquals("https://public.dn.files.1drv.com/x", dto.downloadUrl)
    }

    @Test
    fun `last page has no nextLink`() {
        val page = json.decodeFromString<DriveChildrenPageDto>("""{"value": []}""")
        assertNull(page.nextLink)
    }
}
