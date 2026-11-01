package com.ignotek.ignoassist

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Codifica PCM 16 kHz mono (16 bit) a un archivo .m4a (AAC-LC) usando el codificador del telefono. */
object AacEncoder {
    const val SAMPLE_RATE = 16000
    private const val BIT_RATE = 32000

    fun codificar(pcm: ShortArray, muestras: Int, destino: File) {
        val formato = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1)
        formato.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        formato.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        formato.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(formato, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(destino.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var pista = -1
        var muxerIniciado = false
        val info = MediaCodec.BufferInfo()
        var pos = 0
        var entradaTerminada = false
        var salidaTerminada = false
        val bytesPorMuestra = 2
        try {
            while (!salidaTerminada) {
                if (!entradaTerminada) {
                    val idx = codec.dequeueInputBuffer(10000)
                    if (idx >= 0) {
                        val buf: ByteBuffer = codec.getInputBuffer(idx) ?: continue
                        buf.clear()
                        val cap = buf.capacity() / bytesPorMuestra
                        val n = minOf(cap, muestras - pos)
                        val tiempoUs = pos * 1000000L / SAMPLE_RATE
                        if (n <= 0) {
                            codec.queueInputBuffer(idx, 0, 0, tiempoUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            entradaTerminada = true
                        } else {
                            val sb = buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            sb.put(pcm, pos, n)
                            codec.queueInputBuffer(idx, 0, n * bytesPorMuestra, tiempoUs, 0)
                            pos += n
                        }
                    }
                }
                val oidx = codec.dequeueOutputBuffer(info, 10000)
                when {
                    oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        pista = muxer.addTrack(codec.outputFormat)
                        muxer.start(); muxerIniciado = true
                    }
                    oidx >= 0 -> {
                        val ob = codec.getOutputBuffer(oidx)
                        if (ob != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerIniciado) {
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            muxer.writeSampleData(pista, ob, info)
                        }
                        codec.releaseOutputBuffer(oidx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) salidaTerminada = true
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (e: Exception) {}
            codec.release()
            if (muxerIniciado) { try { muxer.stop() } catch (e: Exception) {} }
            muxer.release()
        }
    }

    /** Respaldo: WAV PCM si el codificador AAC fallara. */
    fun escribirWav(pcm: ShortArray, muestras: Int, destino: File) {
        val datos = muestras * 2
        val cab = ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        cab.put("RIFF".toByteArray()); cab.putInt(36 + datos); cab.put("WAVE".toByteArray())
        cab.put("fmt ".toByteArray()); cab.putInt(16); cab.putShort(1); cab.putShort(1); cab.putInt(SAMPLE_RATE); cab.putInt(SAMPLE_RATE * 2); cab.putShort(2); cab.putShort(16)
        cab.put("data".toByteArray()); cab.putInt(datos)
        destino.outputStream().use { os ->
            os.write(cab.array())
            val bb = ByteBuffer.allocate(datos).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.asShortBuffer().put(pcm, 0, muestras)
            os.write(bb.array())
        }
    }
}
