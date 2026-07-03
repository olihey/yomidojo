package com.oliver.heyme.mangazuki.core.domain

import platform.Foundation.NSUUID

actual fun randomUuid(): String = NSUUID().UUIDString()
