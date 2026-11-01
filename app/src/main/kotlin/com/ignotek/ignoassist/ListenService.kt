package com.ignotek.ignoassist

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servicio en primer plano: escucha el microfono, separa la voz en trozos (VAD), los codifica a .m4a,
 * los sube a la plataforma y ejecuta las ordenes que regresan (calendario, recordatorios, notas).
 */
class ListenService : Service() {
    companion object {
        const val ACTION_START = "com.ignotek.ignoassist.START"
        const val ACTION_STOP = "com.ignotek.ignoassist.STOP"
        const val ACTION_PAUSE = "com.ignotek.ignoassist.PAUSE"
        const val ACTION_RESUME = "com.ignotek.ignoassist.RESUME"
        const val ACTION_SYNC = "com.ignotek.ignoassist.SYNC"
        const val ACTION_UNDO = "com.ignotek.ignoassist.UNDO"
        const val ACTION_DECIDE = "com.ignotek.ignoassist.DECIDE"
        const val SAMPLE_RATE = 16000
        private const val FRAME = 320 // 20 ms

        fun lanzar(ctx: Context, accion: String, extras: ((Intent) -> Unit)? = null) {
            val i = Intent(ctx, ListenService::class.java).setAction(accion)
            extras?.invoke(i)
            // Android 14 exige el permiso de microfono para un servicio de tipo microphone: sin permiso, abrir la app.
            if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                val a = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("accion", "permisos")
                try { ctx.startActivity(a) } catch (e: Exception) { }
                return
            }
            try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } catch (e: Exception) { }
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var ejecutor: Ejecutor
    private var wakeLock: PowerManager.WakeLock? = null
    private val grabando = AtomicBoolean(false)
    private var hiloGrabacion: Thread? = null
    private var hiloSubida: Thread? = null
    private var hiloSync: Thread? = null
    private val vivo = AtomicBoolean(false)
    private val lockSubida = Object()
    private val lockSync = Object()
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    private var textoEstado = ""
    @Volatile private var pedirSyncYa = false
    private val nombreFecha = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        ejecutor = Ejecutor(this, prefs)
        Notifs.crearCanales(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IgnoAssist:escucha")
        encoderThread = HandlerThread("ia-encoder").also { it.start() }
        encoderHandler = Handler(encoderThread!!.looper)
        File(filesDir, "pendientes").mkdirs()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!mostrarPrimerPlano()) { stopSelf(); return START_NOT_STICKY }
        vivo.set(true)
        prefs.servicioActivo = true
        arrancarHilos()
        when (intent?.action) {
            ACTION_START -> { prefs.escuchaDeseada = true; prefs.pausadoHasta = 0L; iniciarGrabacion() }
            ACTION_STOP -> { prefs.escuchaDeseada = false; prefs.pausadoHasta = 0L; detenerGrabacion(); pedirSyncYa = true; despertarSync(); main.postDelayed({ apagarServicio() }, 1500); return START_NOT_STICKY }
            ACTION_PAUSE -> { pausar(intent.getIntExtra("minutos", prefs.pausaMinutosDefecto)) }
            ACTION_RESUME -> { prefs.pausadoHasta = 0L; prefs.escuchaDeseada = true; iniciarGrabacion() }
            ACTION_SYNC -> { pedirSyncYa = true; despertarSync() }
            ACTION_UNDO -> { val id = intent.getIntExtra("id", 0); if (id > 0) { ejecutor.deshacerLocal(id); pedirSyncYa = true; despertarSync() } }
            ACTION_DECIDE -> {
                val id = intent.getIntExtra("id", 0); val crear = intent.getBooleanExtra("crear", false)
                if (id > 0) { prefs.agregarDecision(id, crear); Notifs.cancelar(this, Notifs.BASE_SUGERENCIA + id); pedirSyncYa = true; despertarSync() }
            }
            else -> { // reinicio del sistema (START_STICKY) o arranque sin accion
                if (prefs.escuchaDeseada && !prefs.enPausa) iniciarGrabacion()
                else if (prefs.escuchaDeseada && prefs.enPausa) programarReanudacion(prefs.pausadoHasta)
            }
        }
        actualizarNotificacion()
        return START_STICKY
    }

    private fun mostrarPrimerPlano(): Boolean {
        val n = Notifs.servicio(this, "IgnoAssist", if (textoEstado.isEmpty()) "Iniciando…" else textoEstado, grabando.get(), prefs.enPausa)
        return try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(Notifs.ID_SERVICIO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(Notifs.ID_SERVICIO, n)
            true
        } catch (e: Exception) {
            Notifs.aviso(this, Notifs.ID_REANUDAR, "IgnoAssist no pudo arrancar", "Toca para abrir la app y conceder el permiso de micrófono.", "permisos")
            false
        }
    }

    private fun apagarServicio() {
        vivo.set(false)
        detenerGrabacion()
        prefs.servicioActivo = false
        despertarSubida(); despertarSync()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        vivo.set(false)
        detenerGrabacion()
        prefs.servicioActivo = false
        despertarSubida(); despertarSync()
        encoderThread?.quitSafely()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Si el usuario cierra la app desde recientes, el servicio sigue; Android lo reinicia si lo mata (START_STICKY).
        super.onTaskRemoved(rootIntent)
    }

    // ------------------------------------------------------------------ estado / notificacion
    private fun bateria(): Int {
        return try { (getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (e: Exception) { -1 }
    }

    private fun colaPendiente(): Int = File(filesDir, "pendientes").listFiles { f -> f.name.endsWith(".m4a") || f.name.endsWith(".wav") }?.size ?: 0

    private fun actualizarNotificacion() {
        val pausa = prefs.enPausa
        val b = bateria()
        textoEstado = when {
            grabando.get() -> "Escuchando · ${prefs.subidasHoy} subidas hoy" + (if (colaPendiente() > 0) " · ${colaPendiente()} en cola" else "") + (if (b >= 0) " · $b%" else "")
            pausa -> "En pausa hasta " + SimpleDateFormat("h:mm a", Locale("es", "PR")).format(Date(prefs.pausadoHasta))
            prefs.escuchaDeseada -> "Esperando micrófono…"
            else -> "Apagado"
        }
        try { Notifs.nm(this).notify(Notifs.ID_SERVICIO, Notifs.servicio(this, "IgnoAssist", textoEstado, grabando.get(), pausa)) } catch (e: Exception) { }
    }

    private fun estadoJson(): JSONObject {
        return JSONObject().put("escuchando", grabando.get()).put("pausado_hasta_ms", prefs.pausadoHasta).put("bateria", bateria())
            .put("cola", colaPendiente()).put("subidas_hoy", prefs.subidasHoy).put("version", BuildConfig.VERSION_NAME).put("calendario", prefs.calendarioNombre)
    }

    // ------------------------------------------------------------------ pausa
    private fun pausar(minutos: Int) {
        val hasta = if (minutos > 0) System.currentTimeMillis() + minutos * 60000L else 0L
        if (minutos <= 0) { prefs.escuchaDeseada = false; prefs.pausadoHasta = 0L; detenerGrabacion(); actualizarNotificacion(); return }
        prefs.pausadoHasta = hasta
        detenerGrabacion()
        programarReanudacion(hasta)
        actualizarNotificacion()
    }

    private fun programarReanudacion(hasta: Long) {
        if (hasta <= System.currentTimeMillis()) return
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val i = Intent(this, ActionReceiver::class.java).setAction(ActionReceiver.ACCION_REANUDAR)
        val pi = PendingIntent.getBroadcast(this, 77, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, hasta, pi) } catch (e: Exception) { }
        main.postDelayed({ if (prefs.escuchaDeseada && !prefs.enPausa && !grabando.get()) iniciarGrabacion() }, hasta - System.currentTimeMillis() + 500)
    }

    // ------------------------------------------------------------------ grabacion
    private fun iniciarGrabacion() {
        if (grabando.get()) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Notifs.aviso(this, Notifs.ID_REANUDAR, "Falta el permiso de micrófono", "Toca para abrir IgnoAssist y conceder el permiso.", "permisos")
            return
        }
        grabando.set(true)
        prefs.grabando = true
        try { wakeLock?.setReferenceCounted(false); wakeLock?.acquire() } catch (e: Exception) { }
        hiloGrabacion = Thread({ bucleGrabacion() }, "ia-grabacion").also { it.priority = Thread.MAX_PRIORITY; it.start() }
        Notifs.cancelar(this, Notifs.ID_REANUDAR)
        actualizarNotificacion()
    }

    private fun detenerGrabacion() {
        grabando.set(false)
        prefs.grabando = false
        hiloGrabacion?.let { try { it.join(1500) } catch (e: Exception) { } }
        hiloGrabacion = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { }
    }

    private fun crearRecorder(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val buf = maxOf(min, SAMPLE_RATE * 2)
        for (fuente in intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.DEFAULT)) {
            try {
                val r = AudioRecord(fuente, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf)
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    try { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(r.audioSessionId)?.enabled = true } catch (e: Exception) { }
                    try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId)?.enabled = true } catch (e: Exception) { }
                    return r
                }
                r.release()
            } catch (e: Exception) { }
        }
        return null
    }

    private fun bucleGrabacion() {
        val vad = Vad()
        val frame = ShortArray(FRAME)
        val preRollFrames = 25
        val preRoll = Array(preRollFrames) { ShortArray(FRAME) }
        var preIdx = 0; var preLleno = 0
        var seg = ShortArray(SAMPLE_RATE * (prefs.maxSegundosSegmento + 2))
        var segLen = 0
        var enSegmento = false
        var vozMs = 0; var silencioMs = 0; var ultimoCorteSilencio = -1
        var inicioSegmentoMs = 0L
        var silencioTotalMs = 0L // para detectar microfono mudo
        var fallos = 0
        var rec: AudioRecord = crearRecorder() ?: run {
            grabando.set(false); prefs.grabando = false
            main.post { Notifs.aviso(this, Notifs.ID_REANUDAR, "No pude abrir el micrófono", "Toca para abrir IgnoAssist y reintentar.", "reanudar"); actualizarNotificacion() }
            return
        }
        rec.startRecording()
        val maxSegMs = prefs.maxSegundosSegmento * 1000

        fun finalizarSegmento(descartar: Boolean) {
            if (enSegmento && !descartar && vozMs >= 700) {
                val len = segLen
                val copia = seg.copyOf(len)
                val inicio = inicioSegmentoMs
                val voz = vozMs
                encoderHandler?.post { guardarSegmento(copia, len, inicio, voz) }
            }
            enSegmento = false; segLen = 0; vozMs = 0; silencioMs = 0; ultimoCorteSilencio = -1
        }

        while (grabando.get() && vivo.get()) {
            val n = try { rec.read(frame, 0, FRAME) } catch (e: Exception) { -1 }
            if (n <= 0) {
                fallos++
                if (fallos > 25) { // ~ microfono ocupado (llamada) o error: reabrir
                    try { rec.stop(); rec.release() } catch (e: Exception) { }
                    Thread.sleep(2000)
                    val nuevo = crearRecorder()
                    if (nuevo == null) { fallos = 0; continue }
                    rec = nuevo
                    try { rec.startRecording() } catch (e: Exception) { }
                    fallos = 0
                } else Thread.sleep(40)
                continue
            }
            fallos = 0
            val voz = vad.esVoz(frame, n)
            // Microfono mudo (otra app lo tiene, o restriccion de Android al arrancar en segundo plano)
            if (vad.ultimoDb <= -89.0) silencioTotalMs += 20 else silencioTotalMs = 0
            if (silencioTotalMs >= 20000) {
                silencioTotalMs = 0
                main.post { Notifs.aviso(this, Notifs.ID_REANUDAR, "El micrófono no está dando audio", "Si acabas de reiniciar el teléfono o estás en una llamada, toca aquí para reactivar la escucha.", "reanudar") }
            }
            if (!enSegmento) {
                System.arraycopy(frame, 0, preRoll[preIdx], 0, FRAME); preIdx = (preIdx + 1) % preRollFrames; if (preLleno < preRollFrames) preLleno++
                if (voz) {
                    enSegmento = true
                    inicioSegmentoMs = System.currentTimeMillis() - preLleno * 20L
                    // vuelca el pre-roll (contexto anterior al inicio de la voz)
                    var k = (preIdx - preLleno + preRollFrames) % preRollFrames
                    for (j in 0 until preLleno) { System.arraycopy(preRoll[k], 0, seg, segLen, FRAME); segLen += FRAME; k = (k + 1) % preRollFrames }
                    vozMs = 20; silencioMs = 0
                }
            } else {
                if (segLen + FRAME > seg.size) seg = seg.copyOf(seg.size + SAMPLE_RATE * 10)
                System.arraycopy(frame, 0, seg, segLen, FRAME); segLen += FRAME
                if (voz) { vozMs += 20; silencioMs = 0 } else { silencioMs += 20; if (silencioMs == 300) ultimoCorteSilencio = segLen }
                val durMs = segLen * 1000L / SAMPLE_RATE
                if (silencioMs >= 1400) {
                    // recorta el silencio final a 500 ms
                    segLen -= ((silencioMs - 500) * SAMPLE_RATE / 1000).coerceAtMost(segLen)
                    finalizarSegmento(descartar = vozMs < 700)
                    vad.reiniciar()
                } else if (durMs >= maxSegMs) {
                    if (ultimoCorteSilencio > segLen - SAMPLE_RATE * 15 && ultimoCorteSilencio > 0) {
                        // corta en la ultima pausa y conserva el resto como inicio del siguiente trozo
                        val resto = seg.copyOfRange(ultimoCorteSilencio, segLen)
                        val restoLen = segLen - ultimoCorteSilencio
                        segLen = ultimoCorteSilencio
                        val inicioAnterior = inicioSegmentoMs
                        finalizarSegmento(false)
                        enSegmento = true
                        System.arraycopy(resto, 0, seg, 0, restoLen); segLen = restoLen
                        inicioSegmentoMs = inicioAnterior + (ultimoCorteSilencio * 1000L / SAMPLE_RATE)
                        vozMs = 20
                    } else finalizarSegmento(false)
                }
            }
        }
        if (enSegmento) finalizarSegmento(descartar = vozMs < 700)
        try { rec.stop(); rec.release() } catch (e: Exception) { }
    }

    private fun guardarSegmento(pcm: ShortArray, muestras: Int, inicioMs: Long, vozMs: Int) {
        val dir = File(filesDir, "pendientes"); dir.mkdirs()
        val base = "seg-" + nombreFecha.format(Date(inicioMs)) + "-" + (inicioMs % 1000)
        var archivo = File(dir, "$base.m4a")
        try { AacEncoder.codificar(pcm, muestras, archivo) } catch (e: Exception) {
            archivo.delete(); archivo = File(dir, "$base.wav")
            try { AacEncoder.escribirWav(pcm, muestras, archivo) } catch (e2: Exception) { return }
        }
        val meta = JSONObject().put("inicio_ms", inicioMs).put("duracion_ms", muestras * 1000L / SAMPLE_RATE).put("voz_ms", vozMs)
        File(dir, "$base.json").writeText(meta.toString())
        despertarSubida()
    }

    // ------------------------------------------------------------------ subida
    private fun arrancarHilos() {
        if (hiloSubida?.isAlive != true) hiloSubida = Thread({ bucleSubida() }, "ia-subida").also { it.start() }
        if (hiloSync?.isAlive != true) hiloSync = Thread({ bucleSync() }, "ia-sync").also { it.start() }
    }
    private fun despertarSubida() { synchronized(lockSubida) { lockSubida.notifyAll() } }
    private fun despertarSync() { synchronized(lockSync) { lockSync.notifyAll() } }

    private fun hayRed(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (prefs.soloWifi && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return false
        return true
    }

    private fun bucleSubida() {
        var espera = 15000L
        while (vivo.get()) {
            val dir = File(filesDir, "pendientes")
            val archivos = dir.listFiles { f -> f.name.endsWith(".m4a") || f.name.endsWith(".wav") }?.sortedBy { it.name } ?: emptyList()
            if (archivos.isEmpty() || !prefs.vinculado || !hayRed()) {
                synchronized(lockSubida) { try { lockSubida.wait(if (archivos.isEmpty()) 60000L else 20000L) } catch (e: InterruptedException) { } }
                continue
            }
            val f = archivos.first()
            val metaF = File(dir, f.nameWithoutExtension + ".json")
            val meta = try { JSONObject(metaF.readText()) } catch (e: Exception) { JSONObject().put("inicio_ms", f.lastModified()).put("duracion_ms", 0).put("voz_ms", 0) }
            val campos = mapOf(
                "token" to prefs.token, "inicio_ms" to meta.optLong("inicio_ms").toString(), "duracion_ms" to meta.optLong("duracion_ms").toString(),
                "voz_ms" to meta.optLong("voz_ms").toString(), "bateria" to bateria().toString(), "version" to BuildConfig.VERSION_NAME, "estado" to estadoJson().toString()
            )
            val mime = if (f.name.endsWith(".wav")) "audio/wav" else "audio/mp4"
            val r = Api.subirArchivo(prefs.serverUrl + "api/subir.php", campos, "audio", f, mime)
            if (r.ok) {
                f.delete(); metaF.delete()
                prefs.subidasHoy = prefs.subidasHoy + 1
                prefs.ultimaSubidaMs = System.currentTimeMillis()
                prefs.ultimoError = ""
                espera = 15000L
                r.json?.let { procesarRespuesta(it) }
                main.post { actualizarNotificacion() }
            } else {
                prefs.ultimoError = "Subida: " + (r.error ?: "error")
                if (r.codigo == 401) { // dispositivo desvinculado en el servidor
                    prefs.desvincular(); main.post { Notifs.aviso(this, 5, "IgnoAssist se desvinculó", "Abre la app y vincula de nuevo con un código.", "vincular") }
                } else if (r.codigo == 400 || r.codigo == 413) { // archivo rechazado: no bloquear la cola
                    File(dir, "rechazados").mkdirs(); f.renameTo(File(dir, "rechazados/" + f.name)); metaF.delete()
                }
                synchronized(lockSubida) { try { lockSubida.wait(espera) } catch (e: InterruptedException) { } }
                espera = (espera * 2).coerceAtMost(300000L)
            }
            // limpieza: cola enorme (sin red por dias) -> conserva solo las ultimas 400
            val todos = dir.listFiles { x -> x.name.endsWith(".m4a") || x.name.endsWith(".wav") }?.sortedBy { it.name } ?: emptyList()
            if (todos.size > 400) todos.take(todos.size - 400).forEach { it.delete(); File(dir, it.nameWithoutExtension + ".json").delete() }
        }
    }

    // ------------------------------------------------------------------ sync
    private fun bucleSync() {
        while (vivo.get()) {
            val intervalo = prefs.syncSegundos * 1000L
            synchronized(lockSync) { if (!pedirSyncYa) try { lockSync.wait(intervalo) } catch (e: InterruptedException) { } }
            if (!vivo.get()) break
            pedirSyncYa = false
            if (!prefs.vinculado || !hayRedBasica()) continue
            val resultados = prefs.tomarResultados()
            val decisiones = prefs.tomarDecisiones()
            val r = Api.post(prefs.serverUrl + "api/sync.php", mapOf("token" to prefs.token, "estado" to estadoJson().toString(), "resultados" to resultados.toString(), "decisiones" to decisiones.toString(), "version" to BuildConfig.VERSION_NAME))
            if (r.ok) {
                prefs.ultimoSyncMs = System.currentTimeMillis()
                r.json?.let { procesarRespuesta(it) }
            } else {
                if (resultados.length() > 0) prefs.devolverResultados(resultados)
                if (r.codigo == 401) { prefs.desvincular(); main.post { Notifs.aviso(this, 5, "IgnoAssist se desvinculó", "Abre la app y vincula de nuevo con un código.", "vincular") } }
                else prefs.ultimoError = "Sync: " + (r.error ?: "error")
            }
        }
    }

    private fun hayRedBasica(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Aplica ordenes, sugerencias, comandos y configuracion que devuelve el servidor. */
    private fun procesarRespuesta(j: JSONObject) {
        j.optJSONObject("config")?.let { c ->
            if (c.has("sync_segundos")) prefs.syncSegundos = c.optInt("sync_segundos", 30)
            if (c.has("subir_solo_wifi")) prefs.soloWifi = c.optBoolean("subir_solo_wifi", false)
            if (c.has("max_segundos_segmento")) prefs.maxSegundosSegmento = c.optInt("max_segundos_segmento", 60)
            if (c.has("nombre_asistente")) prefs.nombreAsistente = c.optString("nombre_asistente", "Igno")
            if (c.has("nombre_usuario")) prefs.nombreUsuario = c.optString("nombre_usuario", "")
            if (c.has("pausa_minutos_defecto")) prefs.pausaMinutosDefecto = c.optInt("pausa_minutos_defecto", 30)
        }
        val ordenes = j.optJSONArray("ordenes") ?: JSONArray()
        var ejecutadas = 0
        for (i in 0 until ordenes.length()) {
            val o = ordenes.optJSONObject(i) ?: continue
            val res = ejecutor.ejecutar(o)
            prefs.agregarResultado(res)
            ejecutadas++
        }
        val sugs = j.optJSONArray("sugerencias") ?: JSONArray()
        for (i in 0 until sugs.length()) {
            val s = sugs.optJSONObject(i) ?: continue
            val id = s.optInt("id")
            if (!prefs.sugerenciaNotificada(id)) Notifs.sugerencia(this, id, s.optString("titulo"), ejecutor.cuandoTexto(s.optLong("inicio_ms", 0L), s.optBoolean("todo_el_dia", false)))
        }
        val cmds = j.optJSONArray("comandos") ?: JSONArray()
        for (i in 0 until cmds.length()) {
            val c = cmds.optJSONObject(i) ?: continue
            if (prefs.comandoVisto(c.optInt("id"))) continue
            val datos = c.optJSONObject("datos") ?: JSONObject()
            when (c.optString("tipo")) {
                "pausar" -> main.post { pausar(datos.optInt("minutos", prefs.pausaMinutosDefecto).let { if (it <= 0) prefs.pausaMinutosDefecto else it }) }
                "apagar" -> main.post { prefs.escuchaDeseada = false; prefs.pausadoHasta = 0L; detenerGrabacion(); actualizarNotificacion() }
                "reanudar" -> main.post { prefs.pausadoHasta = 0L; prefs.escuchaDeseada = true; iniciarGrabacion() }
            }
        }
        if (ejecutadas > 0) { pedirSyncYa = true; despertarSync(); main.post { actualizarNotificacion() } }
    }
}
