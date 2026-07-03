package com.mangaread.core.scanner

import com.mangaread.core.source.MangaSource

// TODO: ZIP + XML sidecar reading -- pending Mac bring-up (PLAN.md §12). Falls back to the
// folder-derived title there, same as before this feature existed.
internal actual suspend fun readComicInfoXml(source: MangaSource, cbzLocator: String): String? = null
