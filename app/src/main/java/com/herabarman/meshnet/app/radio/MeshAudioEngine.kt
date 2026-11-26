package com.herabarman.meshnet.app.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.core.content.ContextCompat
import com.herabarman.meshnet.kit.AdpcmCodec
import com.herabarman.meshnet.kit.VoiceCodec
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

/**
 * Real Android live voice Push-to-Talk & Real-Time RTP audio capture/playback engine.
 * Uses hardware AcousticEchoCanceler + NoiseSuppressor + ADPCM 4:1 codec (8 kHz mono -> 60 ms / 244 B packets).
 */
class MeshAudioEngine(private val context: Context) {
    private val codec: VoiceCodec = AdpcmCodec.FACTORY.create()
    private val isTalking = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    var framesCaptured: Long = 0L
        private set

    @Volatile
    var framesPlayed: Long = 0L
        private set

    @Volatile
    var lastDbLevel: Float = 0f
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun startPushToTalkLoopbackOrStream(
        loopbackAudio: Boolean,
        onEncodedPacket: (byteArray: ByteArray, level: Float) -> Unit,
        onStateChanged: () -> Unit
    ) {
        if (!hasAudioPermission()) {
            lastError = "RECORD_AUDIO অনুমতি প্রয়োজন"
            onStateChanged()
            return
        }
        if (!isTalking.compareAndSet(false, true)) return
        lastError = null
        onStateChanged()

        executor.execute {
            val sr = codec.sampleRate()
            val minRec = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val minPlay = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minRec <= 0 || minPlay <= 0) {
                lastError = "অডিও হার্ডওয়্যার সাপোর্ট নেই"
                isTalking.set(false)
                onStateChanged()
                return@execute
            }

            var rec: AudioRecord? = null
            var track: AudioTrack? = null
            var aec: AcousticEchoCanceler? = null
            var ns: NoiseSuppressor? = null
            try {
                rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sr,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minRec, sr)
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord init ব্যর্থ")
                }
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
                    }
                    if (NoiseSuppressor.isAvailable()) {
                        ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
                    }
                } catch (_: Exception) { }

                if (loopbackAudio) {
                    track = buildAudioTrack(sr, maxOf(minPlay, sr / 2))
                    track.play()
                }

                rec.startRecording()
                val pcm = ShortArray(codec.blockSamples())
                while (isTalking.get()) {
                    var off = 0
                    while (off < pcm.size && isTalking.get()) {
                        val n = rec.read(pcm, off, pcm.size - off)
                        if (n <= 0) break
                        off += n
                    }
                    if (!isTalking.get() || off < pcm.size) break

                    var peak = 0
                    for (s in pcm) {
                        val a = abs(s.toInt())
                        if (a > peak) peak = a
                    }
                    val level = min(1f, peak / 16000f)
                    lastDbLevel = level

                    val encoded = codec.encode(pcm)
                    framesCaptured++
                    if (encoded != null) {
                        onEncodedPacket(encoded, level)
                        if (loopbackAudio && track != null) {
                            val dec = codec.decode(encoded)
                            if (dec != null) {
                                track.write(dec, 0, dec.size)
                                framesPlayed++
                            }
                        }
                    }
                    onStateChanged()
                }
            } catch (e: Exception) {
                lastError = e.message ?: "অডিও ত্রুটি"
            } finally {
                isTalking.set(false)
                lastDbLevel = 0f
                try { rec?.stop() } catch (_: Exception) { }
                try { aec?.release() } catch (_: Exception) { }
                try { ns?.release() } catch (_: Exception) { }
                try { rec?.release() } catch (_: Exception) { }
                try { track?.stop(); track?.release() } catch (_: Exception) { }
                onStateChanged()
            }
        }
    }

    fun stopPushToTalk() {
        isTalking.set(false)
    }

    fun isTalking(): Boolean = isTalking.get()

    @Suppress("DEPRECATION")
    private fun buildAudioTrack(sr: Int, bufSize: Int): AudioTrack {
        return if (Build.VERSION.SDK_INT >= 23) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            AudioTrack(AudioManager.STREAM_VOICE_CALL, sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize, AudioTrack.MODE_STREAM)
        }
    }
}
