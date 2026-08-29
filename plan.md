1. **Add the Background Audio Action to PiP Window**:
   - In `CustomPlayerActivity.kt`, define a `BroadcastReceiver` to handle a custom PiP action (e.g., `ACTION_BACKGROUND_PLAY`).
   - Create a `RemoteAction` inside `onUserLeaveHint()` using `ic_headset.xml` for the icon and `PendingIntent.getBroadcast` for the action.
   - Inject this `RemoteAction` into `PictureInPictureParams.Builder().setActions(...)`.

2. **Handle the "Listen in Background" Action**:
   - When the user taps the headset icon in PiP mode, the broadcast receiver triggers.
   - It signals `CustomPlayerActivity` to transition from PiP playback to a full background Service playback.
   - Create a `PlaybackService` (extending Media3 `MediaSessionService`).
   - Pass the current `videoUrl`, `title`, and `position` to the service, and then `finish()` the Activity so the video UI completely goes away while the audio continues in the background with a notification.

3. **Create `PlaybackService.kt`**:
   - Implements Media3 `MediaSessionService`.
   - Initializes an ExoPlayer instance, sets the URI, seeks to the passed position, and continues playing audio.
   - A `MediaSession` binds it to standard Android media notifications.

4. **Update `AndroidManifest.xml`**:
   - Register the new `PlaybackService`.
   - Update permissions if necessary (it already has `FOREGROUND_SERVICE_MEDIA_PLAYBACK`).

5. **Test the Flow**:
   - Verify that clicking PiP -> Headset icon closes the activity but leaves a working audio notification playing the stream.
