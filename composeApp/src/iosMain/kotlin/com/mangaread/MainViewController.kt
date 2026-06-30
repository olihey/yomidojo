package com.mangaread

import androidx.compose.ui.window.ComposeUIViewController

// Exported into the ComposeApp framework. iosApp/ (the Xcode shell) calls this at
// iOS bring-up — deferred until a Mac is available (PLAN.md §12, §16).
fun MainViewController() = ComposeUIViewController { App() }
