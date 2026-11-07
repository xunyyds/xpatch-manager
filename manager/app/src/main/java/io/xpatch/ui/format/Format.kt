package io.xpatch.ui.format



internal fun readableSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

internal fun formatTime(millis: Long): String {
    if (millis <= 0L) return ""
    return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(millis))
}

internal fun formatDateTime(millis: Long): String {
    if (millis <= 0L) return ""
    return java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))
}
