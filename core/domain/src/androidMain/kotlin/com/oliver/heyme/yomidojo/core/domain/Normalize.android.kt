package com.oliver.heyme.yomidojo.core.domain

import java.text.Normalizer

actual fun normalizeNfc(input: String): String =
    Normalizer.normalize(input, Normalizer.Form.NFC)
