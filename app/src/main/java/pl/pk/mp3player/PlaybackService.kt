package pl.pk.mp3player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Usluga odtwarzania oparta na Media3 (ExoPlayer + MediaSession).
 *
 * Odpowiada za:
 *  - odtwarzanie w tle i przy zablokowanym ekranie (foreground service
 *    typu mediaPlayback + powiadomienie generowane przez MediaSessionService),
 *  - sterowanie z ekranu blokady / sluchawek / Android Auto (MediaSession),
 *  - budowe playlisty na podstawie katalogu wybranego przez SAF,
 *  - zapamietywanie ostatnio odtwarzanego utworu i pozycji,
 *  - generowanie NOWEJ permutacji losowej przy kazdym uruchomieniu.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    companion object {
        const val CMD_SET_FOLDER = "pl.pk.mp3player.SET_FOLDER"
        const val CMD_RESHUFFLE = "pl.pk.mp3player.RESHUFFLE"
        const val ARG_TREE_URI = "treeUri"

        private const val TAG = "PlaybackService"
        private const val SAVE_INTERVAL_MS = 3_000L
    }

    private lateinit var player: ExoPlayer
    private lateinit var prefs: Prefs
    private var mediaSession: MediaSession? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    /** Cykliczny zapis stanu, aby przetrwal nawet ubicie procesu przez system. */
    private val saveTicker = object : Runnable {
        override fun run() {
            saveState()
            mainHandler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            // true -> automatyczna obsluga focusu audio (pauza przy rozmowie itp.)
            .setAudioAttributes(audioAttributes, true)
            // pauza przy odlaczeniu sluchawek
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Utrzymanie CPU/WiFi przy zgaszonym ekranie.
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        player.repeatMode = prefs.repeatMode
        player.shuffleModeEnabled = prefs.shuffleEnabled
        player.addListener(playerListener)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(sessionActivity)
            .build()

        // Odtworzenie ostatniego stanu: katalog + ostatni utwor + pozycja.
        prefs.treeUri?.let { saved ->
            loadFolder(Uri.parse(saved), resumeLast = true)
        }

        mainHandler.postDelayed(saveTicker, SAVE_INTERVAL_MS)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Jesli uzytkownik usunal aplikacje z listy zadan i nic nie gra - konczymy.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            saveState()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(saveTicker)
        saveState()
        player.removeListener(playerListener)
        mediaSession?.release()
        mediaSession = null
        player.release()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------ playlista SAF

    /**
     * Skanuje katalog w watku roboczym i ustawia playliste w watku glownym.
     *
     * @param resumeLast true -> wznow od zapamietanego utworu i pozycji.
     */
    private fun loadFolder(treeUri: Uri, resumeLast: Boolean) {
        ioExecutor.execute {
            val tracks = try {
                TrackScanner.scan(applicationContext, treeUri)
            } catch (e: Exception) {
                Log.e(TAG, "Blad skanowania katalogu", e)
                emptyList()
            }

            mainHandler.post {
                if (tracks.isEmpty()) {
                    player.clearMediaItems()
                    return@post
                }

                val items = tracks.map { track ->
                    MediaItem.Builder()
                        .setUri(track.uri)
                        .setMediaId(track.uri.toString())
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(track.title)
                                .setArtist(
                                    if (track.relativePath.isEmpty()) "/"
                                    else track.relativePath
                                )
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .build()
                        )
                        .build()
                }

                var startIndex = 0
                var startPositionMs = 0L
                if (resumeLast) {
                    val lastId = prefs.lastTrackId
                    val idx = items.indexOfFirst { it.mediaId == lastId }
                    if (idx >= 0) {
                        startIndex = idx
                        startPositionMs = prefs.lastPositionMs
                    }
                }

                player.setMediaItems(items, startIndex, startPositionMs)
                player.repeatMode = prefs.repeatMode
                player.shuffleModeEnabled = prefs.shuffleEnabled
                // NOWA permutacja losowa przy kazdym starcie uslugi.
                applyNewShuffleOrder()
                player.prepare()
                // Nie startujemy automatycznie - utwor jest tylko "zaladowany".
                player.playWhenReady = false
            }
        }
    }

    /**
     * Ustawia swieza permutacje kolejki losowej. Ziarno jest zawsze inne niz
     * poprzednio uzyte, co gwarantuje odmienna strategie losowa przy kazdym
     * uruchomieniu aplikacji oraz przy kazdym wlaczeniu trybu losowego.
     */
    private fun applyNewShuffleOrder() {
        val count = player.mediaItemCount
        if (count <= 1) return

        var seed = System.nanoTime() xor Random.nextLong()
        if (seed == prefs.lastShuffleSeed) seed = seed xor 0x5DEECE66DL
        prefs.lastShuffleSeed = seed

        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(count, seed))
    }

    // -------------------------------------------------------------- zapis stanu

    private fun saveState() {
        val item = player.currentMediaItem ?: return
        prefs.lastTrackId = item.mediaId
        prefs.lastPositionMs = player.currentPosition.coerceAtLeast(0L)
    }

    // ---------------------------------------------------------------- listeners

    private val playerListener = object : Player.Listener {

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            prefs.shuffleEnabled = shuffleModeEnabled
            if (shuffleModeEnabled) applyNewShuffleOrder()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            prefs.repeatMode = repeatMode
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            saveState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            saveState()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "Blad odtwarzania: ${error.errorCodeName}", error)
            // Pominiecie uszkodzonego / niedostepnego pliku.
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
            }
        }
    }

    // ------------------------------------------------------- komendy z Activity

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(SessionCommand(CMD_SET_FOLDER, Bundle.EMPTY))
                .add(SessionCommand(CMD_RESHUFFLE, Bundle.EMPTY))
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_SET_FOLDER -> {
                    val uriString = args.getString(ARG_TREE_URI)
                    if (!uriString.isNullOrEmpty()) {
                        loadFolder(Uri.parse(uriString), resumeLast = false)
                    }
                }
                CMD_RESHUFFLE -> applyNewShuffleOrder()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
}
