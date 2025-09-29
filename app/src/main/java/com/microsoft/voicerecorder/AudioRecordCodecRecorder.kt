package com.microsoft.voicerecorder

import android.Manifest
import android.media.*
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.min

class AudioRecordCodecRecorder(
    private val outputFile: File,
    private val sampleRate: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val aacBitrate: Int = 64000
) {
    @Volatile private var isRecording = false

    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerTrackIndex = -1
    private var mediaRecorder: MediaRecorder? = null

    /** Public: start recording depending on extension */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        when (outputFile.extension.lowercase()) {
            "m4a" -> startAacPath()
            "opus" -> startOpusPath()
            else -> throw IllegalArgumentException("Unsupported ext: ${outputFile.extension}")
        }
    }

    /** Stop recording and release resources */
    fun stop() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        codec?.apply {
            try {
                stop()
            } catch (_: Exception) {}
            release()
        }
        codec = null

        muxer?.apply {
            try {
                stop()
            } catch (_: Exception) {}
            release()
        }
        muxer = null

        mediaRecorder?.apply {
            try {
                stop()
            } catch (_: Exception) {}
            release()
        }
        mediaRecorder = null
    }

    /** ---------- OPUS path (MediaRecorder, Android 10+) ---------- */
    private fun startOpusPath() {
        if (Build.VERSION.SDK_INT < 29) {
            throw IllegalStateException("Opus requires API 29+")
        }
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
        isRecording = true
    }

    /** ---------- AAC path (AudioRecord + MediaCodec + MediaMuxer) ---------- */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAacPath() {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            minBuffer
        )

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, aacBitrate)
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        isRecording = true
        audioRecord?.startRecording()

        thread(start = true, name = "AAC-Encode") {
            val pcmBuffer = ByteArray(minBuffer)
            val bufferInfo = MediaCodec.BufferInfo()

            while (isRecording) {
                val read = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: 0
                if (read > 0) {
                    val inIndex = codec!!.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec!!.getInputBuffer(inIndex)!!
                        inBuf.clear()
                        val safeSize = min(read, inBuf.remaining())
                        inBuf.put(pcmBuffer, 0, safeSize)
                        val pts = System.nanoTime() / 1000
                        codec!!.queueInputBuffer(inIndex, 0, safeSize, pts, 0)
                    }
                }

                var outIndex = codec!!.dequeueOutputBuffer(bufferInfo, 10_000)
                while (outIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        codec!!.releaseOutputBuffer(outIndex, false)
                    } else {
                        val outBuf = codec!!.getOutputBuffer(outIndex)!!
                        if (bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            if (muxerTrackIndex == -1) {
                                val newFormat = codec!!.outputFormat
                                muxerTrackIndex = muxer!!.addTrack(newFormat)
                                muxer!!.start()
                            }
                            muxer!!.writeSampleData(muxerTrackIndex, outBuf, bufferInfo)
                        }
                        codec!!.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex = codec!!.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            // EOS flush
            val eosIndex = codec!!.dequeueInputBuffer(10_000)
            if (eosIndex >= 0) {
                codec!!.queueInputBuffer(eosIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            var outIndex = codec!!.dequeueOutputBuffer(bufferInfo, 10_000)
            while (outIndex >= 0) {
                val outBuf = codec!!.getOutputBuffer(outIndex)!!
                if (bufferInfo.size > 0 && muxerTrackIndex != -1) {
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                    muxer!!.writeSampleData(muxerTrackIndex, outBuf, bufferInfo)
                }
                codec!!.releaseOutputBuffer(outIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                outIndex = codec!!.dequeueOutputBuffer(bufferInfo, 0)
            }
        }
    }
}
