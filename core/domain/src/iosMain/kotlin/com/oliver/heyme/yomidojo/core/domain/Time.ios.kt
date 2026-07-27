package com.oliver.heyme.yomidojo.core.domain

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.timeIntervalSince1970

actual fun nowEpochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun formatDateTime(epochMillis: Long): String {
    val formatter = NSDateFormatter().apply {
        dateStyle = NSDateFormatterMediumStyle
        timeStyle = NSDateFormatterShortStyle
    }
    return formatter.stringFromDate(NSDate(timeIntervalSince1970 = epochMillis / 1000.0))
}

actual fun formatShortDate(epochMillis: Long): String {
    val formatter = NSDateFormatter().apply { dateFormat = "MMM d" }
    return formatter.stringFromDate(NSDate(timeIntervalSince1970 = epochMillis / 1000.0))
}
