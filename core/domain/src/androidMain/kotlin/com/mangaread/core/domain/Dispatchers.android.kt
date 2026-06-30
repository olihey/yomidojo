package com.mangaread.core.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
