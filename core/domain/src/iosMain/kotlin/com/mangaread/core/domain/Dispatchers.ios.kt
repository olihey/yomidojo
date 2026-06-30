package com.mangaread.core.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// iOS has no Dispatchers.IO; a bounded Default pool stands in (PLAN.md §13).
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(64)
