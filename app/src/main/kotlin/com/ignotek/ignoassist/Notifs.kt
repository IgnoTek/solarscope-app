package com.ignotek.ignoassist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build

/** Canales y constructores de notificaciones. */
object Notifs {
    const val CH_ESCUCHA = "escucha"
    const val CH_ACCIONES = "acciones"
    const val CH_RECORDATORIOS = "recordatorios"
    const val CH_AVISOS = "avisos"
    const val ID_SERVICIO = 1
    const val ID_REANUDAR = 2
    const val BASE_ACCION = 1000
    const val BASE_SUGERENCIA = 100000
    const val BASE_RECORDATORIO = 200000

    fun crearCanales(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val escucha = NotificationChannel(CH_ESCUCHA, "Escucha en curso", NotificationManager.IMPORTANCE_LOW).apply { description = "Estado del asistente (permanente mientras escucha)"; setShowBadge(false) }
        val acciones = NotificationChannel(CH_ACCIONES, "Acciones creadas", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Eventos, tareas y notas que el asistente agrega" }
        val rec = NotificationChannel(CH_RECORDATORIOS, "Recordatorios", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Alarmas de recordatorio"; enableVibration(true); vibrationPattern = longArrayOf(0, 400, 200, 400)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), Notification.AUDIO_ATTRIBUTES_DEFAULT)
        }
        val avisos = NotificationChannel(CH_AVISOS, "Avisos", NotificationManager.IMPORTANCE_DEFAULT)
        nm.createNotificationChannels(listOf(escucha, acciones, rec, avisos))
    }

    private fun flags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

    fun abrirApp(ctx: Context, req: Int = 0, extra: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (extra != null) i.putExtra("accion", extra)
        return PendingIntent.getActivity(ctx, req, i, flags())
    }

    private fun broadcast(ctx: Context, accion: String, req: Int, id: Int = 0): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).setAction(accion).putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, req, i, flags())
    }

    /** Notificacion permanente del servicio. */
    fun servicio(ctx: Context, titulo: String, texto: String, escuchando: Boolean, pausado: Boolean): Notification {
        val b = Notification.Builder(ctx, CH_ESCUCHA)
            .setSmallIcon(R.drawable.ic_mic).setContentTitle(titulo).setContentText(texto)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(abrirApp(ctx, 1)).setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (escuchando && !pausado) {
            b.addAction(Notification.Action.Builder(null, "Pausar 30 min", broadcast(ctx, ActionReceiver.ACCION_PAUSAR, 11)).build())
        } else {
            b.addAction(Notification.Action.Builder(null, "Reanudar", broadcast(ctx, ActionReceiver.ACCION_REANUDAR, 12)).build())
        }
        b.addAction(Notification.Action.Builder(null, "Apagar", broadcast(ctx, ActionReceiver.ACCION_APAGAR, 13)).build())
        return b.build()
    }

    fun accionCreada(ctx: Context, id: Int, titulo: String, cuando: String, tipo: String, puedeDeshacer: Boolean) {
        val etiqueta = when (tipo) { "evento" -> "Evento en tu calendario"; "recordatorio" -> "Recordatorio programado"; "tarea" -> "Tarea anotada"; else -> "Nota guardada" }
        val b = Notification.Builder(ctx, CH_ACCIONES).setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("$etiqueta: $titulo").setContentText(cuando).setStyle(Notification.BigTextStyle().bigText(cuando))
            .setContentIntent(abrirApp(ctx, BASE_ACCION + id)).setAutoCancel(true)
        if (puedeDeshacer) b.addAction(Notification.Action.Builder(null, "Deshacer", broadcast(ctx, ActionReceiver.ACCION_DESHACER, BASE_ACCION + id, id)).build())
        nm(ctx).notify(BASE_ACCION + id, b.build())
    }

    fun sugerencia(ctx: Context, id: Int, titulo: String, cuando: String) {
        val b = Notification.Builder(ctx, CH_ACCIONES).setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("¿Crear? $titulo").setContentText(cuando).setStyle(Notification.BigTextStyle().bigText("$cuando\nEl asistente no estuvo seguro; decide tú."))
            .setContentIntent(abrirApp(ctx, BASE_SUGERENCIA + id)).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Crear", broadcast(ctx, ActionReceiver.ACCION_CREAR, BASE_SUGERENCIA + id, id)).build())
            .addAction(Notification.Action.Builder(null, "Ignorar", broadcast(ctx, ActionReceiver.ACCION_IGNORAR, BASE_SUGERENCIA + id + 1, id)).build())
        nm(ctx).notify(BASE_SUGERENCIA + id, b.build())
    }

    fun recordatorio(ctx: Context, id: Int, titulo: String, texto: String) {
        val b = Notification.Builder(ctx, CH_RECORDATORIOS).setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("⏰ $titulo").setContentText(texto).setStyle(Notification.BigTextStyle().bigText(texto))
            .setContentIntent(abrirApp(ctx, BASE_RECORDATORIO + id)).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER)
            .addAction(Notification.Action.Builder(null, "Posponer 10 min", broadcast(ctx, ActionReceiver.ACCION_POSPONER, BASE_RECORDATORIO + id, id)).build())
        nm(ctx).notify(BASE_RECORDATORIO + id, b.build())
    }

    fun aviso(ctx: Context, id: Int, titulo: String, texto: String, extra: String? = null) {
        val b = Notification.Builder(ctx, CH_AVISOS).setSmallIcon(R.drawable.ic_mic).setContentTitle(titulo).setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto)).setContentIntent(abrirApp(ctx, id, extra)).setAutoCancel(true)
        nm(ctx).notify(id, b.build())
    }

    fun cancelar(ctx: Context, id: Int) = nm(ctx).cancel(id)
    fun nm(ctx: Context): NotificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
