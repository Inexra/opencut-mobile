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

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedVideo = uri
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Some document providers grant only temporary access. The current session can still use it.
            }
            loadVideo(uri)
            status.text = "Video ready. Enter trim start and end in seconds."
            exportButton.isEnabled = true
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
            text = "＋  Import video"
            setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        }
        root.addView(pickButton, matchWrap())

        playerView = PlayerView(this).apply {
            setBackgroundColor(0xFF050507.toInt())
            useController = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)
            ).apply { topMargin = dp(14); bottomMargin = dp(14) }
        }
        root.addView(playerView)

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

        root.addView(sectionTitle("EDITOR ROADMAP", white))
        val roadmap = listOf(
            "✓  Import and preview one video",
            "✓  Trim start/end and export MP4",
            "○  Multi-clip timeline and drag-to-reorder",
            "○  Text overlays and subtitle tracks",
            "○  Optional local AI captions",
            "○  Optional highlight/Shorts detection"
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

    private fun loadVideo(uri: Uri) {
        player?.release()
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.prepare()
            exo.playWhenReady = false
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
