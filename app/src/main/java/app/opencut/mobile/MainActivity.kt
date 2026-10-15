package app.opencut.mobile

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@UnstableApi
class MainActivity : AppCompatActivity() {
    private data class TimelineClip(val uri: Uri, val name: String)
    private val timelineClips = mutableListOf<TimelineClip>()
    private var selectedVideo: Uri? = null
    private var player: ExoPlayer? = null
    private var transformer: Transformer? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private lateinit var exportButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var playerView: PlayerView
    private lateinit var pickButton: Button
    private lateinit var playPauseButton: Button
    private lateinit var speedButton: Button
    private lateinit var timelineContainer: LinearLayout
    private var previewSpeed = 1.0f

    private val pickVideos = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Some providers grant temporary access only.
                }
                if (timelineClips.none { it.uri == uri }) {
                    val name = queryDisplayName(uri) ?: "Clip ${timelineClips.size + 1}"
                    timelineClips.add(TimelineClip(uri, name))
                }
            }
            if (selectedVideo == null && timelineClips.isNotEmpty()) {
                selectTimelineClip(0)
            } else {
                renderTimeline()
                status.text = "Added ${uris.size} video(s) to the timeline. Select a clip to preview and trim it."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(17, 17, 22)
        window.navigationBarColor = android.graphics.Color.rgb(17, 17, 22)
        buildUi()
    }

    private fun buildUi() {
        val bg = 0xFF111116.toInt()
        val panel = 0xFF1B1B24.toInt()
        val white = 0xFFF4F3FA.toInt()
        val muted = 0xFFB8B5C8.toInt()
        val accent = 0xFF8B78FF.toInt()

        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "OpenCut Mobile"
            textSize = 27f
            setTextColor(white)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Native video editing • local-first • open source"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(18))
        })

        pickButton = Button(this).apply {
            text = "＋  Import videos to timeline"
            setOnClickListener { pickVideos.launch(arrayOf("video/*")) }
        }
        root.addView(pickButton, matchWrap())

        root.addView(sectionTitle("TIMELINE CLIPS", white))
        root.addView(TextView(this).apply {
            text = "Import several videos, select one to preview, then reorder or remove clips. Trim/export currently applies to the selected clip."
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(2), 0, dp(8))
        })
        timelineContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(panel)
        }
        root.addView(timelineContainer, matchWrap())
        renderTimeline()

        playerView = PlayerView(this).apply {
            setBackgroundColor(0xFF050507.toInt())
            useController = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)
            ).apply { topMargin = dp(14); bottomMargin = dp(14) }
        }
        root.addView(playerView)

        val playbackRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        playPauseButton = Button(this).apply {
            text = "▶ Play / Pause"
            setOnClickListener {
                val activePlayer = player
                if (activePlayer != null) {
                    if (activePlayer.isPlaying) {
                        activePlayer.pause()
                        text = "▶ Play"
                    } else {
                        activePlayer.play()
                        text = "Ⅱ Pause"
                    }
                } else {
                    status.text = "Import a video first."
                }
            }
        }
        speedButton = Button(this).apply {
            text = "Speed 1×"
            setOnClickListener {
                previewSpeed = when (previewSpeed) {
                    1.0f -> 1.25f
                    1.25f -> 1.5f
                    1.5f -> 2.0f
                    else -> 1.0f
                }
                player?.setPlaybackSpeed(previewSpeed)
                text = "Speed ${previewSpeed}×"
            }
        }
        playbackRow.addView(playPauseButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(4) })
        playbackRow.addView(speedButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(4) })
        root.addView(playbackRow)

        val markerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val markStartButton = Button(this).apply {
            text = "Set start at playhead"
            textSize = 11f
            setOnClickListener { setTrimFromPlayhead(isStart = true) }
        }
        val markEndButton = Button(this).apply {
            text = "Set end at playhead"
            textSize = 11f
            setOnClickListener { setTrimFromPlayhead(isStart = false) }
        }
        markerRow.addView(markStartButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(4) })
        markerRow.addView(markEndButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(4) })
        root.addView(markerRow)

        root.addView(sectionTitle("TRIM RANGE", white))
        root.addView(TextView(this).apply {
            text = "Set the start and end point in seconds. Export creates a new MP4 and leaves the original unchanged."
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(10))
        })

        val trimRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startInput = EditText(this).apply {
            hint = "Start (sec)"
            setHintTextColor(muted)
            setTextColor(white)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
            setText("0")
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(panel)
        }
        endInput = EditText(this).apply {
            hint = "End (sec)"
            setHintTextColor(muted)
            setTextColor(white)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
            setText("60")
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(panel)
        }
        trimRow.addView(startInput, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(6) })
        trimRow.addView(endInput, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(6) })
        root.addView(trimRow)

        exportButton = Button(this).apply {
            text = "Export trimmed MP4"
            isEnabled = false
            setOnClickListener { exportTrim() }
        }
        root.addView(exportButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(8) })

        status = TextView(this).apply {
            text = "Import a video to begin."
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(12), 0, dp(18))
        }
        root.addView(status)

        root.addView(sectionTitle("FEATURE STATUS", white))
        val roadmap = listOf(
            "✓  Import multiple videos into a clip list",
            "✓  Select, reorder, and remove timeline clips",
            "✓  Preview speed and playhead-based trim markers",
            "✓  Trim/export the selected clip as MP4",
            "○  Combined multi-clip export and visual effects",
            "○  Burned-in subtitles and AI speech transcription",
            "○  Automatic highlight/Shorts detection"
        )
        roadmap.forEach { line ->
            root.addView(TextView(this).apply {
                text = line
                textSize = 14f
                setTextColor(if (line.startsWith("✓")) 0xFFB9F4D0.toInt() else muted)
                setPadding(0, dp(5), 0, dp(5))
            })
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun selectTimelineClip(index: Int) {
        if (index !in timelineClips.indices) return
        selectedVideo = timelineClips[index].uri
        loadVideo(timelineClips[index].uri)
        startInput.setText("0")
        endInput.setText("60")
        exportButton.isEnabled = true
        status.text = "Selected: ${timelineClips[index].name}. Set trim points or edit the time fields."
        renderTimeline()
    }

    private fun renderTimeline() {
        if (!::timelineContainer.isInitialized) return
        timelineContainer.removeAllViews()
        if (timelineClips.isEmpty()) {
            timelineContainer.addView(TextView(this).apply {
                text = "No clips yet. Tap Import videos to timeline."
                setTextColor(0xFFB8B5C8.toInt())
                setPadding(dp(4), dp(8), dp(4), dp(8))
            })
            return
        }
        timelineClips.forEachIndexed { index, clip ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                if (clip.uri == selectedVideo) setBackgroundColor(0xFF302B48.toInt())
            }
            val select = Button(this).apply {
                text = "${if (clip.uri == selectedVideo) "● " else ""}${index + 1}. ${clip.name}"
                textSize = 12f
                setOnClickListener { selectTimelineClip(index) }
            }
            row.addView(select, matchWrap())
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val up = Button(this).apply {
                text = "↑"
                isEnabled = index > 0
                setOnClickListener {
                    val item = timelineClips.removeAt(index)
                    timelineClips.add(index - 1, item)
                    renderTimeline()
                }
            }
            val down = Button(this).apply {
                text = "↓"
                isEnabled = index < timelineClips.lastIndex
                setOnClickListener {
                    val item = timelineClips.removeAt(index)
                    timelineClips.add(index + 1, item)
                    renderTimeline()
                }
            }
            val remove = Button(this).apply {
                text = "Remove"
                setOnClickListener {
                    val wasSelected = clip.uri == selectedVideo
                    timelineClips.removeAt(index)
                    if (wasSelected) {
                        player?.release()
                        player = null
                        playerView.player = null
                        selectedVideo = null
                        exportButton.isEnabled = false
                        if (timelineClips.isNotEmpty()) {
                            selectTimelineClip(index.coerceAtMost(timelineClips.lastIndex))
                        } else {
                            status.text = "Timeline is empty. Import a video to continue."
                        }
                    }
                    renderTimeline()
                }
            }
            actions.addView(up, LinearLayout.LayoutParams(0, dp(44), 1f))
            actions.addView(down, LinearLayout.LayoutParams(0, dp(44), 1f))
            actions.addView(remove, LinearLayout.LayoutParams(0, dp(44), 1f))
            row.addView(actions)
            timelineContainer.addView(row)
            if (index < timelineClips.lastIndex) {
                timelineContainer.addView(View(this).apply {
                    setBackgroundColor(0xFF393744.toInt())
                }, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
    }

    private fun loadVideo(uri: Uri) {
        player?.release()
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.prepare()
            exo.playWhenReady = false
            exo.setPlaybackSpeed(previewSpeed)
        }
        renderTimeline()
    }

    private fun setTrimFromPlayhead(isStart: Boolean) {
        val activePlayer = player
        if (selectedVideo == null || activePlayer == null) {
            status.text = "Import a video and wait for its preview to load first."
            return
        }
        val seconds = activePlayer.currentPosition.coerceAtLeast(0L) / 1000.0
        val formatted = String.format(Locale.US, "%.2f", seconds)
        if (isStart) {
            startInput.setText(formatted)
            status.text = "Trim start set to ${formatted}s."
        } else {
            endInput.setText(formatted)
            status.text = "Trim end set to ${formatted}s."
        }
    }

    private fun exportTrim() {
        val uri = selectedVideo ?: return
        val startSec = startInput.text.toString().toDoubleOrNull()
        val endSec = endInput.text.toString().toDoubleOrNull()
        if (startSec == null || endSec == null || startSec < 0 || endSec <= startSec) {
            status.text = "Enter a valid range. End must be greater than start."
            return
        }
        if (endSec > 24 * 60 * 60) {
            status.text = "End time is too large."
            return
        }

        exportButton.isEnabled = false
        pickButton.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Preparing export…"

        val outFile = File(cacheDir, "opencut_export_${System.currentTimeMillis()}.mp4")
        val clipConfig = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs((startSec * 1000).toLong())
            .setEndPositionMs((endSec * 1000).toLong())
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(clipConfig)
            .build()
        val edited = EditedMediaItem.Builder(mediaItem).build()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                try {
                    val savedUri = copyToMovies(outFile)
                    status.text = "Export complete. Saved to Movies/OpenCut Mobile."
                    Toast.makeText(this@MainActivity, "Video exported", Toast.LENGTH_LONG).show()
                    savedUri?.let { status.append("\nSaved file: ${it.lastPathSegment ?: "MP4"}") }
                } catch (e: Exception) {
                    status.text = "Export finished but saving failed: ${e.message}"
                } finally {
                    outFile.delete()
                    resetExportUi()
                }
            }

            override fun onError(
                composition: androidx.media3.transformer.Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                outFile.delete()
                status.text = "Export failed: ${exportException.message ?: "Unsupported media or codec error"}"
                resetExportUi()
            }
        }

        transformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(listener)
            .build()

        try {
            transformer?.start(edited, outFile.absolutePath)
            pollProgress()
        } catch (e: Exception) {
            outFile.delete()
            status.text = "Could not start export: ${e.message}"
            resetExportUi()
        }
    }

    private fun pollProgress() {
        val active = transformer ?: return
        val holder = ProgressHolder()
        val state = active.getProgress(holder)
        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
            progress.progress = holder.progress
            status.text = "Exporting… ${holder.progress}%"
        }
        if (!exportButton.isEnabled && transformer != null) {
            handler.postDelayed({ pollProgress() }, 400)
        }
    }

    private fun copyToMovies(source: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "OpenCut_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/OpenCut Mobile")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Android could not create the output file")
        contentResolver.openOutputStream(uri)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not open output stream")
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun resetExportUi() {
        transformer = null
        exportButton.isEnabled = selectedVideo != null
        pickButton.isEnabled = true
        progress.visibility = View.GONE
    }

    private fun sectionTitle(text: String, color: Int) = TextView(this).apply {
        this.text = text
        textSize = 12f
        letterSpacing = 0.12f
        setTextColor(color)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
        transformer?.cancel()
        transformer = null
        super.onDestroy()
    }
}
