package com.oliver.heyme.mangazuki.core.domain

import java.util.UUID

actual fun randomUuid(): String = UUID.randomUUID().toString()
