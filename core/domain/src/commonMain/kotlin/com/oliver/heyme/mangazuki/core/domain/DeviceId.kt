package com.oliver.heyme.mangazuki.core.domain

/** A fresh random identifier, used once per app install to tag reading-progress writes for
 * cross-device sync (PLAN.md §10) — a deterministic tiebreak when two records share the same
 * `updatedAt`, never part of matching identity itself. Not `kotlin.uuid.Uuid`: still
 * `@ExperimentalUuidApi` at this project's pinned Kotlin version. */
expect fun randomUuid(): String
