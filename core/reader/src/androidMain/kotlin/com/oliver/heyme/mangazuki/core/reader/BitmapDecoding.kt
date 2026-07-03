package com.oliver.heyme.mangazuki.core.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Size

/** Downsample to the requested target (PLAN.md §8 memory strategy) instead of decoding full-res. */
fun decodeSampled(bytes: ByteArray, maxWidthPx: Int, maxHeightPx: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidthPx && bounds.outHeight / (sample * 2) >= maxHeightPx) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        ?: error("failed to decode page")
}

internal fun decodeBoundsSize(bytes: ByteArray): Size {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    return Size(bounds.outWidth.toFloat(), bounds.outHeight.toFloat())
}
