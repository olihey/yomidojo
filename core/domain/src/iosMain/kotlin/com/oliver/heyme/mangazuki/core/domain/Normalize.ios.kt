package com.oliver.heyme.mangazuki.core.domain

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCanonicalMapping

// NFC must match the JVM actual byte-for-byte or deterministic IDs diverge across
// devices (PLAN.md §5). precomposedStringWithCanonicalMapping is Unicode NFC.
actual fun normalizeNfc(input: String): String =
    (input as NSString).precomposedStringWithCanonicalMapping
