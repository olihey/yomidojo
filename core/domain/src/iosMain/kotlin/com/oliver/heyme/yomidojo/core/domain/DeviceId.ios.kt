package com.oliver.heyme.yomidojo.core.domain

import platform.Foundation.NSUUID

actual fun randomUuid(): String = NSUUID().UUIDString()
