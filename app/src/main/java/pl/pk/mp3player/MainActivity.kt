package pl.pk.mp3player

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.Locale

/**
 * Okno glowne: lista utworow + panel transportu.
 *
 * Komunikacja z usluga odbywa sie przez MediaController (Media3), dzieki czemu
 * ten sam stan widza: UI, powiadomienie i ekran blokady.
 */
@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private lateinit var adapter: TrackAdapter
    private lateinit var recycler: RecyclerView

    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnFolder: ImageButton

    private lateinit var txtTitle: TextView
    private lateinit var txtSubtitle: TextView
    private lateinit var txtPosition: TextView
    private lateinit var txtDuration: TextView
    private lateinit var txtStatus: TextView
    private lateinit var seekBar: SeekBar

    private var userIsSeeking = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            updateProgress()
            uiHandler.postDelayed(this, 500L)
        }
    }

    // --------------------------------------------------------- wybor katalogu

    private val pickFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                Toast.makeText(
                    this,
                    getString(R.string.err_no_permission),
                    Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }

            prefs.treeUri = uri.toString()
            prefs.lastTrackId = null
            prefs.lastPositionMs = 0L

            val args = Bundle().apply { putString(PlaybackService.ARG_TREE_URI, uri.toString()) }
            controller?.sendCustomCommand(
                SessionCommand(PlaybackService.CMD_SET_FOLDER, Bundle.EMPTY),
                args
            )
            txtStatus.text = getString(R.string.status_scanning)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    // ---------------------------------------------------------------- cykl zycia

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        bindViews()
        setupRecycler()
        setupControls()
        requestNotificationPermissionIfNeeded()
        // Usluga jest uruchamiana (bindowana) automatycznie przez MediaController
        // w onStart(); w tryb pierwszoplanowy przechodzi sama przy starcie odtwarzania.
    }

    override fun onStart() {
        super.onStart()
        connectController()
    }

    override fun onStop() {
        uiHandler.removeCallbacks(progressTicker)
        releaseController()
        super.onStop()
    }

    // ------------------------------------------------------------------- widoki

    private fun bindViews() {
        recycler = findViewById(R.id.trackList)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnFolder = findViewById(R.id.btnFolder)
        txtTitle = findViewById(R.id.txtTitle)
        txtSubtitle = findViewById(R.id.txtSubtitle)
        txtPosition = findViewById(R.id.txtPosition)
        txtDuration = findViewById(R.id.txtDuration)
        txtStatus = findViewById(R.id.txtStatus)
        seekBar = findViewById(R.id.seekBar)
    }

    private fun setupRecycler() {
        adapter = TrackAdapter { index ->
            val c = controller ?: return@TrackAdapter
            if (index < 0 || index >= c.mediaItemCount) return@TrackAdapter
            c.seekTo(index, 0L)
            c.play()
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
    }

    private fun setupControls() {
        btnPlayPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
            }
        }

        btnPrev.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            // Ponizej 3 s -> poprzedni utwor, powyzej -> poczatek biezacego.
            if (c.currentPosition > 3000L) c.seekTo(0L) else c.seekToPrevious()
        }

        btnNext.setOnClickListener { controller?.seekToNext() }

        btnShuffle.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            c.shuffleModeEnabled = !c.shuffleModeEnabled
            updateModeButtons()
            Toast.makeText(
                this,
                if (c.shuffleModeEnabled) R.string.toast_shuffle_on else R.string.toast_shuffle_off,
                Toast.LENGTH_SHORT
            ).show()
        }

        btnRepeat.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            c.repeatMode = when (c.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            updateModeButtons()
        }

        btnFolder.setOnClickListener {
            pickFolderLauncher.launch(prefs.treeUri?.let { Uri.parse(it) })
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) txtPosition.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                userIsSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                userIsSeeking = false
                controller?.seekTo(sb?.progress?.toLong() ?: 0L)
            }
        })
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // -------------------------------------------------------------- kontroler

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(playerListener)
                refreshPlaylist()
                updateModeButtons()
                updateNowPlaying()
                uiHandler.post(progressTicker)
            } catch (e: Exception) {
                Toast.makeText(this, R.string.err_service, Toast.LENGTH_LONG).show()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun releaseController() {
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    private val playerListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            refreshPlaylist()
            updateNowPlaying()
        }

        override fun onMediaItemTransition(
            mediaItem: androidx.media3.common.MediaItem?,
            reason: Int
        ) {
            updateNowPlaying()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updatePlayPauseIcon()
            updateProgress()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            updateModeButtons()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            updateModeButtons()
        }
    }

    // ------------------------------------------------------------ aktualizacje

    private fun refreshPlaylist() {
        val c = controller ?: return
        val rows = ArrayList<TrackRow>(c.mediaItemCount)
        for (i in 0 until c.mediaItemCount) {
            val meta = c.getMediaItemAt(i).mediaMetadata
            rows.add(
                TrackRow(
                    title = meta.title?.toString() ?: getString(R.string.unknown_track),
                    subtitle = meta.artist?.toString() ?: ""
                )
            )
        }
        adapter.submit(rows)
        adapter.setCurrentIndex(c.currentMediaItemIndex)

        txtStatus.text = when {
            prefs.treeUri == null -> getString(R.string.status_no_folder)
            rows.isEmpty() -> getString(R.string.status_empty_folder)
            else -> getString(R.string.status_tracks, rows.size)
        }

        if (c.currentMediaItemIndex in rows.indices) {
            recycler.scrollToPosition(c.currentMediaItemIndex)
        }
    }

    private fun updateNowPlaying() {
        val c = controller ?: return
        val meta = c.mediaMetadata
        txtTitle.text = meta.title?.toString() ?: getString(R.string.nothing_playing)
        txtSubtitle.text = meta.artist?.toString() ?: ""
        adapter.setCurrentIndex(c.currentMediaItemIndex)
        updatePlayPauseIcon()
        updateProgress()
    }

    private fun updatePlayPauseIcon() {
        val playing = controller?.isPlaying == true
        btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlayPause.contentDescription =
            getString(if (playing) R.string.cd_pause else R.string.cd_play)
    }

    private fun updateModeButtons() {
        val c = controller ?: return

        val activeColor = ContextCompat.getColor(this, R.color.accent)
        val inactiveColor = ContextCompat.getColor(this, R.color.icon_inactive)

        btnShuffle.setColorFilter(if (c.shuffleModeEnabled) activeColor else inactiveColor)

        when (c.repeatMode) {
            Player.REPEAT_MODE_ALL -> {
                btnRepeat.setImageResource(R.drawable.ic_repeat)
                btnRepeat.setColorFilter(activeColor)
            }
            Player.REPEAT_MODE_ONE -> {
                btnRepeat.setImageResource(R.drawable.ic_repeat_one)
                btnRepeat.setColorFilter(activeColor)
            }
            else -> {
                btnRepeat.setImageResource(R.drawable.ic_repeat)
                btnRepeat.setColorFilter(inactiveColor)
            }
        }
    }

    private fun updateProgress() {
        val c = controller ?: return
        val duration = c.duration
        val position = c.currentPosition

        if (duration > 0) {
            seekBar.max = duration.toInt()
            txtDuration.text = formatTime(duration)
        } else {
            seekBar.max = 0
            txtDuration.text = "--:--"
        }

        if (!userIsSeeking) {
            seekBar.progress = position.coerceIn(0L, maxOf(duration, 0L)).toInt()
            txtPosition.text = formatTime(position)
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms < 0) return "00:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }
}
