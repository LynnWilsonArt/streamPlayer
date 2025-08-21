package org.southeastern_radio.audiostreamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// States for the service to communicate to the UI
enum class PlayerServiceState {
    IDLE, BUFFERING, PLAYING, STOPPED, ERROR
}

// Data class to hold all relevant player data for the UI
data class PlayerServiceData(
    val state: PlayerServiceState = PlayerServiceState.IDLE,
    val currentStreamName: String? = null,
    val currentArtist: String? = null,
    val currentTitle: String? = null,
    val errorMessage: String? = null
)

class AudioPlayerService : Service() {

    private val binder = LocalBinder()
    private var exoPlayer: ExoPlayer? = null

    private val _playerServiceDataFlow = MutableStateFlow(PlayerServiceData())
    val playerServiceDataFlow: StateFlow<PlayerServiceData> = _playerServiceDataFlow.asStateFlow()

    private var currentUrl: String? = null
    private var currentName: String? = "Radio Stream"

    companion object {
        const val ACTION_PLAY = "com.example.audiostreamer.ACTION_PLAY"
        const val ACTION_STOP = "com.example.audiostreamer.ACTION_STOP"
        const val EXTRA_STREAM_URL = "com.example.audiostreamer.EXTRA_STREAM_URL"
        const val EXTRA_STREAM_NAME = "com.example.audiostreamer.EXTRA_STREAM_NAME"

        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "AudioPlayerChannel"
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioPlayerService = this@AudioPlayerService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("AudioPlayerService", "onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("AudioPlayerService", "onStartCommand: ${intent?.action}")
        when (intent?.action) {
            ACTION_PLAY -> {
                currentUrl = intent.getStringExtra(EXTRA_STREAM_URL)
                currentName = intent.getStringExtra(EXTRA_STREAM_NAME) ?: "Radio Stream"
                if (!currentUrl.isNullOrBlank()) {
                    initializeAndPlayPlayer(currentUrl!!, currentName!!)
                } else {
                    Log.w("AudioPlayerService", "URL is null or blank for PLAY action.")
                    stopPlayback() // Stop if URL is invalid
                }
            }
            ACTION_STOP -> {
                stopPlayback()
            }
        }
        return START_NOT_STICKY // Don't restart automatically if killed
    }

    private fun initializeAndPlayPlayer(url: String, name: String) {
        exoPlayer?.release() // Release any existing player

        _playerServiceDataFlow.value = PlayerServiceData(
            state = PlayerServiceState.BUFFERING,
            currentStreamName = name
        )
        startForeground(NOTIFICATION_ID, buildNotification("Buffering: $name"))

        exoPlayer = ExoPlayer.Builder(this).build().apply {
            val mediaItem = MediaItem.fromUri(url)
            setMediaItem(mediaItem)
            addListener(playerListener)
            prepare()
            playWhenReady = true // Start playback as soon as prepared
        }
        Log.d("AudioPlayerService", "Player initialized for: $url")
    }

    private fun stopPlayback() {
        Log.d("AudioPlayerService", "Stopping playback")
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        _playerServiceDataFlow.value = PlayerServiceData(
            state = PlayerServiceState.STOPPED,
            currentStreamName = _playerServiceDataFlow.value.currentStreamName // Keep last name for UI
        )
        stopForeground(STOP_FOREGROUND_REMOVE) // Remove notification
        stopSelf() // Stop the service itself if nothing is playing
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val currentData = _playerServiceDataFlow.value
            when (playbackState) {
                Player.STATE_IDLE -> { /* Handled by isPlaying and error */
                }
                Player.STATE_BUFFERING -> {
                    _playerServiceDataFlow.value = currentData.copy(state = PlayerServiceState.BUFFERING)
                    updateNotification("Buffering: ${currentData.currentStreamName ?: "Stream"}")
                }
                Player.STATE_READY -> { /* isPlaying will confirm if it's playing */
                }
                Player.STATE_ENDED -> {
                    _playerServiceDataFlow.value = currentData.copy(
                        state = PlayerServiceState.STOPPED,
                        currentArtist = null,
                        currentTitle = null
                    )
                    updateNotification("Stream Ended: ${currentData.currentStreamName ?: "Stream"}")
                    stopPlayback() // Stop service when stream ends
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val currentData = _playerServiceDataFlow.value
            if (isPlaying) {
                _playerServiceDataFlow.value = currentData.copy(state = PlayerServiceState.PLAYING)
                val artist = currentData.currentArtist
                val title = currentData.currentTitle
                val station = currentData.currentStreamName
                val notificationText = if (!artist.isNullOrBlank() && !title.isNullOrBlank()) {
                    "$artist - $title"
                } else if (!title.isNullOrBlank()) {
                    title
                } else {
                    "Playing"
                }
                updateNotification(station ?: "Streaming", notificationText)
            } else {
                // Not playing, could be paused, stopped, error, or ended.
                // We handle STOPPED more explicitly from user actions or stream end.
                // If it was playing and now it's not, and not an error or explicit stop by user:
                if (currentData.state == PlayerServiceState.PLAYING && exoPlayer?.playerError == null) {
                    // This could be a pause due to transient audio loss, or buffer underrun that recovered quickly
                    // For simplicity, if we don't have an explicit PAUSED state, we might revert to BUFFERING or IDLE
                    // based on exoPlayer.playbackState.
                    // Or, if user didn't stop it, and it's not error/ended, it might be a temporary pause.
                    // For this simple service, if it stops playing and it wasn't a user action or end,
                    // we might need a more robust state (like PAUSED). For now, we'll let
                    // onPlaybackStateChanged handle transitions to BUFFERING or IDLE/STOPPED.
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e("AudioPlayerService", "Player Error: ${error.message}", error)
            _playerServiceDataFlow.value = _playerServiceDataFlow.value.copy(
                state = PlayerServiceState.ERROR,
                errorMessage = error.localizedMessage ?: "Playback failed",
                currentArtist = null,
                currentTitle = null
            )
            updateNotification("Error: ${error.localizedMessage ?: "Cannot play stream"}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf() // Stop service on error
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            val artist = mediaMetadata.artist?.toString()
            val title = mediaMetadata.title?.toString()
            // Some streams might put station name in displayTitle or station
            val stationNameFromMetadata = mediaMetadata.station?.toString()
                ?: mediaMetadata.displayTitle?.toString()?.takeIf { it != title }

            _playerServiceDataFlow.value = _playerServiceDataFlow.value.copy(
                currentArtist = artist,
                currentTitle = title,
                currentStreamName = stationNameFromMetadata ?: _playerServiceDataFlow.value.currentStreamName // Prefer metadata station
            )

            if (_playerServiceDataFlow.value.state == PlayerServiceState.PLAYING) {
                val notificationTitle = stationNameFromMetadata ?: _playerServiceDataFlow.value.currentStreamName ?: "Streaming"
                val notificationText = if (!artist.isNullOrBlank() && !title.isNullOrBlank()) {
                    "$artist - $title"
                } else if (!title.isNullOrBlank()) {
                    title
                } else {
                    "Now Playing"
                }
                updateNotification(notificationTitle, notificationText)
            }
            Log.d("AudioPlayerService", "Metadata - Artist: $artist, Title: $title, Station: $stationNameFromMetadata")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Player Controls",
                NotificationManager.IMPORTANCE_LOW // Low to avoid sound, but still visible
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String, title: String? = null): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val stopIntent = Intent(this, AudioPlayerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title ?: currentName ?: "Audio Streaming")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_play_arrow_24) // Replace with your app's icon
            .setContentIntent(pendingIntent) // Opens the app on click
            .addAction(R.drawable.ic_stop_24, "Stop", stopPendingIntent)
            .setOngoing(true) // Makes the notification non-dismissable by swipe
            .build()
    }

    private fun updateNotification(newTitle: String? = null, newContentText: String? = null) {
        val titleToShow = newTitle ?: _playerServiceDataFlow.value.currentStreamName ?: "Audio Streaming"
        val contentToShow = newContentText ?: when (_playerServiceDataFlow.value.state) {
            PlayerServiceState.PLAYING -> "Playing"
            PlayerServiceState.BUFFERING -> "Buffering..."
            PlayerServiceState.STOPPED -> "Stopped"
            PlayerServiceState.ERROR -> "Error"
            PlayerServiceState.IDLE -> "Idle"
        }
        val notification = buildNotification(contentToShow, titleToShow)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }


    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        Log.d("AudioPlayerService", "onDestroy")
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }
}
