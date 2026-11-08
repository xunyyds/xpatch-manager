package io.xpatch.data

class DownloadControl {

    @Volatile
    var paused: Boolean = false

    @Volatile
    var cancelled: Boolean = false

    fun checkpoint() {
        if (cancelled) throw DownloadCancelledException()
        while (paused && !cancelled) {
            try {
                Thread.sleep(80)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw DownloadCancelledException()
            }
        }
        if (cancelled) throw DownloadCancelledException()
    }
}

class DownloadCancelledException : Exception("download cancelled")
