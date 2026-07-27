package com.oliver.heyme.yomidojo.core.domain

import java.util.UUID

actual fun randomUuid(): String = UUID.randomUUID().toString()
