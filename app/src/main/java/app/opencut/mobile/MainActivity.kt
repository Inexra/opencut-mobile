package app.opencut.mobile

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.content.ClipData
import android.view.DragEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.SeekBar
import android.media.MediaMetadataRetriever
import android.text.TextWatcher
import android.text.Editable
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
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
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Effects
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.TextOverlay
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
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@UnstableApi
class MainActivity : AppCompatActivity() {
    private data class TimelineClip(val uri: Uri, val name: String)
private data class CaptionCue(val start: Double, val end: Double, val text: String)
    private val timelineClips = mutableListOf<TimelineClip>()
    private var selectedVideo: Uri? = null
    private var player: ExoPlayer? = null
    private var transformer: Transformer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val playheadUpdater = object : Runnable {
        override fun run() {
            val active = player
            if (::playheadSeekBar.isInitialized && active != null && !playheadSeekBar.isPressed) {
                val duration = active.duration
                if (duration > 0L) {
                    playheadSeekBar.progress = ((active.currentPosition.toDouble() / duration) * 1000.0).toInt().coerceIn(0, 1000)
                    playheadTime.text = "${formatClock(active.currentPosition)} / ${formatClock(duration)}"
                } else {
                    playheadTime.text = "${formatClock(active.currentPosition)} / --:--"
                }
            }
            handler.postDelayed(this, 300)
        }
    }

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
    private lateinit var playheadSeekBar: SeekBar
    private lateinit var playheadTime: TextView
    private lateinit var aspectRatioButton: Button
    private lateinit var rotateButton: Button
    private lateinit var motionButton: Button
    private lateinit var titleAnimationButton: Button
    private lateinit var trimFineButton: Button
    private lateinit var highlightButton: Button
    private lateinit var muteButton: Button
    private lateinit var overlayInput: EditText
    private lateinit var stickerInput: EditText
    private lateinit var captionInput: EditText
    private lateinit var captionPreview: TextView
    private lateinit var captionStyleButton: Button
    private lateinit var captionToolsStatus: TextView
    private lateinit var aiTopicInput: EditText
    private lateinit var aiDescriptionInput: EditText
    private lateinit var aiStatus: TextView
    private lateinit var editorScroll: ScrollView
    private lateinit var editorRoot: LinearLayout
    private var captionStyle = "Classic"
    private var previewSpeed = 1.0f
    private var outputRatio: String = "Original"
    private var rotationDegrees = 0f
    private var motionPreset = "Off"
    private var motionScale = 1f
    private var motionRotation = 0f
    private var titleAnimation = "Fade in"
    private val generatedCaptionCues = mutableListOf<CaptionCue>()
    private var muteAudio = false

