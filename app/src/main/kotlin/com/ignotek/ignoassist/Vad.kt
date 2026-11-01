package com.ignotek.ignoassist

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Detector de voz por energia con piso de ruido adaptativo (frames de 20 ms).
 * Devuelve true cuando el frame suena a voz (claramente por encima del ruido de fondo).
 */
class Vad {
    private var pisoDb = -55.0
    private var consecutivos = 0
    var ultimoDb = -90.0
        private set
    val pisoActual: Double get() = pisoDb

    fun esVoz(buf: ShortArray, n: Int): Boolean {
        if (n <= 0) return false
        var suma = 0.0
        var cruces = 0
        var prev = buf[0].toInt()
        for (i in 0 until n) {
            val v = buf[i].toInt()
            suma += (v.toDouble() * v)
            if ((v >= 0) != (prev >= 0)) cruces++
            prev = v
        }
        val rms = sqrt(suma / n)
        val db = if (rms < 1.0) -90.0 else 20.0 * log10(rms / 32768.0)
        ultimoDb = db
        // Piso de ruido: baja rapido, sube lento.
        if (db < pisoDb + 3.0) pisoDb = pisoDb * 0.92 + db * 0.08 else pisoDb += 0.03
        if (pisoDb < -75.0) pisoDb = -75.0
        if (pisoDb > -30.0) pisoDb = -30.0
        val tasaCruces = cruces.toDouble() / n
        // Voz: energia por encima del piso, no demasiado baja en absoluto y con cruces por cero tipicos de voz (no un tono puro ni ruido blanco extremo).
        val voz = db > pisoDb + 9.0 && db > -52.0 && tasaCruces in 0.01..0.45
        consecutivos = if (voz) consecutivos + 1 else 0
        return consecutivos >= 2
    }

    fun reiniciar() { consecutivos = 0 }
}
