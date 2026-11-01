package com.ignotek.ignoassist

import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Cliente HTTP minimo (sin librerias) para hablar con la plataforma. */
object Api {
    class Respuesta(val codigo: Int, val json: JSONObject?, val error: String?) {
        val ok: Boolean get() = codigo == 200 && json != null && json.optBoolean("ok", false)
    }

    private fun leer(c: HttpURLConnection): Respuesta {
        val code = try { c.responseCode } catch (e: Exception) { return Respuesta(0, null, e.message ?: "sin respuesta") }
        val stream = try { if (code >= 400) c.errorStream else c.inputStream } catch (e: Exception) { null }
        val texto = try { stream?.let { BufferedReader(InputStreamReader(it, "UTF-8")).use { r -> r.readText() } } ?: "" } catch (e: Exception) { "" }
        val json = try { if (texto.trim().startsWith("{")) JSONObject(texto) else null } catch (e: Exception) { null }
        val err = if (json == null) "HTTP $code ${texto.take(160)}" else json.optString("error", "")
        return Respuesta(code, json, if (code == 200 && json != null) null else err)
    }

    fun post(url: String, campos: Map<String, String>, timeoutMs: Int = 60000): Respuesta {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 20000; c.readTimeout = timeoutMs
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            c.setRequestProperty("User-Agent", "IgnoAssist/" + BuildConfig.VERSION_NAME)
            val cuerpo = campos.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
            c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            leer(c).also { c.disconnect() }
        } catch (e: Exception) { Respuesta(0, null, e.message ?: e.javaClass.simpleName) }
    }

    /** Sube un archivo (multipart) con campos extra. */
    fun subirArchivo(url: String, campos: Map<String, String>, nombreCampo: String, archivo: File, mime: String, timeoutMs: Int = 120000): Respuesta {
        val limite = "----IgnoAssist" + System.currentTimeMillis()
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 20000; c.readTimeout = timeoutMs
            c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$limite")
            c.setRequestProperty("User-Agent", "IgnoAssist/" + BuildConfig.VERSION_NAME)
            val out = DataOutputStream(c.outputStream)
            for ((k, v) in campos) {
                out.writeBytes("--$limite\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"$k\"\r\n\r\n")
                out.write(v.toByteArray(Charsets.UTF_8)); out.writeBytes("\r\n")
            }
            out.writeBytes("--$limite\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"$nombreCampo\"; filename=\"${archivo.name}\"\r\n")
            out.writeBytes("Content-Type: $mime\r\n\r\n")
            FileInputStream(archivo).use { fis ->
                val buf = ByteArray(16384)
                while (true) { val n = fis.read(buf); if (n <= 0) break; out.write(buf, 0, n) }
            }
            out.writeBytes("\r\n--$limite--\r\n")
            out.flush(); out.close()
            leer(c).also { c.disconnect() }
        } catch (e: Exception) { Respuesta(0, null, e.message ?: e.javaClass.simpleName) }
    }
}
