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
import android.widget.FrameLayout
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
    private lateinit var aspectRatioButton: Button
    private lateinit var rotateButton: Button
    private lateinit var muteButton: Button
    private lateinit var overlayInput: EditText
    private lateinit var captionInput: EditText
    private lateinit var captionPreview: TextView
    private lateinit var captionStyleButton: Button
    private lateinit var captionToolsStatus: TextView
    private lateinit var aiTopicInput: EditText
    private lateinit var aiDescriptionInput: EditText
    private lateinit var aiStatus: TextView
    private var captionStyle = "Classic"
    private var previewSpeed = 1.0f
    private var outputRatio: String = "Original"
    private var rotationDegrees = 0f
    private var muteAudio = false

    private val createSrtDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) {
            try {
                val caption = captionInput.text.toString().trim()
                if (caption.isEmpty()) {
                    status.text = "Type a caption before exporting subtitles."
                    return@registerForActivityResult
                }
                val start = startInput.text.toString().toDoubleOrNull() ?: 0.0
                val end = endInput.text.toString().toDoubleOrNull() ?: 0.0
                if (end <= start) {
                    status.text = "Set a valid trim range before exporting SRT."
                    return@registerForActivityResult
                }
                val srt = "1\\n${toSrtTime(start)} --> ${toSrtTime(end)}\\n${caption.replace("-->", "→")}\\n"
                contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(srt) }
                status.text = "Subtitle file exported. It contains one caption cue for the selected time range."
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

        val appHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appHeader.addView(TextView(this).apply {
            text = "OpenCut Mobile"
            textSize = 25f
            setTextColor(white)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        appHeader.addView(Button(this).apply {
            text = "⚙ Settings"
            setOnClickListener { showSettingsMenu() }
        }, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(appHeader)
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
        root.addView(previewFrame)

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
        editOptions.addView(aspectRatioButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(4) })
        editOptions.addView(rotateButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(4) })
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
        aiStatus = TextView(this).apply {
            text = "AI generation uses your Gemini API quota and requires internet access."
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(aiStatus)

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
            "✓  Export aspect ratios: original, 16:9, 9:16, square",
            "✓  Rotate, mute audio, and burn in a static text overlay",
            "✓  Five caption overlay styles with live preview and export",
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
        captionPreview.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
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
        if (rotationDegrees != 0f) {
            videoEffects.add(ScaleAndRotateTransformation.Builder().setRotationDegrees(rotationDegrees).build())
        }
        val textOverlays = mutableListOf<androidx.media3.effect.TextureOverlay>()
        val overlayText = overlayInput.text.toString().trim()
        if (overlayText.isNotEmpty()) {
            val styledText = SpannableString(overlayText).apply {
                setSpan(ForegroundColorSpan(android.graphics.Color.WHITE), 0, length, 0)
                setSpan(BackgroundColorSpan(0xCC000000.toInt()), 0, length, 0)
                setSpan(StyleSpan(Typeface.BOLD), 0, length, 0)
            }
            textOverlays.add(TextOverlay.createStaticTextOverlay(styledText))
        }
        val captionText = captionInput.text.toString().trim()
        if (captionText.isNotEmpty()) {
            val styledCaption = buildCaptionSpannable(captionText)
            textOverlays.add(TextOverlay.createStaticTextOverlay(styledCaption))
        }
        if (textOverlays.isNotEmpty()) videoEffects.add(OverlayEffect(textOverlays))
        val edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(muteAudio)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

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
