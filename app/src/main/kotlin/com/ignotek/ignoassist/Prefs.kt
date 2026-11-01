package com.ignotek.ignoassist

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Preferencias y estado local de la app (SharedPreferences). */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("ignoassist", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server_url", "https://ignotek.com/ignoassist/") ?: ""
        set(v) = sp.edit().putString("server_url", v.trim().let { if (it.endsWith("/")) it else "$it/" }).apply()
    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()
    val vinculado: Boolean get() = token.length >= 20

    /** El usuario quiere la escucha encendida (se restaura tras reinicio / cierre). */
    var escuchaDeseada: Boolean
        get() = sp.getBoolean("escucha_deseada", false)
        set(v) = sp.edit().putBoolean("escucha_deseada", v).apply()
    /** Pausa hasta este instante (epoch ms); 0 = sin pausa. */
    var pausadoHasta: Long
        get() = sp.getLong("pausado_hasta", 0L)
        set(v) = sp.edit().putLong("pausado_hasta", v).apply()
    val enPausa: Boolean get() = pausadoHasta > System.currentTimeMillis()

    var calendarioId: Long
        get() = sp.getLong("calendario_id", 0L)
        set(v) = sp.edit().putLong("calendario_id", v).apply()
    var calendarioNombre: String
        get() = sp.getString("calendario_nombre", "") ?: ""
        set(v) = sp.edit().putString("calendario_nombre", v).apply()

    // Configuracion enviada por el servidor
    var syncSegundos: Int
        get() = sp.getInt("sync_segundos", 30)
        set(v) = sp.edit().putInt("sync_segundos", v.coerceIn(10, 600)).apply()
    var soloWifi: Boolean
        get() = sp.getBoolean("solo_wifi", false)
        set(v) = sp.edit().putBoolean("solo_wifi", v).apply()
    var maxSegundosSegmento: Int
        get() = sp.getInt("max_seg", 60)
        set(v) = sp.edit().putInt("max_seg", v.coerceIn(20, 120)).apply()
    var nombreAsistente: String
        get() = sp.getString("nombre_asistente", "Igno") ?: "Igno"
        set(v) = sp.edit().putString("nombre_asistente", v).apply()
    var nombreUsuario: String
        get() = sp.getString("nombre_usuario", "") ?: ""
        set(v) = sp.edit().putString("nombre_usuario", v).apply()
    var pausaMinutosDefecto: Int
        get() = sp.getInt("pausa_min", 30)
        set(v) = sp.edit().putInt("pausa_min", v).apply()

    // Contadores
    var subidasHoy: Int
        get() = if (sp.getString("dia_contador", "") == hoy()) sp.getInt("subidas_hoy", 0) else 0
        set(v) = sp.edit().putString("dia_contador", hoy()).putInt("subidas_hoy", v).apply()
    var ultimaSubidaMs: Long
        get() = sp.getLong("ultima_subida", 0L)
        set(v) = sp.edit().putLong("ultima_subida", v).apply()
    var ultimoSyncMs: Long
        get() = sp.getLong("ultimo_sync", 0L)
        set(v) = sp.edit().putLong("ultimo_sync", v).apply()
    var ultimoError: String
        get() = sp.getString("ultimo_error", "") ?: ""
        set(v) = sp.edit().putString("ultimo_error", v).apply()
    var servicioActivo: Boolean
        get() = sp.getBoolean("servicio_activo", false)
        set(v) = sp.edit().putBoolean("servicio_activo", v).apply()
    var grabando: Boolean
        get() = sp.getBoolean("grabando", false)
        set(v) = sp.edit().putBoolean("grabando", v).apply()

    private fun hoy(): String {
        val c = java.util.Calendar.getInstance()
        return "" + c.get(java.util.Calendar.YEAR) + "-" + c.get(java.util.Calendar.DAY_OF_YEAR)
    }

    // ---- Resultados pendientes de reportar al servidor ----
    @Synchronized fun agregarResultado(r: JSONObject) {
        val arr = JSONArray(sp.getString("resultados", "[]") ?: "[]")
        arr.put(r)
        sp.edit().putString("resultados", arr.toString()).apply()
    }
    @Synchronized fun tomarResultados(): JSONArray {
        val arr = JSONArray(sp.getString("resultados", "[]") ?: "[]")
        sp.edit().putString("resultados", "[]").apply()
        return arr
    }
    @Synchronized fun devolverResultados(arr: JSONArray) {
        val actual = JSONArray(sp.getString("resultados", "[]") ?: "[]")
        for (i in 0 until arr.length()) actual.put(arr.getJSONObject(i))
        sp.edit().putString("resultados", actual.toString()).apply()
    }
    @Synchronized fun agregarDecision(accionId: Int, crear: Boolean) {
        val arr = JSONArray(sp.getString("decisiones", "[]") ?: "[]")
        arr.put(JSONObject().put("accion_id", accionId).put("crear", crear))
        sp.edit().putString("decisiones", arr.toString()).apply()
    }
    @Synchronized fun tomarDecisiones(): JSONArray {
        val arr = JSONArray(sp.getString("decisiones", "[]") ?: "[]")
        sp.edit().putString("decisiones", "[]").apply()
        return arr
    }

    // ---- Historial local de acciones ejecutadas (para la pantalla y para deshacer) ----
    @Synchronized fun registrarAccion(o: JSONObject) {
        val arr = JSONArray(sp.getString("acciones", "[]") ?: "[]")
        val nuevo = JSONArray()
        nuevo.put(o)
        var n = 1
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            if (it.optInt("id") == o.optInt("id")) continue
            if (n >= 60) break
            nuevo.put(it); n++
        }
        sp.edit().putString("acciones", nuevo.toString()).apply()
    }
    @Synchronized fun acciones(): JSONArray = JSONArray(sp.getString("acciones", "[]") ?: "[]")
    @Synchronized fun accion(id: Int): JSONObject? {
        val arr = acciones()
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.optInt("id") == id) return o }
        return null
    }
    @Synchronized fun marcarAccion(id: Int, estado: String) {
        val a = accion(id) ?: return
        a.put("estado", estado)
        registrarAccion(a)
    }
    @Synchronized fun sugerenciaNotificada(id: Int): Boolean {
        val s = sp.getStringSet("sug_notif", emptySet()) ?: emptySet()
        if (s.contains(id.toString())) return true
        val n = HashSet(s); n.add(id.toString())
        if (n.size > 200) n.clear()
        sp.edit().putStringSet("sug_notif", n).apply()
        return false
    }
    @Synchronized fun comandoVisto(id: Int): Boolean {
        val s = sp.getStringSet("cmd_vistos", emptySet()) ?: emptySet()
        if (s.contains(id.toString())) return true
        val n = HashSet(s); n.add(id.toString())
        if (n.size > 200) n.clear()
        sp.edit().putStringSet("cmd_vistos", n).apply()
        return false
    }

    fun desvincular() { sp.edit().remove("token").putBoolean("escucha_deseada", false).putLong("pausado_hasta", 0L).putString("resultados", "[]").putString("decisiones", "[]").apply() }
}
