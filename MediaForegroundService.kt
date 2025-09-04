class MediaForegroundService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var mediaControlCallback: MediaControlCallback? = null
    private var isPlaying = false

    fun setMediaControlCallback(callback: MediaControlCallback) {
        mediaControlCallback = callback
    }

    fun stopMediaKeepAlive() {
        mediaPlayer?.apply {
            stop()
            release()
        }
        mediaPlayer = null
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "PLAY_PAUSE" -> mediaControlCallback?.onPlayPause()
            "STOP" -> {
                mediaControlCallback?.onStop()
                stopMediaKeepAlive()
            }
        }
        return START_STICKY
    }

    // ... rest of the service implementation
}