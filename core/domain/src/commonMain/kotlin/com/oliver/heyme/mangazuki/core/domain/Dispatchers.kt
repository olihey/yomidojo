package com.oliver.heyme.mangazuki.core.domain

import kotlinx.coroutines.CoroutineDispatcher

/**
 * `Dispatchers.IO` is JVM-only — not available in commonMain (PLAN.md §13).
 * This single declaration is OWNED BY core:domain; every module routes DB + file
 * IO through it instead of defining its own.
 */
expect val ioDispatcher: CoroutineDispatcher
