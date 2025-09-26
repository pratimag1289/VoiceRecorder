package com.microsoft.voicerecorder

import ai.instavision.ffmpegkit.FFprobeKit
import android.media.MediaExtractor
import android.media.MediaPlayer
import android.media.MediaFormat
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FileDetailsActivity : ComponentActivity() {
    // single MediaPlayer instance used for file preview in details screen
    private var detailsMediaPlayer: MediaPlayer? = null
    private var runExtractorAlways = true

    private fun mimeToFriendly(mime: String): String {
        return when {
            mime.startsWith("audio/opus") -> "Opus (audio/opus)"
            mime.startsWith("audio/mp4") || mime.startsWith("audio/mp4a") || mime == "audio/mp4a-latm" -> "AAC (audio/mp4a-latm)"
            mime.startsWith("audio/aac") -> "AAC"
            mime.startsWith("audio/mpeg") -> "MP3"
            mime.startsWith("audio/3gpp") || mime.startsWith("audio/3gpp2") -> "AMR/3GP"
            mime.startsWith("audio/ogg") || mime.contains("ogg") -> "OGG/Opus?"
            mime == "unknown" -> "unknown"
            else -> mime
        }
    }

    // Modify detectWithFfprobeKit to use a blocking approach
    private fun detectWithFfprobeKit(filePath: String): Triple<String?, Int?, Int?> {
        val session = FFprobeKit.getMediaInformation(filePath)
        val info = session.mediaInformation
        val streams = info?.streams
        val audioStream = streams?.firstOrNull { it.type == "audio" }

        val codec = audioStream?.codec
        val sampleRate = audioStream?.sampleRate?.toIntOrNull()
        val bitRate = audioStream?.bitrate?.toIntOrNull()

        return Triple(codec, sampleRate ?: 0, bitRate ?: 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        val files = dir?.listFiles { f -> f.extension in listOf("m4a", "opus", "ogg") }?.toList() ?: emptyList()
        val logFile = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "file_size_log.txt")
        val logMap = mutableMapOf<String, List<String>>()
        if (logFile.exists()) {
            logFile.readLines().forEach { line ->
                val parts = line.split(",")
                // support both 6-field (older) and 7-field (with detected mime)
                if (parts.size >= 6) {
                    // If there are extra fields, keep them all; parts[6] may be the mime
                    logMap[parts[0]] = parts
                }
            }
        }
        setContent {
            var logCleared by remember { mutableStateOf(false) }
            val logMapState = remember { mutableStateMapOf<String, List<String>>() }
            // initialize
            LaunchedEffect(Unit) {
                logMapState.clear()
                logMap.forEach { (k, v) -> logMapState[k] = v }
            }
            // files sorted by lastModified() desc
            val filesState = remember { mutableStateListOf<File>().apply { addAll(files.sortedByDescending { it.lastModified() }) } }
            val snackbarHostState = remember { SnackbarHostState() }
            val coroutineScope = rememberCoroutineScope()

            // MIME map populated by MediaExtractor or log
            val mimeMapState = remember { mutableStateMapOf<String, String>() }
            // Detected sample rate and bitrate maps (values in Hz and bps)
            val detectedSampleRate = remember { mutableStateMapOf<String, Int>() }
            val detectedBitrate = remember { mutableStateMapOf<String, Int>() }
            // populate mime map for files: prefer log-stored mime, otherwise detect via MediaExtractor
            LaunchedEffect(filesState, logMapState) {
                mimeMapState.clear()
                // First, fill from logMapState if present
                filesState.forEach { file ->
                    val logged = logMapState[file.name]
                    val loggedMime = logged?.getOrNull(6)
                    if (!loggedMime.isNullOrBlank()) {
                        mimeMapState[file.name] = loggedMime
                    } else {
                        mimeMapState[file.name] = "" // placeholder to detect later
                    }
                }
                // Now detect for any that don't have a logged mime
                filesState.forEach { file ->
                    val current = mimeMapState[file.name]
                    if (current == null || current.isEmpty() || runExtractorAlways) {
                        // Run extractor on IO dispatcher to avoid blocking UI
                        val found = withContext(Dispatchers.IO) {
                            try {
                                val extractor = MediaExtractor()
                                extractor.setDataSource(file.absolutePath)
                                var fm: String? = null
                                var sampleHz: Int? = null
                                var bitRateBps: Int? = null
                                for (i in 0 until extractor.trackCount) {
                                    val format = extractor.getTrackFormat(i)
                                    val mediaFormatMime = format.getString(MediaFormat.KEY_MIME)
                                    if (mediaFormatMime != null && mediaFormatMime.startsWith("audio/")) {
                                        fm = mediaFormatMime
                                        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                            sampleHz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                        }
                                        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                                            bitRateBps = format.getInteger(MediaFormat.KEY_BIT_RATE)
                                        }
                                        break
                                    }
                                }
                                extractor.release()
                                Triple(fm, sampleHz, bitRateBps)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                null
                            }
                        }

                        // If MediaExtractor was unable to provide bitrate/sample, try ffprobe as a fallback (best-effort)
                        var finalMime: String? = null
                        var finalSample: Int? = null
                        var finalBit: Int? = null
                        if (found != null) {
                            finalMime = found.first
                            finalSample = found.second
                            finalBit = found.third
                        }
                        if ((finalBit == null || finalBit == 0) || finalMime.isNullOrBlank()) {
                            // attempt ffprobe fallback
                            val ff = withContext(Dispatchers.IO) {
                                try {
                                    detectWithFfprobeKit(file.absolutePath)
                                } catch (e: Exception) {
                                    Triple(null, null, null)
                                }
                            }
                            if (ff.first != null) finalMime = ff.first
                            if (ff.second != null && ff.second!! > 0) finalSample = ff.second
                            if (ff.third != null && ff.third!! > 0) finalBit = ff.third
                        }

                        mimeMapState[file.name] = finalMime ?: "unknown"
                        detectedSampleRate[file.name] = finalSample ?: 0
                        detectedBitrate[file.name] = finalBit ?: 0
                    } else {
                        // If logged mime present, also try to populate sample/bitrate from log fields if available
                        val logged = logMapState[file.name]
                        val loggedSample = logged?.getOrNull(3)?.toIntOrNull() ?: 0
                        val loggedBit = logged?.getOrNull(2)?.toIntOrNull() ?: 0
                        detectedSampleRate[file.name] = if (loggedSample > 0) loggedSample else 0
                        detectedBitrate[file.name] = if (loggedBit > 0) loggedBit else 0
                    }
                }
            }

            var showConfirmDialog by remember { mutableStateOf(false) }
            var alsoDeleteFiles by remember { mutableStateOf(false) }

            // Playback state for details screen
            var playingPath by remember { mutableStateOf<String?>(null) }
            var isPlaying by remember { mutableStateOf(false) }

            fun stopAndReleasePlayer() {
                try {
                    detailsMediaPlayer?.setOnCompletionListener(null)
                    detailsMediaPlayer?.stop()
                } catch (_: Exception) { }
                try { detailsMediaPlayer?.release() } catch (_: Exception) { }
                detailsMediaPlayer = null
                playingPath = null
                isPlaying = false
            }

            fun playFile(file: File) {
                // If same file is playing, toggle pause/resume
                if (playingPath == file.absolutePath) {
                    if (isPlaying) {
                        detailsMediaPlayer?.pause()
                        isPlaying = false
                    } else {
                        detailsMediaPlayer?.start()
                        isPlaying = true
                    }
                    return
                }
                // Different file: stop previous and start new
                stopAndReleasePlayer()
                try {
                    val mp = MediaPlayer()
                    mp.setDataSource(file.absolutePath)
                    mp.prepare()
                    mp.start()
                    mp.setOnCompletionListener {
                        playingPath = null
                        isPlaying = false
                        try { mp.release() } catch (_: Exception) { }
                        detailsMediaPlayer = null
                    }
                    detailsMediaPlayer = mp
                    playingPath = file.absolutePath
                    isPlaying = true
                } catch (e: Exception) {
                    e.printStackTrace()
                    // show snackbar using coroutine scope
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Playback failed: ${e.message}")
                    }
                }
            }

            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) }
            ) { innerPadding ->
                Surface(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Top,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📋 All Generated Files", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))

                        Button(onClick = { showConfirmDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE57373)), modifier = Modifier.fillMaxWidth(0.6f)) {
                            Text("🧹 Clear Logs", color = Color.White)
                        }

                        // Confirmation dialog
                        if (showConfirmDialog) {
                            AlertDialog(
                                onDismissRequest = { showConfirmDialog = false },
                                title = { Text("Clear logs?") },
                                text = {
                                    Column {
                                        Text("This will clear the saved metadata log.\nYou can optionally delete the recorded audio files as well.")
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked = alsoDeleteFiles, onCheckedChange = { alsoDeleteFiles = it })
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Also delete audio files", modifier = Modifier.padding(start = 4.dp))
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        // Perform clear (and optional delete)
                                        try {
                                            logFile.writeText("")
                                        } catch (e: Exception) { e.printStackTrace() }
                                        logMapState.clear()
                                        mimeMapState.clear()
                                        if (alsoDeleteFiles) {
                                            // stop playback if playing
                                            stopAndReleasePlayer()
                                            // delete files and update filesState
                                            filesState.forEach { f ->
                                                try { f.delete() } catch (e: Exception) { e.printStackTrace() }
                                            }
                                            filesState.clear()
                                        }
                                        showConfirmDialog = false
                                        alsoDeleteFiles = false
                                        logCleared = true
                                    }) { Text("Confirm") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showConfirmDialog = false }) { Text("Cancel") }
                                }
                            )
                        }

                        if (logCleared) {
                            LaunchedEffect(Unit) {
                                snackbarHostState.showSnackbar("Logs cleared!")
                                logCleared = false
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            // Always show newest (recently created/modified) files first
                            items(filesState.sortedByDescending { it.lastModified() }) { file ->
                                val meta = logMapState[file.name]
                                val format = meta?.getOrNull(1) ?: "unknown"
                                val bitrate = meta?.getOrNull(2) ?: "unknown"
                                val sampleRate = meta?.getOrNull(3) ?: "unknown"
                                val size = meta?.getOrNull(4) ?: (file.length() / 1024).toString()
                                val duration = meta?.getOrNull(5) ?: run {
                                    try {
                                        val mp = MediaPlayer()
                                        mp.setDataSource(file.absolutePath)
                                        mp.prepare()
                                        val d = mp.duration / 1000
                                        mp.release()
                                        d.toString()
                                    } catch (e: Exception) { e.printStackTrace(); "unknown" }
                                }
                                val detectedMime = mimeMapState[file.name] ?: "unknown"
                                val friendly = mimeToFriendly(detectedMime)
                                val detectedSample = detectedSampleRate[file.name] ?: 0
                                val detectedBit = detectedBitrate[file.name] ?: 0
                                // compute estimated bitrate (bps) from file size and duration if detectedBit not available
                                val durationSec = duration.toIntOrNull() ?: 0
                                val estimatedBitrate = if (durationSec > 0) ((file.length() * 8) / durationSec).toInt() else 0
                                 Card(
                                     modifier = Modifier
                                         .fillMaxWidth()
                                         .padding(vertical = 10.dp, horizontal = 8.dp),
                                     colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                 ) {
                                     Column(modifier = Modifier.padding(18.dp), horizontalAlignment = Alignment.Start) {
                                         Text("🎵 ${file.name}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                                         Spacer(modifier = Modifier.height(10.dp))
                                         Text("📦 Size: $size KB", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                                         Text("🔎 Detected codec: $detectedMime", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                                         Text("🔧 Detected sample rate: ${if (detectedSample>0) "$detectedSample Hz" else "unknown"}", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                                         if (detectedBit > 0) {
                                            Text("🔧 Detected bitrate: $detectedBit bps (~${detectedBit/1000} kbps)", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                                         } else if (estimatedBitrate > 0) {
                                            Text("🔧 Estimated bitrate: $estimatedBitrate bps (~${estimatedBitrate/1000} kbps)", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                                         } else {
                                            Text("🔧 Detected bitrate: unknown", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                                         }
                                         Text("🎚️ Bitrate: $bitrate", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                                         Text("🎛️ Sample Rate: $sampleRate", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                                         Text("🗂️ Format: $format", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                                         Text("⏱️ Duration: $duration s", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                                         Text("🎼 Friendly codec: $friendly", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())

                                         Spacer(modifier = Modifier.height(12.dp))
                                        // Play / Pause controls for this file
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                                            val isThisPlaying = playingPath == file.absolutePath && isPlaying
                                            Button(onClick = { playFile(file) }) {
                                                Text(if (isThisPlaying) "⏸️ Pause" else "▶️ Play")
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            if (playingPath == file.absolutePath) {
                                                Text(if (isPlaying) "Playing" else "Paused", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterVertically))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Update the usage of ffprobe fallback to use detectWithFfprobeKit with a callback
    private fun detectMediaInfo(filePath: String, callback: (Triple<String?, Int?, Int?>) -> Unit) {
        // First try MediaExtractor
        val extractorResult = try {
            val extractor = MediaExtractor()
            extractor.setDataSource(filePath)
            var codec: String? = null
            var sampleRate: Int? = null
            var bitRate: Int? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    codec = mime
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    bitRate = format.getInteger(MediaFormat.KEY_BIT_RATE)
                    break
                }
            }
            extractor.release()
            Triple(codec, sampleRate ?: 0, bitRate ?: 0)
        } catch (e: Exception) {
            null
        }

        if (extractorResult != null && extractorResult.first != null) {
            callback(extractorResult)
        } else {
            // Use FFprobeKit directly for robust media probing
            callback(detectWithFfprobeKit(filePath))
        }
    }

    // Example usage of detectMediaInfo with callback
    private fun processMediaFile(filePath: String) {
        detectMediaInfo(filePath) { result ->
            val (codec, sampleRate, bitRate) = result
            android.util.Log.d("FileDetailsActivity", "Detected codec: $codec, Sample rate: $sampleRate, Bitrate: $bitRate")
            // Handle the result (e.g., update UI or save metadata)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            detailsMediaPlayer?.setOnCompletionListener(null)
            detailsMediaPlayer?.stop()
        } catch (_: Exception) { }
        try { detailsMediaPlayer?.release() } catch (_: Exception) { }
        detailsMediaPlayer = null
    }
}