    private val createSrtDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) {
            try {
                val caption = captionInput.text.toString().trim()
                val srt = if (generatedCaptionCues.isNotEmpty()) {
                    generatedCaptionCues.mapIndexed { index, cue ->
                        "${index + 1}\\n${toSrtTime(cue.start)} --> ${toSrtTime(cue.end)}\\n${cue.text.replace("-->", "→")}\\n"
                    }.joinToString("\\n")
                } else {
                    if (caption.isEmpty()) {
                        status.text = "Generate timed captions or type a caption before exporting subtitles."
                        return@registerForActivityResult
                    }
                    val start = startInput.text.toString().toDoubleOrNull() ?: 0.0
                    val end = endInput.text.toString().toDoubleOrNull() ?: 0.0
                    if (end <= start) {
                        status.text = "Set a valid trim range before exporting SRT."
                        return@registerForActivityResult
                    }
                    "1\\n${toSrtTime(start)} --> ${toSrtTime(end)}\\n${caption.replace("-->", "→")}\\n"
                }
                contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(srt) }
                status.text = if (generatedCaptionCues.isNotEmpty()) "Exported ${generatedCaptionCues.size} timed subtitle cues." else "Subtitle file exported."
            } catch (e: Exception) {
                status.text = "Could not save SRT: ${e.message}"
            }
        }
    }

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
        handler.post(playheadUpdater)
    }

    private fun buildUi() {
        val bg = 0xFF111116.toInt()
        val panel = 0xFF1B1B24.toInt()
        val white = 0xFFF4F3FA.toInt()
        val muted = 0xFFB8B5C8.toInt()
        val accent = 0xFF8B78FF.toInt()

        val screenRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        editorScroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        editorRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
        }
        editorScroll.addView(editorRoot)
        screenRoot.addView(editorScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(screenRoot)
        val root = editorRoot

        val appHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appHeader.addView(TextView(this).apply {
            text = "OpenCut"
            textSize = 24f
            setTextColor(white)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        appHeader.addView(Button(this).apply {
            text = "⚙ Settings"
            setOnClickListener { showSettingsMenu() }
        }, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(appHeader)
        root.addView(TextView(this).apply {
            text = "Studio  •  New project"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(18))
        })

        val toolDockScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(0xFF191820.toInt()) }
        val toolDock = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(6), dp(6), dp(6), dp(6)) }
        val toolItems = listOf("▶\nPreview" to "VIDEO PREVIEW", "✂\nEdit" to "TRIM RANGE", "♫\nAudio" to "CANVAS & AUDIO", "T\nText" to "CAPTION OVERLAY & STYLE PREVIEW", "CC\nCaptions" to "CAPTION OVERLAY & STYLE PREVIEW", "✦\nAI" to "GEMINI AI ASSISTANT", "⇧\nExport" to "EXPORT")
        val toolButtons = mutableListOf<TextView>()
        toolItems.forEach { item ->
            val tool = TextView(this).apply {
                text = item.first
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(white)
                setPadding(dp(4), dp(5), dp(4), dp(5))
                setLineSpacing(0f, 0.92f)
                setBackgroundColor(if (item.first == "▶\nPreview") accent else 0xFF292633.toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    toolButtons.forEach { button ->
                        val selected = button === this
                        button.animate().cancel()
                        button.animate()
                            .scaleX(if (selected) 1.06f else 1f)
                            .scaleY(if (selected) 1.06f else 1f)
                            .alpha(if (selected) 1f else 0.82f)
                            .setDuration(180L)
                            .setInterpolator(android.view.animation.OvershootInterpolator(1.15f))
                            .start()
                        button.setBackgroundColor(if (selected) accent else 0xFF292633.toInt())
                    }
                    animateToolTap(this)
                    showEditorPanel(item.first)
                }
            }
            toolButtons.add(tool)
            toolDock.addView(tool, LinearLayout.LayoutParams(dp(66), dp(50)).apply { rightMargin = dp(4) })
        }
        toolDockScroll.addView(toolDock)
        screenRoot.addView(toolDockScroll, LinearLayout.LayoutParams(-1, dp(58)))

        pickButton = Button(this).apply {
            text = "＋  Import videos to timeline"
            setOnClickListener { pickVideos.launch(arrayOf("video/*")) }
        }
        root.addView(pickButton, matchWrap())



        playerView = PlayerView(this).apply {
            setBackgroundColor(0xFF050507.toInt())
            useController = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)
            ).apply { topMargin = dp(14); bottomMargin = dp(14) }
        }
        val previewFrame = FrameLayout(this).apply {
            setBackgroundColor(0xFF050507.toInt())
            layoutParams = LinearLayout.LayoutParams(-1, dp(220)).apply {
                topMargin = dp(14)
                bottomMargin = dp(14)
            }
        }
        playerView.layoutParams = FrameLayout.LayoutParams(-1, -1)
        previewFrame.addView(playerView)
        captionPreview = TextView(this).apply {
            text = ""
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
            setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
        }
        previewFrame.addView(captionPreview, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
            bottomMargin = dp(16)
        })
        root.addView(sectionTitle("VIDEO PREVIEW", white))
        root.addView(previewFrame)

        root.addView(sectionTitle("TIMELINE CLIPS", white))
        root.addView(TextView(this).apply {
            text = "Import several videos, select one to preview and trim, then reorder or remove clips. Export joins the ordered timeline with straight cuts; trim applies to the selected clip."
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(2), 0, dp(8))
        })
        val timelineScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(panel) }
        timelineContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(panel)
        }
        timelineScroll.addView(timelineContainer)
        root.addView(timelineScroll, LinearLayout.LayoutParams(-1, dp(150)))
        renderTimeline()

        val playheadRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }
        playheadTime = TextView(this).apply {
            text = "00:00 / --:--"
            textSize = 12f
            setTextColor(muted)
        }
        playheadSeekBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    val active = player ?: return
                    val duration = active.duration
                    if (duration > 0L) {
                        val position = (duration * value / 1000L).coerceIn(0L, duration)
                        playheadTime.text = "${formatClock(position)} / ${formatClock(duration)}"
                        if (fromUser) active.seekTo(position)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        playheadRow.addView(playheadTime)
        playheadRow.addView(playheadSeekBar, LinearLayout.LayoutParams(-1, dp(36)))
        root.addView(playheadRow)

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
        trimFineButton = Button(this).apply {
            text = "Fine trim  ±0.1s"
            textSize = 11f
            setOnClickListener {
                val options = arrayOf("±1.0 second", "±0.1 second", "±0.01 second")
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Trim adjustment step")
                    .setItems(options) { _, which ->
                        val step = when (which) { 0 -> 1.0; 1 -> 0.1; else -> 0.01 }
                        fun adjust(input: EditText, delta: Double) {
                            val value = (input.text.toString().toDoubleOrNull() ?: 0.0) + delta
                            input.setText(String.format(Locale.US, "%.2f", value.coerceAtLeast(0.0)))
                        }
                        android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("Adjust which trim point?")
                            .setItems(arrayOf("Start −", "Start +", "End −", "End +")) { _, action ->
                                when (action) {
                                    0 -> adjust(startInput, -step)
                                    1 -> adjust(startInput, step)
                                    2 -> adjust(endInput, -step)
                                    3 -> adjust(endInput, step)
                                }
                                status.text = "Trim point adjusted by ${String.format(Locale.US, "%.2f", step)} seconds. Check that end is after start."
                            }.show()
                    }.show()
            }
        }
        root.addView(trimFineButton, matchWrap())

        root.addView(sectionTitle("CANVAS & AUDIO", white))
        val editOptions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        aspectRatioButton = Button(this).apply {
            text = "Ratio: Original"
            textSize = 11f
            setOnClickListener {
                val choices = arrayOf("Original", "YouTube 16:9", "Shorts 9:16", "Square 1:1")
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Export aspect ratio")
                    .setItems(choices) { _, which ->
                        outputRatio = choices[which]
                        text = "Ratio: " + when (outputRatio) {
                            "YouTube 16:9" -> "16:9"
                            "Shorts 9:16" -> "9:16"
                            "Square 1:1" -> "1:1"
                            else -> "Original"
                        }
                    }.show()
            }
        }
        rotateButton = Button(this).apply {
            text = "Rotate 0°"
            textSize = 11f
            setOnClickListener {
                rotationDegrees = (rotationDegrees + 90f) % 360f
                text = "Rotate ${rotationDegrees.toInt()}°"
            }
        }
        motionButton = Button(this).apply {
            text = "Motion: Off"
            textSize = 11f
            setOnClickListener {
                val choices = arrayOf("Off", "Zoom crop", "Cinematic crop", "Tilt")
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Export motion look")
                    .setMessage("Applies a subtle framing transform to the exported video. These are static framing looks, not animated keyframes.")
                    .setItems(choices) { _, which ->
                        motionPreset = choices[which]
                        when (motionPreset) {
                            "Zoom crop" -> { motionScale = 1.08f; motionRotation = 0f }
                            "Cinematic crop" -> { motionScale = 1.04f; motionRotation = 0f }
                            "Tilt" -> { motionScale = 1.04f; motionRotation = 1.5f }
                            else -> { motionScale = 1f; motionRotation = 0f }
                        }
                        text = "Motion: " + motionPreset
                        status.text = "Applied " + motionPreset + " framing look. It will be used in the next export."
                    }.show()
            }
        }
        editOptions.addView(aspectRatioButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(3) })
        editOptions.addView(rotateButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        editOptions.addView(motionButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(3) })
        root.addView(editOptions)

        muteButton = Button(this).apply {
            text = "Audio: On"
            setOnClickListener {
                muteAudio = !muteAudio
                text = if (muteAudio) "Audio: Muted" else "Audio: On"
            }
        }
        root.addView(muteButton, matchWrap())
        overlayInput = EditText(this).apply {
            hint = "Optional text overlay (burned into export)"
            setHintTextColor(muted)
            setTextColor(white)
            setSingleLine(false)
            minLines = 1
            maxLines = 3
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(panel)
        }
        root.addView(overlayInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        stickerInput = EditText(this).apply {
            hint = "Sticker / emoji overlay (e.g. 🔥 😂 ⭐)"
            setHintTextColor(muted)
            setTextColor(white)
            setSingleLine(true)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(panel)
        }
        root.addView(stickerInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        titleAnimationButton = Button(this).apply {
            text = "Title animation: Fade in"
            setOnClickListener {
                val choices = arrayOf("Fade in", "Pop in", "Slide up", "Static")
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Title / sticker animation")
                    .setItems(choices) { _, which ->
                        titleAnimation = choices[which]
                        text = "Title animation: $titleAnimation"
                        status.text = "Selected $titleAnimation. Animation is applied during export to text and emoji overlays."
                    }.show()
            }
        }
        root.addView(titleAnimationButton, matchWrap())
        root.addView(TextView(this).apply {
            text = "Text and emoji overlays can use a simple export-time entrance animation."
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(4))
        })

        root.addView(sectionTitle("CAPTION OVERLAY & STYLE PREVIEW", white))
        captionInput = EditText(this).apply {
            hint = "Type a caption to preview and burn into export"
            setHintTextColor(muted)
            setTextColor(white)
            setSingleLine(false)
            minLines = 2
            maxLines = 4
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(panel)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updateCaptionPreview()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        root.addView(captionInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        captionStyleButton = Button(this).apply {
            text = "Caption style: Classic"
            setOnClickListener {
                val styles = arrayOf("Classic", "Bold Yellow", "Boxed", "Minimal", "Karaoke")
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Choose caption style")
                    .setItems(styles) { _, which ->
                        captionStyle = styles[which]
                        text = "Caption style: $captionStyle"
                        updateCaptionPreview()
                    }.show()
            }
        }
        root.addView(captionStyleButton, matchWrap())
        root.addView(TextView(this).apply {
            text = "The caption preview appears over the video above. The selected style is burned into the exported MP4."
            textSize = 12f
            setTextColor(muted)
        })
        val captionActionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val srtButton = Button(this).apply {
            text = "Export SRT"
            textSize = 11f
            setOnClickListener {
                if (captionInput.text.toString().trim().isEmpty()) {
                    status.text = "Type a caption first."
                } else {
                    createSrtDocument.launch("OpenCut-subtitles.srt")
                }
            }
        }
        val smartPresetButton = Button(this).apply {
            text = "Smart edit presets"
            textSize = 11f
            setOnClickListener { showSmartEditPresets() }
        }
        captionActionRow.addView(srtButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(4) })
        captionActionRow.addView(smartPresetButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(4) })
        root.addView(captionActionRow)
        captionToolsStatus = TextView(this).apply {
            text = "Smart presets apply editing settings locally; they do not call a cloud AI service."
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(captionToolsStatus)

        root.addView(sectionTitle("GEMINI AI ASSISTANT", white))
        root.addView(TextView(this).apply {
            text = "Generate editable YouTube titles, descriptions and hashtags from the topic or caption text you provide. Configure your personal API key in Settings."
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(2), 0, dp(8))
        })
        aiTopicInput = EditText(this).apply {
            hint = "Video topic or short summary"
            setHintTextColor(muted)
            setTextColor(white)
            minLines = 1
            maxLines = 3
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(panel)
        }
        root.addView(aiTopicInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        aiDescriptionInput = EditText(this).apply {
            hint = "AI title, description and hashtags will appear here"
            setHintTextColor(muted)
            setTextColor(white)
            minLines = 4
            maxLines = 8
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(panel)
        }
        root.addView(aiDescriptionInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        root.addView(Button(this).apply {
            text = "Generate with Gemini AI"
            setOnClickListener { generateGeminiDescription() }
        }, matchWrap())
        root.addView(Button(this).apply {
            text = "Transcribe video with Gemini"
            setOnClickListener { generateTimedCaptionsWithGemini() }
        }, matchWrap())
        highlightButton = Button(this).apply {
            text = "Find visual highlight moments"
            setOnClickListener { scanVisualHighlights() }
        }
        root.addView(highlightButton, matchWrap())
        aiStatus = TextView(this).apply {
            text = "AI generation uses your Gemini API quota and requires internet access."
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(aiStatus)

        root.addView(sectionTitle("EXPORT", white))
        exportButton = Button(this).apply {
            text = "Export timeline MP4"
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
            "✓  Export the ordered timeline as one MP4 (straight cuts)",
            "✓  Export aspect ratios: original, 16:9, 9:16, square",
            "✓  Rotate, mute audio, and burn in a static text overlay",
            "✓  Five caption overlay styles with live preview and export",
            "✓  Fine trim adjustments (1s, 0.1s, 0.01s)",
            "✓  Animated text and emoji entrance presets",
            "✓  Smart edit presets for Shorts, landscape, square, podcast and karaoke",
            "✓  Export a basic SRT subtitle file for the selected time range",
            "○  AI speech transcription, highlight detection and multi-clip export",
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
        // Give each major block its own breathing room instead of stacking controls tightly.
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            lp.bottomMargin = dp(12)
            if (child is TextView && child.text.toString() == child.text.toString().uppercase(Locale.ROOT)
                && child.text.toString().length in 4..40) {
                lp.topMargin = dp(10)
            }
            child.layoutParams = lp
        }
        polishUi(root, accent, panel, white)
    }

    private fun polishUi(root: LinearLayout, accent: Int, panel: Int, white: Int) {
        fun rounded(color: Int, radiusDp: Int): android.graphics.drawable.Drawable {
            val shape = android.graphics.drawable.GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radiusDp).toFloat()
            }
            return android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x338B78FF.toInt()), shape, null
            )
        }
        fun visit(view: View) {
            when (view) {
                is Button -> {
                    val primary = view == pickButton || view == exportButton
                    view.background = rounded(if (primary) accent else 0xFF292735.toInt(), 14)
                    view.setTextColor(white)
                    view.textSize = view.textSize.coerceIn(13f, 15f)
                    view.minHeight = dp(50)
                    view.isAllCaps = false
                    view.stateListAnimator = null
                    view.setPadding(dp(14), dp(8), dp(14), dp(8))
                    view.setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN ->
                                v.animate().scaleX(0.975f).scaleY(0.975f).setDuration(90).start()
                            android.view.MotionEvent.ACTION_UP,
                            android.view.MotionEvent.ACTION_CANCEL ->
                                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                        }
                        false
                    }
                }
                is EditText -> {
                    view.background = rounded(panel, 12)
                    view.setPadding(dp(14), dp(12), dp(14), dp(12))
                    view.textSize = 15f
                    view.minHeight = dp(52)
                }
                is LinearLayout -> {
                    if (view == timelineContainer) {
                        view.background = rounded(panel, 16)
                        view.setPadding(dp(10), dp(8), dp(10), dp(8))
                    }
                    for (i in 0 until view.childCount) visit(view.getChildAt(i))
                }
                is FrameLayout -> {
                    view.background = rounded(0xFF08080D.toInt(), 18)
                    view.clipToOutline = true
                    for (i in 0 until view.childCount) visit(view.getChildAt(i))
                }
            }
        }
        visit(root)
        // A short staggered entrance animation, intentionally lightweight for older phones.
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            child.alpha = 0f
            child.translationY = dp(8).toFloat()
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(i.coerceAtMost(10) * 22L)
                .setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun secureAiPreferences(): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            this,
            "opencut_secure_ai",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun getGeminiApiKey(): String? {
        return try { secureAiPreferences().getString("gemini_api_key", null) } catch (_: Exception) { null }
    }

    private fun showSettingsMenu() {
        val keyStatus = if (getGeminiApiKey().isNullOrBlank()) "Not configured" else "Configured"
        val items = arrayOf("Gemini API key — $keyStatus", "About OpenCut Mobile")
        android.app.AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showGeminiKeyDialog()
                    1 -> android.app.AlertDialog.Builder(this)
                        .setTitle("About OpenCut Mobile")
                        .setMessage("Open-source video editor. Gemini AI metadata generation is optional and uses the API key you configure on this device. Automatic YouTube posting is not included.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showGeminiKeyDialog() {
        val input = EditText(this).apply {
            hint = "Paste your Gemini API key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(getGeminiApiKey().orEmpty())
            setSingleLine(true)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Gemini API key")
            .setMessage("Create a key in Google AI Studio. The key is stored encrypted on this device. Never share your key or commit it to GitHub.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isBlank()) {
                    secureAiPreferences().edit().remove("gemini_api_key").apply()
                    aiStatus.text = "Gemini API key removed."
                } else {
                    try {
                        secureAiPreferences().edit().putString("gemini_api_key", key).apply()
                        aiStatus.text = "API key saved encrypted on this device."
                    } catch (e: Exception) {
                        aiStatus.text = "Could not securely save key: ${e.message}"
                    }
                }
            }
            .setNeutralButton("Remove key") { _, _ ->
                try { secureAiPreferences().edit().remove("gemini_api_key").apply() } catch (_: Exception) {}
                aiStatus.text = "Gemini API key removed."
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    private fun generateTimedCaptionsWithGemini() {
        val apiKey = getGeminiApiKey()
        if (apiKey.isNullOrBlank()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Gemini API key required")
                .setMessage("Add your personal Gemini API key in Settings before transcribing a video.")
                .setPositiveButton("Add key") { _, _ -> showGeminiKeyDialog() }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val videoUri = selectedVideo
        if (videoUri == null) {
            aiStatus.text = "Import and select a video first."
            return
        }
        aiStatus.text = "Uploading video for timed transcription. Large videos may take a while…"
        Thread {
            var errorText: String? = null
            var cues = emptyList<CaptionCue>()
            var uploadUrl: String? = null
            var fileUri: String? = null
            var mimeType = contentResolver.getType(videoUri) ?: "video/mp4"
            try {
                val descriptor = contentResolver.openAssetFileDescriptor(videoUri, "r")
                    ?: throw IllegalStateException("Cannot read the selected video.")
                val length = descriptor.length
                descriptor.close()
                if (length <= 0L) throw IllegalStateException("Video size could not be determined.")
                val startConnection = (URL("https://generativelanguage.googleapis.com/upload/v1beta/files?key=" + java.net.URLEncoder.encode(apiKey, "UTF-8")).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 30000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("X-Goog-Upload-Protocol", "resumable")
                    setRequestProperty("X-Goog-Upload-Command", "start")
                    setRequestProperty("X-Goog-Upload-Header-Content-Length", length.toString())
                    setRequestProperty("X-Goog-Upload-Header-Content-Type", mimeType)
                    setRequestProperty("Content-Type", "application/json")
                }
                startConnection.outputStream.use { it.write(JSONObject().put("file", JSONObject().put("display_name", "OpenCut-video-${System.currentTimeMillis()}")).toString().toByteArray(Charsets.UTF_8)) }
                val startCode = startConnection.responseCode
                if (startCode !in 200..299) throw IllegalStateException("Gemini upload setup failed (HTTP $startCode). Check API key and quota.")
                uploadUrl = startConnection.getHeaderField("X-Goog-Upload-URL")
                startConnection.disconnect()
                if (uploadUrl.isNullOrBlank()) throw IllegalStateException("Gemini did not return an upload URL.")
                val uploadConnection = URL(uploadUrl).openConnection() as HttpURLConnection
                uploadConnection.requestMethod = "POST"
                uploadConnection.connectTimeout = 30000
                uploadConnection.readTimeout = 120000
                uploadConnection.doOutput = true
                uploadConnection.setFixedLengthStreamingMode(length)
                uploadConnection.setRequestProperty("X-Goog-Upload-Offset", "0")
                uploadConnection.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
                contentResolver.openInputStream(videoUri).use { input ->
                    if (input == null) throw IllegalStateException("Cannot open video stream.")
                    uploadConnection.outputStream.use { output -> input.copyTo(output, 64 * 1024) }
                }
                val uploadCode = uploadConnection.responseCode
                val uploadBody = (if (uploadCode in 200..299) uploadConnection.inputStream else uploadConnection.errorStream)
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                uploadConnection.disconnect()
                if (uploadCode !in 200..299) throw IllegalStateException("Gemini video upload failed (HTTP $uploadCode): ${uploadBody.take(180)}")
                val fileObject = JSONObject(uploadBody).optJSONObject("file") ?: JSONObject(uploadBody)
                fileUri = fileObject.optString("uri").takeIf { it.isNotBlank() }
                mimeType = fileObject.optString("mimeType", mimeType)
                if (fileUri.isNullOrBlank()) throw IllegalStateException("Gemini upload returned no file URI.")
                val prompt = """Transcribe all spoken dialogue in this video into subtitle cues. Return ONLY valid JSON array, no markdown. Each item must have numeric start and end times in seconds and a concise verbatim text field, schema: [{"start":0.0,"end":2.4,"text":"spoken words"}]. Use accurate timestamps, preserve language as spoken, split captions into readable phrases of at most about 8 words, omit music and sound effects, and do not invent speech."""
                val requestBody = JSONObject().put("contents", org.json.JSONArray().put(JSONObject().put("parts", org.json.JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().put("file_data", JSONObject().put("mime_type", mimeType).put("file_uri", fileUri)))))
                    .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
                    .toString()
                var responseText: String? = null
                var lastState = "PROCESSING"
                repeat(12) {
                    val statusConnection = URL("https://generativelanguage.googleapis.com/v1beta/" + fileObject.optString("name") + "?key=" + java.net.URLEncoder.encode(apiKey, "UTF-8")).openConnection() as HttpURLConnection
                    statusConnection.connectTimeout = 15000
                    statusConnection.readTimeout = 15000
                    val statusCode = statusConnection.responseCode
                    val statusBody = (if (statusCode in 200..299) statusConnection.inputStream else statusConnection.errorStream).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    statusConnection.disconnect()
                    if (statusCode in 200..299) {
                        val statusJson = JSONObject(statusBody).optJSONObject("file") ?: JSONObject(statusBody)
                        lastState = statusJson.optString("state", "ACTIVE")
                        if (lastState == "ACTIVE") return@repeat
                    }
                    Thread.sleep(2000)
                }
                val genConnection = (URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + java.net.URLEncoder.encode(apiKey, "UTF-8")).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 30000
                    readTimeout = 120000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                genConnection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
                val genCode = genConnection.responseCode
                val genBody = (if (genCode in 200..299) genConnection.inputStream else genConnection.errorStream).bufferedReader(Charsets.UTF_8).use { it.readText() }
                genConnection.disconnect()
                if (genCode !in 200..299) throw IllegalStateException("Gemini transcription failed (HTTP $genCode): ${genBody.take(220)}")
                val textResult = JSONObject(genBody).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                    ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                    ?: throw IllegalStateException("Gemini returned no transcript.")
                val cleaned = textResult.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val jsonCues = org.json.JSONArray(cleaned)
                val parsed = mutableListOf<CaptionCue>()
                for (i in 0 until jsonCues.length()) {
                    val item = jsonCues.optJSONObject(i) ?: continue
                    val start = item.optDouble("start", Double.NaN)
                    val end = item.optDouble("end", Double.NaN)
                    val text = item.optString("text").trim()
                    if (start.isFinite() && end > start && text.isNotBlank()) parsed.add(CaptionCue(start, end, text))
                }
                cues = parsed.sortedBy { it.start }
                if (cues.isEmpty()) throw IllegalStateException("Gemini returned no valid timed caption cues.")
            } catch (e: Exception) {
                errorText = e.message ?: "Transcription failed."
            }
            runOnUiThread {
                if (cues.isNotEmpty()) {
                    generatedCaptionCues.clear()
                    generatedCaptionCues.addAll(cues)
                    captionInput.setText(cues.joinToString(" ") { it.text })
                    aiStatus.text = "Generated ${cues.size} timed caption cues. Review them and use Export SRT to save subtitles."
                } else {
                    aiStatus.text = errorText ?: "Could not transcribe this video."
                }
            }
        }.start()
    }

    private fun generateGeminiDescription() {
        val apiKey = getGeminiApiKey()
        if (apiKey.isNullOrBlank()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Gemini API key required")
                .setMessage("Add your own Gemini API key first. You can create one in Google AI Studio; usage is subject to Google's current limits and terms.")
                .setPositiveButton("Add key") { _, _ -> showGeminiKeyDialog() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val topic = aiTopicInput.text.toString().trim()
        val caption = captionInput.text.toString().trim()
        if (topic.isBlank() && caption.isBlank()) {
            aiStatus.text = "Enter a video topic or caption first."
            return
        }
        aiStatus.text = "Generating title, description and hashtags…"
        val prompt = "Create YouTube metadata for a video. Return a concise clickable title, a natural description, and 8-12 relevant hashtags. Do not invent facts not present in the input. Topic/summary: ${topic.ifBlank { "(not supplied)" }}. User-provided caption/context: ${caption.ifBlank { "(not supplied)" }}. Output plain text with headings Title, Description, Hashtags."
        Thread {
            var resultText: String? = null
            var errorText: String? = null
            var connection: HttpURLConnection? = null
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + java.net.URLEncoder.encode(apiKey, "UTF-8"))
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = 45000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val body = JSONObject()
                    .put("contents", org.json.JSONArray().put(JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", prompt)))))
                    .toString()
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (code !in 200..299) {
                    val message = try { JSONObject(response).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                    errorText = "Gemini request failed (HTTP $code): ${message ?: response.take(240)}"
                } else {
                    val rootJson = JSONObject(response)
                    resultText = rootJson.optJSONArray("candidates")
                        ?.optJSONObject(0)?.optJSONObject("content")
                        ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                        ?.takeIf { !it.isNullOrBlank() }
                    if (resultText == null) errorText = "Gemini returned no text. Check your key, model access, and quota."
                }
            } catch (e: Exception) {
                errorText = "AI request failed: ${e.message ?: "unknown error"}"
            } finally {
                connection?.disconnect()
            }
            runOnUiThread {
                if (resultText != null) {
                    aiDescriptionInput.setText(resultText)
                    aiStatus.text = "Generated. Review and edit the result before using it."
                } else {
                    aiStatus.text = errorText ?: "Could not generate metadata."
                }
            }
        }.start()
    }

    private fun toSrtTime(seconds: Double): String {
        val totalMs = (seconds.coerceAtLeast(0.0) * 1000.0).toLong()
        val hours = totalMs / 3_600_000
        val minutes = (totalMs % 3_600_000) / 60_000
        val secs = (totalMs % 60_000) / 1000
        val millis = totalMs % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, secs, millis)
    }

    private fun showSmartEditPresets() {
        val presets = arrayOf(
            "YouTube Shorts — vertical, yellow captions",
            "YouTube landscape — 16:9, classic captions",
            "Square social — 1:1, minimal captions",
            "Podcast clip — landscape, boxed captions",
            "Karaoke — original ratio, green captions"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Smart edit presets")
            .setItems(presets) { _, which ->
                when (which) {
                    0 -> { outputRatio = "Shorts 9:16"; captionStyle = "Bold Yellow" }
                    1 -> { outputRatio = "YouTube 16:9"; captionStyle = "Classic" }
                    2 -> { outputRatio = "Square 1:1"; captionStyle = "Minimal" }
                    3 -> { outputRatio = "YouTube 16:9"; captionStyle = "Boxed" }
                    else -> { captionStyle = "Karaoke" }
                }
                aspectRatioButton.text = "Ratio: " + when (outputRatio) {
                    "YouTube 16:9" -> "16:9"
                    "Shorts 9:16" -> "9:16"
                    "Square 1:1" -> "1:1"
                    else -> "Original"
                }
                captionStyleButton.text = "Caption style: $captionStyle"
                updateCaptionPreview()
                captionToolsStatus.text = "Applied preset: ${presets[which]}. Review the preview before exporting."
                status.text = "Smart edit preset applied. The preset uses local rules, not generative AI."
            }
            .show()
    }

    private fun buildCaptionSpannable(value: String): SpannableString {
        return SpannableString(value).apply {
            when (captionStyle) {
                "Bold Yellow" -> {
                    setSpan(ForegroundColorSpan(0xFFFFE45E.toInt()), 0, length, 0)
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, 0)
                }
                "Boxed" -> {
                    setSpan(ForegroundColorSpan(android.graphics.Color.WHITE), 0, length, 0)
                    setSpan(BackgroundColorSpan(0xE6000000.toInt()), 0, length, 0)
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, 0)
                }
                "Minimal" -> {
                    setSpan(ForegroundColorSpan(android.graphics.Color.WHITE), 0, length, 0)
                }
                "Karaoke" -> {
                    setSpan(ForegroundColorSpan(0xFF72F1A5.toInt()), 0, length, 0)
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, 0)
                }
                else -> {
                    setSpan(ForegroundColorSpan(android.graphics.Color.WHITE), 0, length, 0)
                    setSpan(BackgroundColorSpan(0xB8000000.toInt()), 0, length, 0)
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, 0)
                }
            }
        }
    }

    private fun updateCaptionPreview() {
        if (!::captionPreview.isInitialized || !::captionInput.isInitialized) return
        val value = captionInput.text.toString().trim()
        captionPreview.text = value
        val wasHidden = captionPreview.visibility != View.VISIBLE
        captionPreview.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
        if (value.isNotEmpty() && wasHidden) {
            captionPreview.alpha = 0f
            captionPreview.scaleX = 0.88f
            captionPreview.scaleY = 0.88f
            captionPreview.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(220L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
                .start()
        }
        captionPreview.setPadding(dp(12), dp(6), dp(12), dp(6))
        when (captionStyle) {
            "Bold Yellow" -> {
                captionPreview.setTextColor(0xFFFFE45E.toInt())
                captionPreview.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                captionPreview.typeface = Typeface.DEFAULT_BOLD
                captionPreview.setShadowLayer(3f, 2f, 2f, android.graphics.Color.BLACK)
            }
            "Boxed" -> {
                captionPreview.setTextColor(android.graphics.Color.WHITE)
                captionPreview.setBackgroundColor(0xE6000000.toInt())
                captionPreview.typeface = Typeface.DEFAULT_BOLD
                captionPreview.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
            }
            "Minimal" -> {
                captionPreview.setTextColor(android.graphics.Color.WHITE)
                captionPreview.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                captionPreview.typeface = Typeface.DEFAULT
                captionPreview.setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
            }
            "Karaoke" -> {
                captionPreview.setTextColor(0xFF72F1A5.toInt())
                captionPreview.setBackgroundColor(0x99000000.toInt())
                captionPreview.typeface = Typeface.DEFAULT_BOLD
                captionPreview.setShadowLayer(1f, 1f, 1f, android.graphics.Color.BLACK)
            }
            else -> {
                captionPreview.setTextColor(android.graphics.Color.WHITE)
                captionPreview.setBackgroundColor(0xB8000000.toInt())
                captionPreview.typeface = Typeface.DEFAULT_BOLD
                captionPreview.setShadowLayer(1f, 1f, 1f, android.graphics.Color.BLACK)
            }
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
                text = "＋ Import clips to start editing"
                gravity = Gravity.CENTER
                setTextColor(0xFFB8B5C8.toInt())
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }, LinearLayout.LayoutParams(dp(220), dp(120)))
            return
        }
        timelineClips.forEachIndexed { index, clip ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setBackgroundColor(if (clip.uri == selectedVideo) 0xFF383052.toInt() else 0xFF24232D.toInt())
            }
            val select = Button(this).apply {
                text = "${if (clip.uri == selectedVideo) "● " else ""}${index + 1}. ${clip.name}"
                textSize = 12f
                setOnClickListener { selectTimelineClip(index) }
            }
            val thumbnail = ImageView(this).apply {
                tag = clip.uri.toString()
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF111116.toInt())
                contentDescription = "Thumbnail for ${clip.name}"
            }
            row.addView(thumbnail, LinearLayout.LayoutParams(-1, dp(66)).apply { bottomMargin = dp(4) })
            Thread {
                val bitmap = try {
                    MediaMetadataRetriever().let { retriever ->
                        try {
                            retriever.setDataSource(this@MainActivity, clip.uri)
                            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        } finally {
                            retriever.release()
                        }
                    }
                } catch (_: Exception) { null }
                if (bitmap != null) runOnUiThread {
                    if (thumbnail.tag == clip.uri.toString()) thumbnail.setImageBitmap(bitmap)
                    else bitmap.recycle()
                }
            }.start()
            row.tag = index
            row.setOnLongClickListener {
                val dragData = ClipData.newPlainText("opencut-clip-index", index.toString())
                it.startDragAndDrop(dragData, View.DragShadowBuilder(it), null, 0)
                true
            }
            row.setOnDragListener { targetView, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> event.clipDescription?.label == "opencut-clip-index"
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        targetView.setBackgroundColor(0xFF494064.toInt())
                        true
                    }
                    DragEvent.ACTION_DRAG_EXITED -> {
                        targetView.setBackgroundColor(if (clip.uri == selectedVideo) 0xFF383052.toInt() else 0xFF24232D.toInt())
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        val from = event.clipData.getItemAt(0).text.toString().toIntOrNull()
                        val to = targetView.tag as? Int
                        if (from != null && to != null && from in timelineClips.indices && to in timelineClips.indices && from != to) {
                            val moving = timelineClips.removeAt(from)
                            timelineClips.add(to, moving)
                            renderTimeline()
                            status.text = "Clip order updated."
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        targetView.setBackgroundColor(if (clip.uri == selectedVideo) 0xFF383052.toInt() else 0xFF24232D.toInt())
                        true
                    }
                    else -> true
                }
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
            row.alpha = 0f
            row.translationY = dp(8).toFloat()
            timelineContainer.addView(row, LinearLayout.LayoutParams(dp(190), dp(128)).apply { rightMargin = dp(8) })
            row.animate().alpha(1f).translationY(0f)
                .setStartDelay((index * 28L).coerceAtMost(180L))
                .setDuration(200L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun formatClock(milliseconds: Long): String {
        val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1000L)
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
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
        val videoEffects = mutableListOf<androidx.media3.common.Effect>()
        when (outputRatio) {
            "YouTube 16:9" -> videoEffects.add(
                Presentation.createForAspectRatio(16f / 9f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
            )
            "Shorts 9:16" -> videoEffects.add(
                Presentation.createForAspectRatio(9f / 16f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
            )
            "Square 1:1" -> videoEffects.add(
                Presentation.createForAspectRatio(1f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
            )
        }
        if (rotationDegrees != 0f || motionScale != 1f || motionRotation != 0f) {
            videoEffects.add(
                ScaleAndRotateTransformation.Builder()
                    .setScale(motionScale, motionScale)
                    .setRotationDegrees(rotationDegrees + motionRotation)
                    .build()
            )
        }
        val textOverlays = mutableListOf<androidx.media3.effect.TextureOverlay>()
        val overlayText = overlayInput.text.toString().trim()
        if (overlayText.isNotEmpty()) {
            val styledText = SpannableString(overlayText).apply {
                setSpan(ForegroundColorSpan(android.graphics.Color.WHITE), 0, length, 0)
                setSpan(BackgroundColorSpan(0xCC000000.toInt()), 0, length, 0)
                setSpan(StyleSpan(Typeface.BOLD), 0, length, 0)
            }
            textOverlays.add(createAnimatedTextOverlay(styledText, titleAnimation))
        }
        val stickerText = stickerInput.text.toString().trim()
        if (stickerText.isNotEmpty()) {
            val styledSticker = SpannableString(stickerText).apply {
                setSpan(ForegroundColorSpan(android.graphics.Color.WHITE), 0, length, 0)
                setSpan(StyleSpan(Typeface.BOLD), 0, length, 0)
            }
            textOverlays.add(createAnimatedTextOverlay(styledSticker, titleAnimation))
        }
        val captionText = captionInput.text.toString().trim()
        if (generatedCaptionCues.isNotEmpty()) {
            textOverlays.add(createTimedCaptionOverlay(generatedCaptionCues.toList()))
        } else if (captionText.isNotEmpty()) {
            val styledCaption = buildCaptionSpannable(captionText)
            textOverlays.add(TextOverlay.createStaticTextOverlay(styledCaption))
        }
        if (textOverlays.isNotEmpty()) videoEffects.add(OverlayEffect(textOverlays))
        val clipsToExport = if (timelineClips.isEmpty()) {
            listOf(TimelineClip(uri, "Selected clip"))
        } else {
            timelineClips.toList()
        }
        val editedItems = clipsToExport.map { clip ->
            val sourceItem = if (clip.uri == uri) mediaItem else MediaItem.fromUri(clip.uri)
            EditedMediaItem.Builder(sourceItem)
                .setRemoveAudio(muteAudio)
                .setEffects(Effects(emptyList(), videoEffects))
                .build()
        }
        val composition = Composition.Builder(
            EditedMediaItemSequence.withAudioAndVideoFrom(editedItems)
        ).build()

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
            transformer?.start(composition, outFile.absolutePath)
            pollProgress()
        } catch (e: Exception) {
            outFile.delete()
            status.text = "Could not start export: ${e.message}"
            resetExportUi()
        }
    }



    private fun scanVisualHighlights() {
        val uri = selectedVideo
        if (uri == null) {
            status.text = "Import and select a video first."
            return
        }
        highlightButton.isEnabled = false
        status.text = "Scanning frames for scene changes… This runs locally on your phone."
        Thread {
            val candidates = mutableListOf<Long>()
            var previous: android.graphics.Bitmap? = null
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                var timeMs = 1000L
                while (timeMs < durationMs && timeMs <= 30L * 60L * 1000L) {
                    val frame = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame != null) {
                        val small = android.graphics.Bitmap.createScaledBitmap(frame, 24, 24, true)
                        if (small !== frame) frame.recycle()
                        val old = previous
                        if (old != null) {
                            var diff = 0L
                            for (y in 0 until 24) {
                                for (x in 0 until 24) {
                                    val a = old.getPixel(x, y)
                                    val b = small.getPixel(x, y)
                                    diff += kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b))
                                    diff += kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b))
                                    diff += kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
                                }
                            }
                            val averageDifference = diff.toDouble() / (24.0 * 24.0 * 3.0)
                            if (averageDifference >= 42.0) candidates.add(timeMs)
                        }
                        previous?.recycle()
                        previous = small
                    }
                    timeMs += 2000L
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Scene scan failed: ${e.message ?: "Could not read video frames"}" }
            } finally {
                previous?.recycle()
                try { retriever?.release() } catch (_: Exception) {}
            }
            runOnUiThread {
                highlightButton.isEnabled = true
                if (candidates.isEmpty()) {
                    status.text = "No strong scene changes found. Try a video with more visual cuts."
                    return@runOnUiThread
                }
                val labels = candidates.take(30).map { ms ->
                    String.format(Locale.US, "%02d:%02d  — scene change candidate", ms / 60000, (ms / 1000) % 60)
                }.toTypedArray()
                status.text = "Found ${candidates.size} possible scene changes. These are visual candidates, not AI-verified highlights."
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Visual highlight candidates")
                    .setMessage("Tap a time to seek there. This local scan detects frame changes; it does not understand dialogue or story context.")
                    .setItems(labels) { _, which ->
                        val chosenMs = candidates[which.coerceAtMost(candidates.lastIndex)]
                        player?.seekTo(chosenMs)
                        playheadTime.text = "${formatClock(chosenMs)} / ${formatClock(player?.duration ?: 0L)}"
                    }
                    .setPositiveButton("Done", null)
                    .show()
            }
        }.start()
    }


    private fun createTimedCaptionOverlay(cues: List<CaptionCue>): TextOverlay {
        return object : TextOverlay() {
            override fun getText(presentationTimeUs: Long): SpannableString {
                val seconds = presentationTimeUs / 1_000_000.0
                val cue = cues.firstOrNull { seconds >= it.start && seconds < it.end }
                if (cue == null) return SpannableString("")
                return buildCaptionSpannable(cue.text)
            }
        }
    }

    private fun createAnimatedTextOverlay(
        original: SpannableString,
        animation: String
    ): TextOverlay {
        return object : TextOverlay() {
            override fun getText(presentationTimeUs: Long): SpannableString {
                val seconds = presentationTimeUs / 1_000_000f
                val progress = (seconds / 0.7f).coerceIn(0f, 1f)
                val alpha = when (animation) {
                    "Fade in" -> (progress * 255).toInt()
                    "Pop in" -> if (seconds < 0.12f) 80 else 255
                    "Slide up" -> (progress * 255).toInt()
                    else -> 255
                }.coerceIn(0, 255)
                val styled = SpannableString(original)
                if (styled.isNotEmpty()) {
                    styled.setSpan(
                        ForegroundColorSpan(android.graphics.Color.argb(alpha, 255, 255, 255)),
                        0, styled.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                return styled
            }


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

    private fun showEditorPanel(panelName: String) {
        val selectedSection = when (panelName) {
            "✂\nEdit" -> "TRIM RANGE"
            "♫\nAudio" -> "CANVAS & AUDIO"
            "T\nText" -> "CAPTION OVERLAY & STYLE PREVIEW"
            "CC\nCaptions" -> "CAPTION OVERLAY & STYLE PREVIEW"
            "✦\nAI" -> "GEMINI AI ASSISTANT"
            "⇧\nExport" -> "EXPORT"
            else -> null
        }
        var currentSection: String? = null
        var reachedTools = false
        var target: View? = null
        val sectionNames = setOf(
            "VIDEO PREVIEW", "TIMELINE CLIPS", "TRIM RANGE", "CANVAS & AUDIO",
            "CAPTION OVERLAY & STYLE PREVIEW", "GEMINI AI ASSISTANT", "EXPORT", "FEATURE STATUS"
        )
        for (index in 0 until editorRoot.childCount) {
            val child = editorRoot.getChildAt(index)
            val title = if (child is TextView) child.text?.toString() else null
            if (title != null && title in sectionNames) {
                when (title) {
                    "VIDEO PREVIEW", "TIMELINE CLIPS" -> currentSection = null
                    "TRIM RANGE" -> { currentSection = "TRIM RANGE"; reachedTools = true }
                    "CANVAS & AUDIO" -> currentSection = "CANVAS & AUDIO"
                    "CAPTION OVERLAY & STYLE PREVIEW" -> currentSection = "CAPTION OVERLAY & STYLE PREVIEW"
                    "GEMINI AI ASSISTANT" -> currentSection = "GEMINI AI ASSISTANT"
                    "EXPORT", "FEATURE STATUS" -> currentSection = "EXPORT"
                }
                if (title == selectedSection || (panelName == "▶\nPreview" && title == "VIDEO PREVIEW")) target = child
            }
            child.visibility = if (!reachedTools || selectedSection == currentSection) View.VISIBLE else View.GONE
        }
        editorScroll.post {
            editorScroll.smoothScrollTo(0, ((target?.top ?: 0) - dp(12)).coerceAtLeast(0))
            // Short, low-cost entrance motion: animate only the newly visible panel.
            var animateSection: String? = null
            var insideSelectedPanel = false
            var delay = 0L
            for (index in 0 until editorRoot.childCount) {
                val child = editorRoot.getChildAt(index)
                val heading = if (child is TextView) child.text?.toString() else null
                if (heading != null && heading in sectionNames) {
                    animateSection = when (heading) {
                        "VIDEO PREVIEW", "TIMELINE CLIPS" -> null
                        "TRIM RANGE" -> "TRIM RANGE"
                        "CANVAS & AUDIO" -> "CANVAS & AUDIO"
                        "CAPTION OVERLAY & STYLE PREVIEW" -> "CAPTION OVERLAY & STYLE PREVIEW"
                        "GEMINI AI ASSISTANT" -> "GEMINI AI ASSISTANT"
                        "EXPORT", "FEATURE STATUS" -> "EXPORT"
                        else -> null
                    }
                    insideSelectedPanel = selectedSection == animateSection ||
                        (panelName == "▶\nPreview" && heading == "VIDEO PREVIEW")
                    delay = 0L
                    if (insideSelectedPanel && child.visibility == View.VISIBLE) animatePanelEntrance(child, delay)
                } else if (insideSelectedPanel && child.visibility == View.VISIBLE) {
                    animatePanelEntrance(child, delay)
                    delay += 24L
                }
            }
        }
    }

    private fun animateToolTap(view: View) {
        view.animate().cancel()
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120L)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
            }.start()
    }

    private fun animatePanelEntrance(view: View, delay: Long = 0L) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = dp(10).toFloat()
        view.animate().alpha(1f).translationY(0f)
            .setStartDelay(delay)
            .setDuration(220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun sectionTitle(text: String, color: Int) = TextView(this).apply {
        this.text = text
        textSize = 14f
        letterSpacing = 0.08f
        setTextColor(color)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(18), 0, dp(8))
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
