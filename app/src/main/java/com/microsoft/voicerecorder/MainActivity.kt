package com.microsoft.voicerecorder

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.microsoft.voicerecorder.ui.theme.VoiceRecorderTheme
import java.io.File
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : ComponentActivity() {
    private var isRecording by mutableStateOf(false)
    private var sampleRate by mutableIntStateOf(44100)
    private var bitRate by mutableIntStateOf(96000)
    private var fileSize by mutableIntStateOf(0)
    private var outputFilePath by mutableStateOf("")
    private var selectedFormat by mutableStateOf(".m4a")
    private var useEncoderDefaults by mutableStateOf(false)

    private var mediaRecorder: MediaRecorder? = null
    private var lastRecordedFile: File? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying by mutableStateOf(false)
    private var playbackPosition by mutableStateOf(0f)
    private var playbackDuration by mutableStateOf(0)

    private var recordingStartTime by mutableStateOf<Long?>(null)
    private var recordingDuration by mutableStateOf(0)

    private val formatMap = mapOf(
        ".m4a" to MediaRecorder.OutputFormat.MPEG_4,
        ".opus" to MediaRecorder.OutputFormat.OGG // OGG for opus container
    )
    private val encoderMap = mapOf(
        ".m4a" to MediaRecorder.AudioEncoder.AAC,
        ".opus" to MediaRecorder.AudioEncoder.OPUS
    )

    private val REQUEST_RECORD_AUDIO_PERMISSION = 200
    private var permissionToRecordAccepted = false
    private val permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)
    private var fileSizeLog by mutableStateOf("")

    private lateinit var prefs: SharedPreferences
    private val PREF_PERMISSION_REQUESTED = "permission_requested"

    private var isRunningExperiment by mutableStateOf(false)
    private var experimentStatus by mutableStateOf("")

    private var selectedRecordingMethod by mutableStateOf("MediaRecorder")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("voicerecorder_prefs", MODE_PRIVATE)
        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
        enableEdgeToEdge()
        setContent {
            VoiceRecorderTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(innerPadding)
                }
            }
        }
    }

    @Composable
    fun RecordingMethodSelector(selectedMethod: String, onMethodSelected: (String) -> Unit) {
        val methods = listOf("MediaRecorder", "MediaCodec", "libopus")
        Text("🎛️ Select Recording Method", style = MaterialTheme.typography.titleMedium)
        Column {
            methods.forEach { method ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selectedMethod == method,
                        onClick = { onMethodSelected(method) }
                    )
                    Text(method, modifier = Modifier.clickable { onMethodSelected(method) })
                }
            }
        }
    }

    @Composable
    fun MainScreen(innerPadding: PaddingValues) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Section
                Text(
                    text = "🎙️ Voice Recorder",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 24.dp)
                )

                // Recording Settings Section
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .padding(horizontal = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.Start
                    ) {

                        // Recording Method Selector
                        RecordingMethodSelector(
                            selectedMethod = selectedRecordingMethod,
                            onMethodSelected = { selectedRecordingMethod = it }
                        )

                        // Format Selector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Format", modifier = Modifier.weight(1f))
                            FormatSelector(
                                selectedFormat = selectedFormat,
                                onFormatSelected = { selectedFormat = it }
                            )
                        }

                        // Encoder Defaults Toggle
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Use encoder defaults", modifier = Modifier.weight(1f))
                            Switch(checked = useEncoderDefaults, onCheckedChange = { useEncoderDefaults = it })
                        }

                        // Bitrate and Sample Rate Selectors
                        if (!useEncoderDefaults) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Bitrate", modifier = Modifier.weight(1f))
                                BitrateSelector(
                                    selectedBitrate = bitRate,
                                    enabled = true,
                                    onBitrateSelected = { bitRate = it }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Sample Rate", modifier = Modifier.weight(1f))
                                SampleRateSelector(
                                    selectedSampleRate = sampleRate,
                                    enabled = true,
                                    onSampleRateSelected = { sampleRate = it }
                                )
                            }
                        }
                    }
                }

                // Controls Section
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .padding(horizontal = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🎛️ Controls", style = MaterialTheme.typography.titleMedium)

                        // Record and Play Buttons
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = { onRecord() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isRecording) "⏹️ Stop" else "⏺️ Record")
                            }
                            Button(
                                onClick = { onPlayPause() },
                                modifier = Modifier.weight(1f),
                                enabled = lastRecordedFile?.exists() == true
                            ) {
                                Text(if (isPlaying) "⏸️ Pause" else "▶️ Play")
                            }
                        }

                        // Display Recording Duration Below Buttons
                        if (isRecording) {
                            Text(
                                text = "Recording Duration: ${recordingDuration}s",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Playback Controls
                        if (!isRecording && lastRecordedFile?.exists() == true) {
                            Text("🎵 Playback", style = MaterialTheme.typography.titleSmall)
                            PlaybackSeekBar(
                                position = playbackPosition,
                                duration = playbackDuration,
                                isPlaying = isPlaying,
                                onSeek = { onSeek(it) }
                            )
                        }

                        // File Size Display
                        Text("📦 File Size", style = MaterialTheme.typography.titleSmall)
                        Text("${fileSize / 1024} KB", style = MaterialTheme.typography.bodyLarge)
                    }
                }

                // Log and Experiment Actions
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = {
                            val intent = Intent(this@MainActivity, FileDetailsActivity::class.java)
                            startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth(0.7f)
                    ) {
                        Text("📋 Show Details Log")
                    }

                    Button(
                        onClick = {
                            if (!isRunningExperiment) runExperiment()
                        },
                        modifier = Modifier.fillMaxWidth(0.7f),
                        enabled = !isRunningExperiment
                    ) {
                        Text(if (isRunningExperiment) "⏳ Running experiment..." else "▶️ Run Experiment")
                    }

                    if (isRunningExperiment) {
                        Text(experimentStatus, style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.7f))
                    }
                    // Playback position updater
                    LaunchedEffect(isPlaying) {
                        while (isPlaying && playbackDuration > 0) {
                            mediaPlayer?.let {
                                playbackPosition = it.currentPosition.toFloat() / playbackDuration
                            }
                            delay(200)
                        }
                    }
                    // Recording duration updater
                    if (isRecording) {
                        LaunchedEffect(isRecording) {
                            recordingStartTime = System.currentTimeMillis()
                            while (isRecording) {
                                recordingDuration = ((System.currentTimeMillis() - (recordingStartTime ?: 0)) / 1000).toInt()
                                delay(1000)
                            }
                        }
                    } else {
                        recordingDuration = 0
                        recordingStartTime = null
                    }
                    if (isRecording) {
                        Text("Recording Duration: ${recordingDuration}s", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionToRecordAccepted = if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        if (!permissionToRecordAccepted) {
            Toast.makeText(this, "Permission required to record audio", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAndRequestPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                // Show rationale and request again
                AlertDialog.Builder(this)
                    .setTitle("Permission Required")
                    .setMessage("Microphone access is required to record audio.")
                    .setPositiveButton("Grant") { _, _ ->
                        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                // Directly request permission
                ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
            }
            false
        } else {
            true
        }
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Denied")
            .setMessage("Microphone permission was permanently denied. Please enable it in app settings.")
            .setPositiveButton("Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:" + packageName)
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startRecording() {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        val timestamp = DateFormat.format("yyyyMMdd_HHmmss", System.currentTimeMillis())
        val fileName = "recording_${timestamp}${selectedFormat}"
        outputFilePath = File(dir, fileName).absolutePath
        lastRecordedFile = File(outputFilePath)
        val format: Int
        val encoder: Int
        if (selectedFormat == ".opus" && android.os.Build.VERSION.SDK_INT >= 29) {
            format = MediaRecorder.OutputFormat.OGG
            encoder = MediaRecorder.AudioEncoder.OPUS
        } else {
            format = MediaRecorder.OutputFormat.MPEG_4
            encoder = MediaRecorder.AudioEncoder.AAC
        }
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(format)
            setAudioEncoder(encoder)
            setOutputFile(outputFilePath)
            if (!useEncoderDefaults) {
                setAudioEncodingBitRate(bitRate)
                setAudioSamplingRate(sampleRate)
            }
            try {
                prepare()
                start()
                isRecording = true
                println("Recording started: $outputFilePath")
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // Validate the file immediately after stopping the recording
        lifecycleScope.launch {
            delay(1000) // Wait for 1 second to ensure some data is written
            val file = lastRecordedFile
            if (file?.exists() == true) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    println("Validating recorded file: ${file.absolutePath}")
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        println("Track $i: $format")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    println("Error validating file: ${e.message}")
                } finally {
                    extractor.release()
                }
            } else {
                println("File does not exist or is invalid.")
            }
        }
    }
    private var recorder: AudioRecordCodecRecorder? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startMediaCodecRecording() {
        val timestamp = DateFormat.format("yyyyMMdd_HHmmss", System.currentTimeMillis())
        val fileName = "recording_${timestamp}${selectedFormat}"
        val outputFile = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), fileName)
        recorder = AudioRecordCodecRecorder(
            outputFile = outputFile,
            sampleRate = sampleRate,
        )
        recorder?.let {
            it.apply {
                try {
                    start()
                    recorder = this
                    lastRecordedFile = outputFile
                    isRecording = true
                    println("MediaCodec recording started: ${outputFile.absolutePath}")
                } catch (e: Exception) {
                    e.printStackTrace()
                    println("Error starting MediaCodec recording: ${e.message}")
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to start MediaCodec recording: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } ?: run {
            println("Recorder initialization failed.")
            Toast.makeText(this, "Recorder initialization failed.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopMediaCodecRecording() {
        try {
            recorder?.stop()
            isRecording = false
            Toast.makeText(this, "MediaCodec recording stopped", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to stop MediaCodec recording: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startLibOpusRecording() {
        // Placeholder for libopus recording implementation
        Toast.makeText(this, "libopus recording started (not implemented yet)", Toast.LENGTH_SHORT).show()
    }

    private fun stopLibOpusRecording() {
        // Placeholder for stopping libopus recording
        Toast.makeText(this, "libopus recording stopped (not implemented yet)", Toast.LENGTH_SHORT).show()
    }

    private fun detectAudioProperties(filePath: String): Triple<String, Int, Int> {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(filePath)
            var foundMime: String? = null
            var sampleHz = 0
            var bitRateBps = 0
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    foundMime = mime
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        try { sampleHz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { }
                    }
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        try { bitRateBps = format.getInteger(MediaFormat.KEY_BIT_RATE) } catch (_: Exception) { }
                    }
                    break
                }
            }
            extractor.release()
            Triple(foundMime ?: "unknown", sampleHz, bitRateBps)
        } catch (e: Exception) {
            e.printStackTrace()
            Triple("unknown", 0, 0)
        }
    }

    private fun onRecord() {
        val permissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val rationale = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
        val permissionRequested = prefs.getBoolean(PREF_PERMISSION_REQUESTED, false)
        if (!permissionGranted) {
            if (rationale) {
                // Show rationale and request again
                AlertDialog.Builder(this)
                    .setTitle("Permission Required")
                    .setMessage("Microphone access is required to record audio.")
                    .setPositiveButton("Grant") { _, _ ->
                        prefs.edit().putBoolean(PREF_PERMISSION_REQUESTED, true).apply()
                        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            } else {
                if (!permissionRequested) {
                    // First time, request permission
                    prefs.edit().putBoolean(PREF_PERMISSION_REQUESTED, true).apply()
                    ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
                } else {
                    // Permanently denied, show settings dialog
                    showSettingsDialog()
                }
                return
            }
        }
        if (!permissionToRecordAccepted) {
            Toast.makeText(this, "Microphone permission not granted", Toast.LENGTH_SHORT).show()
            return
        }
        if (isRecording) {
            when (selectedRecordingMethod) {
                "MediaRecorder" -> {
                    mediaRecorder?.stop()
                    mediaRecorder?.release()
                    mediaRecorder = null
                }
                "MediaCodec" -> stopMediaCodecRecording()
                "libopus" -> stopLibOpusRecording()
            }
            isRecording = false
            val file = lastRecordedFile
            fileSize = if (file?.exists() == true) file.length().toInt() else 0
            // Get duration
            val duration = try {
                val mp = MediaPlayer()
                mp.setDataSource(file?.absolutePath ?: "")
                mp.prepare()
                val d = mp.duration / 1000
                mp.release()
                d
            } catch (e: Exception) { 0 }
            // Detect mime and audio properties and write all metadata to log (CSV)
            val (detectedMime, detectedSample, detectedBit) = if (file?.exists() == true) detectAudioProperties(file.absolutePath) else Triple("unknown", 0, 0)
            // Update the visible selectors to reflect actual detected values so the UI always matches the encoded file
            if (detectedSample > 0) sampleRate = detectedSample
            if (detectedBit > 0) bitRate = detectedBit
            Toast.makeText(this, "Detected: ${detectedSample}Hz, ${detectedBit}bps", Toast.LENGTH_SHORT).show()
             // prefer detected values for logging (reflects actual encoded file)
             val logEntry = "${file?.name},$selectedFormat,${detectedBit},${detectedSample},${fileSize / 1024},${duration},${detectedMime}"
             fileSizeLog += logEntry + "\n"
             val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
             val logFile = File(dir, "file_size_log.txt")
             logFile.appendText(logEntry + "\n")
             // Prepare playback
             preparePlayback()
             Toast.makeText(this, "File generated!", Toast.LENGTH_SHORT).show()
        } else {
            // Set the recording start time when recording begins
            recordingStartTime = System.currentTimeMillis()

            when (selectedRecordingMethod) {
                "MediaRecorder" -> startRecording()
                "MediaCodec" -> startMediaCodecRecording()
                "libopus" -> startLibOpusRecording()
            }
        }
    }

    private fun preparePlayback() {
        val file = lastRecordedFile
        if (file?.exists() == true) {
            try {
                // Log file details for debugging
                val fileSize = file.length()
                val filePath = file.absolutePath
                println("Attempting to play file: $filePath (Size: $fileSize bytes)")

                // Validate file properties using MediaExtractor
                val extractor = MediaExtractor()
                extractor.setDataSource(filePath)
                var mimeType: String? = null
                var duration: Long = 0
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    mimeType = format.getString(MediaFormat.KEY_MIME)
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        duration = format.getLong(MediaFormat.KEY_DURATION)
                    }
                    if (mimeType != null && mimeType.startsWith("audio/")) {
                        break
                    }
                }
                extractor.release()

                println("File MIME type: $mimeType, Duration: ${duration / 1000} seconds")

                if (mimeType == null || !mimeType.startsWith("audio/")) {
                    Toast.makeText(this, "Invalid audio file format.", Toast.LENGTH_LONG).show()
                    return
                }

                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(filePath)
                    prepare()
                    playbackDuration = duration.toInt() / 1000
                    setOnCompletionListener {
                        this@MainActivity.isPlaying = false
                        playbackPosition = 0f
                    }
                }
                playbackPosition = 0f
            } catch (e: IOException) {
                e.printStackTrace()
                Toast.makeText(this, "Unable to play the file. Ensure it is in a supported format.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "An error occurred while validating the file.", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "File does not exist or is invalid.", Toast.LENGTH_LONG).show()
        }
    }

    private fun onPlayPause() {
        mediaPlayer?.let { player ->
            if (isPlaying) {
                player.pause()
                isPlaying = false
            } else {
                player.seekTo((playbackPosition * playbackDuration).toInt())
                player.start()
                isPlaying = true
            }
        }
    }

    private fun onSeek(position: Float) {
        mediaPlayer?.let { player ->
            player.seekTo((position * playbackDuration).toInt())
            playbackPosition = position
        }
    }

    private fun exportFileSizeLog() {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val logFile = File(dir, "file_size_log.txt")
        logFile.writeText(fileSizeLog)
        val uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            logFile
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Audio file size log attached.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Export File Size Log"))
    }

    private fun runExperiment() {
        // quick preset matrix — you can adjust these lists
        val formats = listOf(".m4a", ".opus")
        val bitrates = listOf(24000, 32000, 64000) // in bps
        val sampleRates = listOf(16000, 44100)
        val durationSec = 10
        // Launch coroutine on the Activity lifecycleScope
        lifecycleScope.launch {
            isRunningExperiment = true
            var total = formats.size * bitrates.size * sampleRates.size
            var done = 0
            for (fmt in formats) {
                for (br in bitrates) {
                    for (sr in sampleRates) {
                        experimentStatus = "Recording: format=$fmt br=${br/1000}kbps sr=${sr}Hz (${done+1}/$total)"
                        // set selections
                        selectedFormat = fmt
                        useEncoderDefaults = false
                        bitRate = br
                        sampleRate = sr
                        // start recording (use existing onRecord to toggle start)
                        onRecord()
                        // wait duration + small buffer
                        try { kotlinx.coroutines.delay((durationSec + 1) * 1000L) } catch (_: Exception) {}
                        // stop recording
                        onRecord()
                        // brief pause
                        try { kotlinx.coroutines.delay(1000L) } catch (_: Exception) {}
                        done++
                    }
                }
            }
            experimentStatus = "Experiment completed: $done recordings"
            isRunningExperiment = false
        }
    }
}

@Composable
fun FormatSelector(
    selectedFormat: String,
    onFormatSelected: (String) -> Unit
) {
    val formats = listOf(".m4a", ".opus")
    var expanded by remember { mutableStateOf(false) }
    Row {
        Text(selectedFormat, modifier = Modifier.clickable(true) { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            formats.forEach { format ->
                DropdownMenuItem(text = { Text(format) }, onClick = {
                    onFormatSelected(format)
                    expanded = false
                })
            }
        }
    }
}

@Composable
fun BitrateSelector(selectedBitrate: Int, enabled: Boolean = true, onBitrateSelected: (Int) -> Unit) {
    val bitrates = listOf(24000, 32000, 64000, 96000, 128000, 192000)
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text("$selectedBitrate", modifier = Modifier.clickable(enabled = enabled) { if (enabled) expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            bitrates.forEach { br ->
                DropdownMenuItem(text = { Text("$br") }, onClick = {
                    onBitrateSelected(br)
                    expanded = false
                })
            }
        }
    }
}

@Composable
fun SampleRateSelector(selectedSampleRate: Int, enabled: Boolean = true, onSampleRateSelected: (Int) -> Unit) {
    val sampleRates = listOf(8000, 16000, 44100, 48000)
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text("$selectedSampleRate", modifier = Modifier.clickable(enabled = enabled) { if (enabled) expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sampleRates.forEach { sr ->
                DropdownMenuItem(text = { Text("$sr") }, onClick = {
                    onSampleRateSelected(sr)
                    expanded = false
                })
            }
        }
    }
}

@Composable
fun PlaybackSeekBar(position: Float, duration: Int, isPlaying: Boolean, onSeek: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {

        Slider(
            value = position,
            onValueChange = onSeek,
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(0.8f)
        )
        Text("${(position * duration / 1000).toInt()}s / ${duration / 1000}s")
    }
}