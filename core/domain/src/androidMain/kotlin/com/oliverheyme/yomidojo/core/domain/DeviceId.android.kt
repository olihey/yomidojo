package com.oliverheyme.yomidojo.core.domain

import java.util.UUID

actual fun randomUuid(): String = UUID.randomUUID().toString()
