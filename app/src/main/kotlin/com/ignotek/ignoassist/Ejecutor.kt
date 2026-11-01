package com.ignotek.ignoassist

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Ejecuta en el telefono las ordenes del servidor: calendario, alarmas de recordatorio, tareas y notas. */
class Ejecutor(private val ctx: Context, private val prefs: Prefs) {

    private val fmt = SimpleDateFormat("EEE d MMM, h:mm a", Locale("es", "PR"))
    private val fmtDia = SimpleDateFormat("EEE d MMM", Locale("es", "PR"))

    fun tieneCalendario(): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun cuandoTexto(inicioMs: Long, todoElDia: Boolean): String {
        if (inicioMs <= 0) return "Sin fecha"
        val d = Date(inicioMs)
        return if (todoElDia) fmtDia.format(d) else fmt.format(d)
    }

    // ------------------------------------------------------------------ calendarios
    data class Cal(val id: Long, val nombre: String, val cuenta: String, val tipo: String, val primario: Boolean)

    fun listaCalendarios(): List<Cal> {
        if (!tieneCalendario()) return emptyList()
        val out = ArrayList<Cal>()
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.VISIBLE)
        try {
            ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val acceso = c.getInt(4)
                    if (acceso < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                    out.add(Cal(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getInt(5) == 1))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    fun calendarioElegido(): Cal? {
        val lista = listaCalendarios()
        if (lista.isEmpty()) return null
        val guardado = prefs.calendarioId
        if (guardado > 0) lista.firstOrNull { it.id == guardado }?.let { return it }
        val auto = lista.firstOrNull { it.tipo == "com.google" && it.primario } ?: lista.firstOrNull { it.tipo == "com.google" && it.nombre == it.cuenta }
            ?: lista.firstOrNull { it.tipo == "com.google" } ?: lista.firstOrNull { it.primario } ?: lista.first()
        prefs.calendarioId = auto.id; prefs.calendarioNombre = auto.nombre
        return auto
    }

    // ------------------------------------------------------------------ eventos
    private fun valoresEvento(calId: Long, o: JSONObject): ContentValues {
        val v = ContentValues()
        val todoDia = o.optBoolean("todo_el_dia", false)
        var ini = o.optLong("inicio_ms", 0L)
        var fin = o.optLong("fin_ms", 0L)
        if (todoDia) {
            // Para eventos de dia completo Android exige medianoche UTC del dia.
            val local = Calendar.getInstance(); local.timeInMillis = ini
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            utc.clear(); utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
            ini = utc.timeInMillis; fin = ini + 86400000L
            v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        } else {
            if (fin <= ini) fin = ini + 3600000L
            v.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        v.put(CalendarContract.Events.CALENDAR_ID, calId)
        v.put(CalendarContract.Events.TITLE, o.optString("titulo"))
        v.put(CalendarContract.Events.DTSTART, ini)
        v.put(CalendarContract.Events.DTEND, fin)
        v.put(CalendarContract.Events.ALL_DAY, if (todoDia) 1 else 0)
        val desc = StringBuilder(o.optString("descripcion"))
        val personas = o.optString("personas")
        if (personas.isNotBlank()) desc.append("\nCon: ").append(personas)
        desc.append("\n— Creado por IgnoAssist")
        v.put(CalendarContract.Events.DESCRIPTION, desc.toString().trim())
        val lugar = o.optString("lugar")
        if (lugar.isNotBlank()) v.put(CalendarContract.Events.EVENT_LOCATION, lugar)
        v.put(CalendarContract.Events.HAS_ALARM, 1)
        return v
    }

    private fun crearEvento(o: JSONObject, minutosAviso: Int): Long {
        val cal = calendarioElegido() ?: throw IllegalStateException("No hay un calendario disponible (¿permiso de calendario?)")
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, valoresEvento(cal.id, o)) ?: throw IllegalStateException("El calendario no aceptó el evento")
        val id = ContentUris.parseId(uri)
        try {
            val r = ContentValues()
            r.put(CalendarContract.Reminders.EVENT_ID, id)
            r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            r.put(CalendarContract.Reminders.MINUTES, if (minutosAviso > 0) minutosAviso else 30)
            ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, r)
        } catch (e: Exception) { }
        return id
    }

    private fun actualizarEvento(id: Long, o: JSONObject): Boolean {
        val cal = calendarioElegido() ?: return false
        val v = valoresEvento(cal.id, o)
        v.remove(CalendarContract.Events.CALENDAR_ID)
        val n = ctx.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), v, null, null)
        return n > 0
    }

    private fun borrarEvento(id: Long): Boolean {
        return try { ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) >= 0 } catch (e: Exception) { false }
    }

    // ------------------------------------------------------------------ alarmas (recordatorios)
    private fun pendingAlarma(id: Int, titulo: String, texto: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction("com.ignotek.ignoassist.RECORDATORIO").putExtra("id", id).putExtra("titulo", titulo).putExtra("texto", texto)
        return PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun programarAlarma(id: Int, cuandoMs: Long, titulo: String, texto: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingAlarma(id, titulo, texto)
        val cuando = if (cuandoMs < System.currentTimeMillis() + 5000) System.currentTimeMillis() + 5000 else cuandoMs
        // setAlarmClock no requiere permiso de alarmas exactas y despierta el telefono aunque este en reposo.
        am.setAlarmClock(AlarmManager.AlarmClockInfo(cuando, Notifs.abrirApp(ctx, id)), pi)
    }

    fun cancelarAlarma(id: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingAlarma(id, "", ""))
    }

    // ------------------------------------------------------------------ ordenes
    /** Ejecuta una orden y devuelve el resultado que se reporta al servidor. */
    fun ejecutar(o: JSONObject): JSONObject {
        val id = o.optInt("id")
        val op = o.optString("operacion", "crear")
        val tipo = o.optString("tipo", "nota")
        val titulo = o.optString("titulo")
        val res = JSONObject().put("accion_id", id).put("operacion", op)
        val refAnterior = o.optString("telefono_ref", "")
        val minAviso = o.optInt("recordatorio_min", 30)
        val recAlCal = o.optBoolean("recordatorios_al_calendario", true)
        val ini = o.optLong("inicio_ms", 0L)
        val cuando = cuandoTexto(ini, o.optBoolean("todo_el_dia", false))
        var telefono = o.optString("telefono", "")
        var telefonoDeContactos = false
        if (telefono.isBlank() && op != "anular") {
            val buscado = buscarEnContactos(o.optString("personas", ""))
            if (buscado != null) { telefono = buscado; telefonoDeContactos = true }
        }
        try {
            when (op) {
                "anular" -> {
                    deshacerRef(refAnterior, id)
                    Notifs.cancelar(ctx, Notifs.BASE_ACCION + id)
                    prefs.marcarAccion(id, "anulada")
                    res.put("ok", true)
                }
                "actualizar", "crear" -> {
                    if (op == "actualizar") deshacerRef(refAnterior, id)
                    val refs = ArrayList<String>()
                    when (tipo) {
                        "evento" -> {
                            if (ini <= 0) throw IllegalStateException("evento sin fecha")
                            refs.add("cal:" + crearEvento(o, minAviso))
                        }
                        "recordatorio", "tarea" -> {
                            if (ini > 0) {
                                programarAlarma(id, ini, titulo, o.optString("descripcion").ifBlank { "Recordatorio de IgnoAssist" })
                                refs.add("al:$id")
                                if (recAlCal && tieneCalendario()) {
                                    val copia = JSONObject(o.toString())
                                    copia.put("fin_ms", ini + 15 * 60000L).put("todo_el_dia", false)
                                    try { refs.add("cal:" + crearEvento(copia, 0)) } catch (e: Exception) { }
                                }
                            } else {
                                refs.add("nota:$id")
                            }
                        }
                        else -> refs.add("nota:$id")
                    }
                    if (telefono.isNotBlank()) o.put("telefono", telefono)
                    guardarLocal(o, refs.joinToString(";"))
                    Notifs.accionCreada(ctx, id, titulo, cuando, tipo, true, telefono)
                    res.put("ok", true).put("ref", refs.joinToString(";"))
                    if (telefonoDeContactos) res.put("telefono", telefono)
                }
                else -> res.put("ok", false).put("error", "operacion desconocida $op")
            }
        } catch (e: Exception) {
            res.put("ok", false).put("error", (e.message ?: e.javaClass.simpleName).take(300))
        }
        return res
    }

    private fun guardarLocal(o: JSONObject, ref: String) {
        val l = JSONObject()
        l.put("id", o.optInt("id")).put("tipo", o.optString("tipo")).put("titulo", o.optString("titulo")).put("descripcion", o.optString("descripcion"))
            .put("inicio_ms", o.optLong("inicio_ms", 0L)).put("todo_el_dia", o.optBoolean("todo_el_dia", false)).put("lugar", o.optString("lugar"))
            .put("personas", o.optString("personas")).put("telefono", o.optString("telefono", "")).put("url_conversacion", o.optString("url_conversacion", ""))
            .put("ref", ref).put("estado", "creada").put("creado_ms", System.currentTimeMillis())
        prefs.registrarAccion(l)
    }

    fun tieneContactos(): Boolean = ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Busca el telefono de la primera persona de la lista en los contactos del telefono (nombre completo o nombre+apellido contenidos). */
    fun buscarEnContactos(personas: String): String? {
        if (!tieneContactos() || personas.isBlank()) return null
        for (nombre in personas.split(",").map { it.trim() }.filter { it.length >= 3 }) {
            val tel = buscarContacto(nombre)
            if (tel != null) return tel
        }
        return null
    }

    private fun buscarContacto(nombre: String): String? {
        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
        val tokens = nombre.split(" ").filter { it.length >= 3 }
        if (tokens.isEmpty()) return null
        val candidatos = ArrayList<Pair<String, String>>()
        try {
            ctx.contentResolver.query(uri, proj, android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%" + tokens[0] + "%"), null)?.use { c ->
                while (c.moveToNext()) { candidatos.add(Pair(c.getString(0) ?: "", c.getString(1) ?: "")) }
            }
        } catch (e: Exception) { return null }
        fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("[^a-z0-9 ]"), "").trim()
        val n = norm(nombre)
        val exacto = candidatos.firstOrNull { norm(it.first) == n }
        if (exacto != null) return exacto.second
        val parciales = candidatos.filter { c -> val cn = norm(c.first); tokens.all { cn.contains(norm(it)) } }
        val nombres = parciales.map { norm(it.first) }.distinct()
        return if (nombres.size == 1) parciales.first().second else null
    }

    private fun deshacerRef(ref: String, id: Int) {
        for (parte in ref.split(";")) {
            val p = parte.trim()
            when {
                p.startsWith("cal:") -> p.substring(4).toLongOrNull()?.let { borrarEvento(it) }
                p.startsWith("al:") -> cancelarAlarma(id)
            }
        }
        Notifs.cancelar(ctx, Notifs.BASE_RECORDATORIO + id)
    }

    /** Deshacer desde la notificacion o la app: borra en el telefono y reporta anulada. */
    fun deshacerLocal(id: Int): Boolean {
        val a = prefs.accion(id) ?: return false
        deshacerRef(a.optString("ref"), id)
        prefs.marcarAccion(id, "anulada")
        prefs.agregarResultado(JSONObject().put("accion_id", id).put("operacion", "anular").put("ok", true))
        Notifs.cancelar(ctx, Notifs.BASE_ACCION + id)
        return true
    }
}
