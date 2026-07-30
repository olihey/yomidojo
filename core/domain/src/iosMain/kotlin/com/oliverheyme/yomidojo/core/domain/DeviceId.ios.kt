package com.oliverheyme.yomidojo.core.domain

import platform.Foundation.NSUUID

actual fun randomUuid(): String = NSUUID().UUIDString()
