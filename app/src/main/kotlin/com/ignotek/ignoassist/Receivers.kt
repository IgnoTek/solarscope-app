package com.ignotek.ignoassist

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifs.crearCanales(this)
    }
}

/** Botones de las notificaciones (pausar / reanudar / apagar / deshacer / crear / ignorar / posponer). */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACCION_PAUSAR = "com.ignotek.ignoassist.N_PAUSAR"
        const val ACCION_REANUDAR = "com.ignotek.ignoassist.N_REANUDAR"
        const val ACCION_APAGAR = "com.ignotek.ignoassist.N_APAGAR"
        const val ACCION_DESHACER = "com.ignotek.ignoassist.N_DESHACER"
        const val ACCION_CREAR = "com.ignotek.ignoassist.N_CREAR"
        const val ACCION_IGNORAR = "com.ignotek.ignoassist.N_IGNORAR"
        const val ACCION_POSPONER = "com.ignotek.ignoassist.N_POSPONER"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val prefs = Prefs(ctx)
        when (intent.action) {
            ACCION_PAUSAR -> ListenService.lanzar(ctx, ListenService.ACTION_PAUSE) { it.putExtra("minutos", prefs.pausaMinutosDefecto) }
            ACCION_REANUDAR -> if (prefs.escuchaDeseada || prefs.enPausa) ListenService.lanzar(ctx, ListenService.ACTION_RESUME)
            ACCION_APAGAR -> ListenService.lanzar(ctx, ListenService.ACTION_STOP)
            ACCION_DESHACER -> {
                if (prefs.servicioActivo) ListenService.lanzar(ctx, ListenService.ACTION_UNDO) { it.putExtra("id", id) }
                else { Ejecutor(ctx, prefs).deshacerLocal(id); ListenService.lanzar(ctx, ListenService.ACTION_SYNC) }
            }
            ACCION_CREAR, ACCION_IGNORAR -> {
                val crear = intent.action == ACCION_CREAR
                if (prefs.servicioActivo) ListenService.lanzar(ctx, ListenService.ACTION_DECIDE) { it.putExtra("id", id).putExtra("crear", crear) }
                else { prefs.agregarDecision(id, crear); Notifs.cancelar(ctx, Notifs.BASE_SUGERENCIA + id); ListenService.lanzar(ctx, ListenService.ACTION_SYNC) }
            }
            ACCION_POSPONER -> {
                val a = prefs.accion(id)
                Notifs.cancelar(ctx, Notifs.BASE_RECORDATORIO + id)
                Ejecutor(ctx, prefs).programarAlarma(id, System.currentTimeMillis() + 10 * 60000L, a?.optString("titulo") ?: "Recordatorio", a?.optString("descripcion") ?: "")
            }
        }
    }
}

/** Al reiniciar el telefono: Android no deja abrir el microfono desde segundo plano, asi que avisamos con un toque. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val prefs = Prefs(ctx)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (prefs.escuchaDeseada) {
                Notifs.crearCanales(ctx)
                Notifs.aviso(ctx, Notifs.ID_REANUDAR, "IgnoAssist: toca para seguir escuchando", "El teléfono se reinició. Un toque y vuelvo a estar atento todo el día.", "reanudar")
            }
        }
    }
}

/** Alarma de recordatorio: muestra la notificacion con sonido. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val titulo = intent.getStringExtra("titulo") ?: "Recordatorio"
        val texto = intent.getStringExtra("texto") ?: ""
        Notifs.crearCanales(ctx)
        Notifs.recordatorio(ctx, id, titulo, texto)
    }
}

/** Mosaico de ajustes rapidos: un toque enciende/apaga la escucha. */
class ListenTile : TileService() {
    override fun onStartListening() { super.onStartListening(); refrescar() }

    private fun refrescar() {
        val prefs = Prefs(this)
        val t = qsTile ?: return
        val on = prefs.escuchaDeseada && !prefs.enPausa && prefs.grabando
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "IgnoAssist"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "Escuchando" else if (prefs.enPausa) "En pausa" else "Apagado"
        t.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = Prefs(this)
        if (prefs.escuchaDeseada && !prefs.enPausa && prefs.grabando) {
            ListenService.lanzar(this, ListenService.ACTION_STOP)
            android.os.Handler(mainLooper).postDelayed({ refrescar() }, 900)
            return
        }
        // Encender: se abre la app un instante para que Android conceda el microfono (regla de servicios en segundo plano).
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("accion", "reanudar")
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 90, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
        else startActivityAndCollapse(i)
    }
}
