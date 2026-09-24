import re

with open("app/src/main/java/com/omymaxz/download/YouTubeDownloadService.kt", "r") as f:
    content = f.read()

# I see what happened. I overwrote part of the file with the replacement but missed removing the old one, leading to duplicated code and syntax errors.
# Let's cleanly replace the entire `muxVideoAndAudio` method.

old_mux_search = re.search(r"    private fun muxVideoAndAudio\(.*?    private fun saveToDownloads\(", content, re.DOTALL)

if old_mux_search:
    new_mux = """    private fun muxVideoAndAudio(videoFile: File, audioFile: File, outFile: File, mimeType: String): Boolean {
        try {
            android.util.Log.d("YouTubeDownloadService", "[YOUTUBE_TRACE] mux started")
            val videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(videoFile.absolutePath)

            val audioExtractor = MediaExtractor()
            audioExtractor.setDataSource(audioFile.absolutePath)

            val format = if (mimeType.contains("webm") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            } else {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }
            val muxer = MediaMuxer(outFile.absolutePath, format)

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var muxerVideoTrackIndex = -1
            var muxerAudioTrackIndex = -1

            // Find Video Track
            for (i in 0 until videoExtractor.trackCount) {
                val trackFormat = videoExtractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoExtractor.selectTrack(i)
                    videoTrackIndex = i
                    muxerVideoTrackIndex = muxer.addTrack(trackFormat)
                    break
                }
            }

            // Find Audio Track
            for (i in 0 until audioExtractor.trackCount) {
                val trackFormat = audioExtractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioExtractor.selectTrack(i)
                    audioTrackIndex = i
                    muxerAudioTrackIndex = muxer.addTrack(trackFormat)
                    break
                }
            }

            if (videoTrackIndex == -1 || audioTrackIndex == -1) {
                android.util.Log.e("YouTubeDownloadService", "[YOUTUBE_TRACE] Required tracks not found for muxing")
                return false
            }

            muxer.start()

            // Copy Video
            val videoBuffer = ByteBuffer.allocate(1024 * 1024)
            val videoBufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val sampleSize = videoExtractor.readSampleData(videoBuffer, 0)
                if (sampleSize < 0) break
                videoBufferInfo.offset = 0
                videoBufferInfo.size = sampleSize
                videoBufferInfo.presentationTimeUs = videoExtractor.sampleTime
                videoBufferInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(muxerVideoTrackIndex, videoBuffer, videoBufferInfo)
                videoExtractor.advance()
            }

            // Copy Audio
            val audioBuffer = ByteBuffer.allocate(512 * 1024)
            val audioBufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val sampleSize = audioExtractor.readSampleData(audioBuffer, 0)
                if (sampleSize < 0) break
                audioBufferInfo.offset = 0
                audioBufferInfo.size = sampleSize
                audioBufferInfo.presentationTimeUs = audioExtractor.sampleTime
                audioBufferInfo.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(muxerAudioTrackIndex, audioBuffer, audioBufferInfo)
                audioExtractor.advance()
            }

            muxer.stop()
            muxer.release()
            videoExtractor.release()
            audioExtractor.release()

            android.util.Log.d("YouTubeDownloadService", "[YOUTUBE_TRACE] mux succeeded")
            return true

        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("YouTubeDownloadService", "[YOUTUBE_TRACE] mux failed: ${e.message}")
            return false
        }
    }

    private fun saveToDownloads("""

    content = content[:old_mux_search.start()] + new_mux + content[old_mux_search.end() - len("    private fun saveToDownloads("):]

    with open("app/src/main/java/com/omymaxz/download/YouTubeDownloadService.kt", "w") as f:
        f.write(content)
    print("Fixed!")
else:
    print("Could not find mux area!")
