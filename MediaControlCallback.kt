interface MediaControlCallback {
    /**
     * Called when the play/pause button is pressed in the media notification
     */
    fun onPlayPause()

    /**
     * Called when the stop button is pressed in the media notification
     */
    fun onStop()
}