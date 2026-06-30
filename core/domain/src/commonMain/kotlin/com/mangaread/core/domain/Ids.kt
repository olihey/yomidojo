package com.mangaread.core.domain

import okio.ByteString.Companion.encodeUtf8

/**
 * Deterministic, FROZEN library identity (PLAN.md §5, §18).
 *
 *   id = hex( SHA-256( source_id + " " + normalizeLocator(raw) ) )[0..15]   // first 16 bytes
 *
 * This function and its inputs MUST NOT change. A different hash re-keys every
 * row in the DB and orphans reading progress, sync, and metadata matches. If it
 * ever truly must change, that is an explicit migration with a re-key + progress
 * remap — never a silent swap.
 */
fun deterministicId(sourceId: String, rawLocator: String): String {
    val normalized = normalizeLocator(rawLocator)
    return "$sourceId $normalized".encodeUtf8().sha256().substring(0, 16).hex()
}

/**
 * Locator normalization — part of the frozen ID definition:
 *   Unicode NFC → unify path separators to '/' → trim.
 * Case is preserved (filesystems differ); only the byte-level instability that a
 * single device introduces is removed.
 */
fun normalizeLocator(raw: String): String =
    normalizeNfc(raw).replace('\\', '/').trim()

/** Platform NFC normalization. JVM uses java.text.Normalizer; iOS actual stubbed until bring-up. */
expect fun normalizeNfc(input: String): String
