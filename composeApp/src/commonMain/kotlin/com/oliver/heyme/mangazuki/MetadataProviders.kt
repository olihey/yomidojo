package com.oliver.heyme.mangazuki

import com.oliver.heyme.mangazuki.core.metadata.MetadataProvider

/** Both metadata providers the app knows about (PLAN.md §9.3), keyed by the same
 * [MetadataProviderChoice] used for the global setting and the Fix Metadata dialog's
 * per-lookup override — one place mapping the choice to a concrete instance. */
class MetadataProviders(private val aniList: MetadataProvider, private val kitsu: MetadataProvider) {
    fun get(choice: MetadataProviderChoice): MetadataProvider = when (choice) {
        MetadataProviderChoice.ANILIST -> aniList
        MetadataProviderChoice.KITSU -> kitsu
    }
}
