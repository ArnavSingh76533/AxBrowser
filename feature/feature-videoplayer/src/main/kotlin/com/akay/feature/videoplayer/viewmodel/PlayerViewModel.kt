package com.akay.feature.videoplayer.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.akay.feature.videoplayer.PendingMediaPlay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class PlayerUiState(
    val videoUrl: String = "",
    val title: String = "",
    val isPlaying: Boolean = false,
    val playbackPosition: Long = 0,
    val duration: Long = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    val playbackSpeed: Float = 1.0f,
    val isAudio: Boolean = false,
    val isMuted: Boolean = false,
    val isEnded: Boolean = false,
    /** 0 = fit, 1 = fill, 2 = zoom (crop). */
    val resizeMode: Int = 0
)

private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "wma")

@HiltViewModel
class PlayerViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    var exoPlayer: ExoPlayer? = null
        private set

    init {
        initializePlayer()
        trackPosition()
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(getApplication()).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = playbackState == Player.STATE_BUFFERING,
                        isEnded = playbackState == Player.STATE_ENDED,
                        duration = this@apply.duration.coerceAtLeast(0)
                    )
                }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    _uiState.value = _uiState.value.copy(playbackPosition = newPosition.positionMs)
                }
            })
        }
    }

    private fun trackPosition() {
        viewModelScope.launch {
            while (true) {
                delay(500)
                exoPlayer?.let { player ->
                    _uiState.value = _uiState.value.copy(
                        playbackPosition = player.currentPosition.coerceAtLeast(0),
                        duration = player.duration.coerceAtLeast(0)
                    )
                }
            }
        }
    }

    fun loadPending() {
        val path  = PendingMediaPlay.filePath
        val title = PendingMediaPlay.title
        if (path.isBlank()) return
        PendingMediaPlay.filePath = ""
        PendingMediaPlay.title    = ""
        loadVideo(path, title)
    }

    fun loadVideo(path: String, title: String = "") {
        val ext = path.substringAfterLast('.', "").substringBefore('?').lowercase()
        val isAudio = ext in AUDIO_EXTS
        _uiState.value = _uiState.value.copy(
            videoUrl = path, title = title, isLoading = true, error = null,
            isAudio = isAudio, isEnded = false
        )
        exoPlayer?.let { player ->
            val uri = when {
                path.startsWith("http://") || path.startsWith("https://") -> Uri.parse(path)
                path.startsWith("file://") -> Uri.parse(path)
                else -> Uri.fromFile(File(path))
            }
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.play()
        }
    }

    fun playPause() {
        exoPlayer?.let { player ->
            if (player.isPlaying) player.pause() else player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    fun seekRelative(offsetMs: Long) {
        exoPlayer?.let { player ->
            val newPos = (player.currentPosition + offsetMs).coerceIn(0, player.duration)
            player.seekTo(newPos)
        }
    }

    fun setSpeed(speed: Float) {
        exoPlayer?.setPlaybackSpeed(speed)
        _uiState.value = _uiState.value.copy(playbackSpeed = speed)
    }

    fun toggleMute() {
        val muted = !_uiState.value.isMuted
        exoPlayer?.volume = if (muted) 0f else 1f
        _uiState.value = _uiState.value.copy(isMuted = muted)
    }

    fun cycleResizeMode() {
        _uiState.value = _uiState.value.copy(resizeMode = (_uiState.value.resizeMode + 1) % 3)
    }

    fun replay() {
        exoPlayer?.let { it.seekTo(0); it.play() }
        _uiState.value = _uiState.value.copy(isEnded = false)
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
    }

    override fun onCleared() {
        super.onCleared()
        release()
    }
}
