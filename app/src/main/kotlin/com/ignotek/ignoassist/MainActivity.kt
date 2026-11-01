package com.ignotek.ignoassist

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var ejecutor: Ejecutor
    private lateinit var root: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var vinculando = false
    private val poll = object : Runnable { override fun run() { if (prefs.vinculado) render(); handler.postDelayed(this, 3000) } }

    private val TEAL = Color.parseColor("#0D6E66"); private val TEAL_SUAVE = Color.parseColor("#E6F3F2"); private val INK = Color.parseColor("#0F172A")
    private val MUTE = Color.parseColor("#64748B"); private val FONDO = Color.parseColor("#F3F6F6"); private val OK = Color.parseColor("#15803D")
    private val WARN = Color.parseColor("#B45309"); private val ERR = Color.parseColor("#B91C1C")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this); ejecutor = Ejecutor(this, prefs)
        Notifs.crearCanales(this)
        val scroll = ScrollView(this); scroll.setBackgroundColor(FONDO); scroll.isFillViewport = true
        root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL; root.setPadding(dp(14), dp(14), dp(14), dp(28))
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        window.statusBarColor = TEAL
        render()
        manejarExtra(intent)
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); manejarExtra(intent) }
    override fun onResume() { super.onResume(); render(); handler.removeCallbacks(poll); handler.postDelayed(poll, 3000) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(poll) }

    private fun manejarExtra(i: Intent?) {
        when (i?.getStringExtra("accion")) {
            "reanudar" -> if (prefs.vinculado) encender()
            "permisos" -> pedirPermisos()
        }
        i?.removeExtra("accion")
    }

    // ------------------------------------------------------------------ helpers UI
    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun texto(s: CharSequence, sp: Float = 15f, color: Int = INK, negrita: Boolean = false, centrado: Boolean = false): TextView {
        val t = TextView(this); t.text = s; t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); t.setTextColor(color)
        if (negrita) t.typeface = Typeface.DEFAULT_BOLD
        if (centrado) t.gravity = Gravity.CENTER
        t.setLineSpacing(0f, 1.15f)
        return t
    }
    private fun card(): LinearLayout {
        val c = LinearLayout(this); c.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable(); bg.cornerRadius = dp(16).toFloat(); bg.setColor(Color.WHITE)
        c.background = bg; c.elevation = dp(4).toFloat(); c.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(12)
        c.layoutParams = lp
        c.alpha = 0f; c.translationY = dp(8).toFloat(); c.animate().alpha(1f).translationY(0f).setDuration(260).start()
        return c
    }
    private fun boton(label: String, fondo: Int, letra: Int = Color.WHITE, grande: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(this); b.text = label; b.setTextColor(letra); b.isAllCaps = false; b.typeface = Typeface.DEFAULT_BOLD
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (grande) 18f else 14f)
        val bg = GradientDrawable(); bg.cornerRadius = dp(if (grande) 16 else 12).toFloat(); bg.setColor(fondo); b.background = bg
        b.setPadding(dp(16), dp(if (grande) 16 else 10), dp(16), dp(if (grande) 16 else 10)); b.stateListAnimator = null; b.elevation = 0f
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.topMargin = dp(8)
        b.layoutParams = lp
        return b
    }
    private fun fila(vararg vistas: View): LinearLayout {
        val f = LinearLayout(this); f.orientation = LinearLayout.HORIZONTAL
        for (v in vistas) { val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); lp.rightMargin = dp(6); lp.topMargin = dp(8); v.layoutParams = lp; f.addView(v) }
        return f
    }
    private fun chip(s: String, color: Int): TextView {
        val t = texto(s, 12f, color, true); val bg = GradientDrawable(); bg.cornerRadius = dp(99).toFloat(); bg.setColor(Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)))
        t.background = bg; t.setPadding(dp(10), dp(4), dp(10), dp(4)); return t
    }
    private fun entrada(hint: String, valor: String, tipo: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val e = EditText(this); e.hint = hint; e.setText(valor); e.inputType = tipo; e.setTextColor(INK); e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        val bg = GradientDrawable(); bg.cornerRadius = dp(12).toFloat(); bg.setColor(Color.WHITE); bg.setStroke(dp(1), Color.parseColor("#E2E8F0")); e.background = bg
        e.setPadding(dp(12), dp(12), dp(12), dp(12))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.topMargin = dp(8); e.layoutParams = lp
        return e
    }
    private fun encabezado(): View {
        val f = LinearLayout(this); f.orientation = LinearLayout.HORIZONTAL; f.gravity = Gravity.CENTER_VERTICAL; f.setPadding(0, 0, 0, dp(10))
        val m = texto("Igno", 24f, INK, true); val a = texto("Assist", 24f, TEAL, true)
        val sub = texto("  · Ignotek", 13f, MUTE)
        f.addView(m); f.addView(a); f.addView(sub)
        return f
    }

    // ------------------------------------------------------------------ pantallas
    private fun render() {
        if (vinculando) return
        root.removeAllViews()
        root.addView(encabezado())
        if (!prefs.vinculado) renderVincular() else renderPrincipal()
    }

    private fun renderVincular() {
        val c = card()
        c.addView(texto("Vincular con tu plataforma", 18f, INK, true))
        c.addView(texto("En el panel web (Ajustes → Teléfono) pulsa «Generar código» y escríbelo aquí. El código vale 10 minutos.", 14f, MUTE))
        val url = entrada("Dirección del servidor", prefs.serverUrl, InputType.TYPE_TEXT_VARIATION_URI)
        val codigo = entrada("Código de 6 dígitos", "", InputType.TYPE_CLASS_NUMBER)
        c.addView(url); c.addView(codigo)
        c.addView(boton("Vincular", TEAL, grande = true) {
            val u = url.text.toString().trim(); val cod = codigo.text.toString().trim()
            if (!u.startsWith("http") || cod.length != 6) { Toast.makeText(this, "Revisa la dirección y el código de 6 dígitos", Toast.LENGTH_LONG).show(); return@boton }
            prefs.serverUrl = u
            vinculando = true
            Thread {
                val r = Api.post(prefs.serverUrl + "api/vincular.php", mapOf("codigo" to cod, "nombre" to (Build.MANUFACTURER + " " + Build.MODEL).trim(), "version" to BuildConfig.VERSION_NAME))
                runOnUiThread {
                    vinculando = false
                    if (r.ok) {
                        prefs.token = r.json!!.optString("token")
                        r.json.optJSONObject("config")?.let { cf -> prefs.syncSegundos = cf.optInt("sync_segundos", 30); prefs.soloWifi = cf.optBoolean("subir_solo_wifi", false); prefs.maxSegundosSegmento = cf.optInt("max_segundos_segmento", 60); prefs.nombreAsistente = cf.optString("nombre_asistente", "Igno"); prefs.nombreUsuario = cf.optString("nombre_usuario", "") }
                        Toast.makeText(this, "¡Vinculado! Ahora los permisos.", Toast.LENGTH_LONG).show()
                        render(); pedirPermisos()
                    } else Toast.makeText(this, "No se pudo vincular: " + (r.error ?: "sin conexión"), Toast.LENGTH_LONG).show()
                }
            }.start()
        })
        root.addView(c)
    }

    private fun faltaPermiso(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_DENIED
    private fun permisosFaltantes(): List<String> {
        val l = ArrayList<String>()
        if (faltaPermiso(Manifest.permission.RECORD_AUDIO)) l.add(Manifest.permission.RECORD_AUDIO)
        if (faltaPermiso(Manifest.permission.READ_CALENDAR)) l.add(Manifest.permission.READ_CALENDAR)
        if (faltaPermiso(Manifest.permission.WRITE_CALENDAR)) l.add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33 && faltaPermiso(Manifest.permission.POST_NOTIFICATIONS)) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l
    }
    private fun pedirPermisos() { val f = permisosFaltantes(); if (f.isNotEmpty()) requestPermissions(f.toTypedArray(), 1) }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) pedirBateria()
    }
    private fun pedirBateria() {
        try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        catch (e: Exception) { try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { } }
    }

    private fun encender() {
        if (faltaPermiso(Manifest.permission.RECORD_AUDIO)) { pedirPermisos(); return }
        ListenService.lanzar(this, ListenService.ACTION_START)
        handler.postDelayed({ render() }, 600)
    }

    private fun renderPrincipal() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val escuchando = prefs.grabando && prefs.servicioActivo
        val pausa = prefs.enPausa
        val faltan = permisosFaltantes()

        // --- estado
        val c = card()
        val f = LinearLayout(this); f.orientation = LinearLayout.HORIZONTAL; f.gravity = Gravity.CENTER_VERTICAL
        val anillo = TextView(this); anillo.text = "🎙"; anillo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f); anillo.gravity = Gravity.CENTER
        val abg = GradientDrawable(); abg.shape = GradientDrawable.OVAL; abg.setColor(if (escuchando) TEAL else TEAL_SUAVE); anillo.background = abg
        anillo.layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).also { it.rightMargin = dp(14) }
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        val estadoTxt = when { escuchando -> "Escuchando"; pausa -> "En pausa hasta " + SimpleDateFormat("h:mm a", Locale("es", "PR")).format(Date(prefs.pausadoHasta)); prefs.escuchaDeseada -> "Activando micrófono…"; else -> "Apagado" }
        col.addView(texto(estadoTxt, 20f, if (escuchando) TEAL else INK, true))
        val detalle = StringBuilder()
        detalle.append(prefs.subidasHoy).append(" trozos subidos hoy")
        if (prefs.ultimaSubidaMs > 0) detalle.append(" · última ").append(SimpleDateFormat("h:mm a", Locale("es", "PR")).format(Date(prefs.ultimaSubidaMs)))
        col.addView(texto(detalle.toString(), 13f, MUTE))
        if (prefs.ultimoError.isNotEmpty()) col.addView(texto("⚠ " + prefs.ultimoError, 12f, WARN))
        f.addView(anillo); f.addView(col); c.addView(f)
        if (escuchando) {
            c.addView(boton("Apagar la escucha", INK, grande = true) { ListenService.lanzar(this, ListenService.ACTION_STOP); handler.postDelayed({ render() }, 800) })
            c.addView(fila(boton("Pausar 30 min", Color.parseColor("#F1F5F9"), INK) { ListenService.lanzar(this, ListenService.ACTION_PAUSE) { it.putExtra("minutos", 30) }; handler.postDelayed({ render() }, 800) },
                            boton("Pausar 1 hora", Color.parseColor("#F1F5F9"), INK) { ListenService.lanzar(this, ListenService.ACTION_PAUSE) { it.putExtra("minutos", 60) }; handler.postDelayed({ render() }, 800) }))
        } else {
            c.addView(boton(if (pausa) "Reanudar ahora" else "Encender la escucha", TEAL, grande = true) { encender() })
            if (pausa) c.addView(boton("Apagar del todo", Color.parseColor("#F1F5F9"), INK) { ListenService.lanzar(this, ListenService.ACTION_STOP); handler.postDelayed({ render() }, 800) })
        }
        c.addView(texto("Mientras escucho, todo lo que acuerdes («nos vemos el lunes a las 2») aparece solo en tu calendario y recordatorios. Di «${prefs.nombreAsistente}, pausa 30 minutos» o «${prefs.nombreAsistente}, recuérdame…» para darme órdenes por voz.", 13f, MUTE))
        root.addView(c)

        // --- permisos / bateria
        val sinBateria = !pm.isIgnoringBatteryOptimizations(packageName)
        if (faltan.isNotEmpty() || sinBateria) {
            val p = card()
            p.addView(texto("Para funcionar todo el día", 16f, INK, true))
            if (faltan.isNotEmpty()) { p.addView(texto("Faltan permisos: " + faltan.joinToString(", ") { it.substringAfterLast('.').lowercase().replace('_', ' ') }, 13f, ERR)); p.addView(boton("Conceder permisos", TEAL) { pedirPermisos() }) }
            if (sinBateria) { p.addView(texto("Samsung apaga las apps en segundo plano. Permite que IgnoAssist no tenga restricciones de batería.", 13f, WARN)); p.addView(boton("Quitar restricción de batería", INK) { pedirBateria() }) }
            root.addView(p)
        }

        // --- contactos (para el boton Llamar)
        if (!ejecutor.tieneContactos()) {
            val ct = card()
            ct.addView(texto("Contactos del teléfono", 16f, INK, true))
            ct.addView(texto("Con acceso a tus contactos, cuando el asistente agende algo con alguien que tienes guardado, la notificación trae el botón «Llamar» y el teléfono queda en Personas.", 13f, MUTE))
            ct.addView(boton("Conectar contactos", TEAL) { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 2) })
            root.addView(ct)
        }

        // --- calendario
        val cal = ejecutor.calendarioElegido()
        val k = card()
        k.addView(texto("Calendario destino", 16f, INK, true))
        k.addView(texto(if (cal != null) cal.nombre + " (" + cal.cuenta + ")" else if (faltan.contains(Manifest.permission.WRITE_CALENDAR)) "Concede el permiso de calendario" else "No se encontró ningún calendario", 14f, if (cal != null) TEAL else WARN))
        k.addView(texto("Los eventos van a este calendario; si es el de Google, se sincronizan solos con Google Calendar.", 13f, MUTE))
        k.addView(boton("Cambiar calendario", Color.parseColor("#F1F5F9"), INK) { elegirCalendario() })
        root.addView(k)

        // --- acciones recientes
        val a = card()
        a.addView(texto("Creado por el asistente", 16f, INK, true))
        val arr = prefs.acciones()
        if (arr.length() == 0) a.addView(texto("Todavía nada. Cuando acuerdes algo en una conversación, aparecerá aquí.", 13f, MUTE))
        for (i in 0 until minOf(arr.length(), 20)) {
            val o = arr.getJSONObject(i)
            val filaA = LinearLayout(this); filaA.orientation = LinearLayout.HORIZONTAL; filaA.gravity = Gravity.CENTER_VERTICAL; filaA.setPadding(0, dp(8), 0, dp(8))
            val ico = texto(when (o.optString("tipo")) { "evento" -> "📅"; "recordatorio" -> "⏰"; "tarea" -> "✅"; else -> "📝" }, 20f)
            ico.layoutParams = LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT)
            val colA = LinearLayout(this); colA.orientation = LinearLayout.VERTICAL; colA.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val anulada = o.optString("estado") == "anulada"
            val tit = texto(o.optString("titulo"), 15f, if (anulada) MUTE else INK, true)
            if (anulada) tit.paintFlags = tit.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            colA.addView(tit)
            colA.addView(texto(ejecutor.cuandoTexto(o.optLong("inicio_ms", 0L), o.optBoolean("todo_el_dia", false)) + (if (o.optString("lugar").isNotBlank()) " · " + o.optString("lugar") else ""), 12f, if (anulada) MUTE else TEAL))
            filaA.addView(ico); filaA.addView(colA)
            val telA = o.optString("telefono", "")
            if (telA.isNotBlank() && !anulada) { val l = texto("📞", 18f); l.setPadding(dp(6), dp(6), dp(6), dp(6)); l.setOnClickListener { try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + telA.filter { ch -> ch.isDigit() || ch == '+' }))) } catch (e: Exception) { } }; filaA.addView(l) }
            if (!anulada) { val d = texto("Deshacer", 12f, ERR, true); d.setPadding(dp(8), dp(6), dp(4), dp(6)); d.setOnClickListener { confirmarDeshacer(o) }; filaA.addView(d) }
            a.addView(filaA)
        }
        root.addView(a)

        // --- guia Samsung
        val g = card()
        g.addView(texto("Ajustes recomendados (Samsung One UI)", 16f, INK, true))
        g.addView(texto("1) Ajustes → Aplicaciones → IgnoAssist → Batería → «Sin restricciones».\n2) Ajustes → Batería → Límites de uso en segundo plano → «Aplicaciones que nunca se suspenden» → añade IgnoAssist.\n3) No uses «Optimización de apps» o «Modo de ahorro de energía» extremos: pueden cerrar el micrófono.\n4) Mosaico rápido: añade «IgnoAssist» a los ajustes rápidos para encender/apagar con un toque.", 13f, MUTE))
        g.addView(fila(boton("Ajustes de la app", Color.parseColor("#F1F5F9"), INK) { try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e: Exception) { } },
                        boton("Batería", Color.parseColor("#F1F5F9"), INK) { pedirBateria() }))
        root.addView(g)

        // --- pie
        val pie = card()
        pie.addView(texto("Panel web: " + prefs.serverUrl, 12f, MUTE))
        pie.addView(texto("Último sync: " + (if (prefs.ultimoSyncMs > 0) SimpleDateFormat("h:mm:ss a", Locale("es", "PR")).format(Date(prefs.ultimoSyncMs)) else "—") + " · cada " + prefs.syncSegundos + " s · app v" + BuildConfig.VERSION_NAME, 12f, MUTE))
        pie.addView(fila(boton("Abrir panel web", TEAL) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(prefs.serverUrl))) } catch (e: Exception) { } },
                         boton("Sincronizar ahora", Color.parseColor("#F1F5F9"), INK) { ListenService.lanzar(this, ListenService.ACTION_SYNC); Toast.makeText(this, "Sincronizando…", Toast.LENGTH_SHORT).show() }))
        pie.addView(boton("Desvincular este teléfono", Color.parseColor("#FEE2E2"), ERR) {
            AlertDialog.Builder(this).setTitle("¿Desvincular?").setMessage("Se apaga la escucha y tendrás que vincular con un código nuevo.")
                .setPositiveButton("Desvincular") { _, _ -> ListenService.lanzar(this, ListenService.ACTION_STOP); prefs.desvincular(); render() }.setNegativeButton("Cancelar", null).show()
        })
        root.addView(pie)
    }

    private fun elegirCalendario() {
        val lista = ejecutor.listaCalendarios()
        if (lista.isEmpty()) { Toast.makeText(this, "No hay calendarios disponibles (revisa el permiso)", Toast.LENGTH_LONG).show(); return }
        val nombres = lista.map { it.nombre + "\n" + it.cuenta }.toTypedArray()
        val actual = lista.indexOfFirst { it.id == prefs.calendarioId }
        AlertDialog.Builder(this).setTitle("Calendario para los eventos").setSingleChoiceItems(nombres, actual) { d, i ->
            prefs.calendarioId = lista[i].id; prefs.calendarioNombre = lista[i].nombre; d.dismiss(); render()
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun confirmarDeshacer(o: JSONObject) {
        AlertDialog.Builder(this).setTitle("¿Deshacer?").setMessage("Se quita «" + o.optString("titulo") + "» del teléfono y del asistente.")
            .setPositiveButton("Deshacer") { _, _ ->
                val id = o.optInt("id")
                if (prefs.servicioActivo) ListenService.lanzar(this, ListenService.ACTION_UNDO) { it.putExtra("id", id) }
                else { ejecutor.deshacerLocal(id); ListenService.lanzar(this, ListenService.ACTION_SYNC) }
                handler.postDelayed({ render() }, 500)
            }.setNegativeButton("Cancelar", null).show()
    }
}
