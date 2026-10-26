package com.ignotek.solarscope.callscope;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.CallLog;
import android.telephony.TelephonyManager;

import org.json.JSONObject;

/**
 * Detecta el estado de la llamada (entrante / activa / colgada) vía el broadcast
 * PHONE_STATE y coordina la burbuja y la notificación de registro.
 *   RINGING  -> hay número (si hay permiso READ_CALL_LOG): busca en el CRM y muestra la burbuja.
 *   OFFHOOK  -> llamada activa: mantiene la burbuja.
 *   IDLE     -> colgó: quita la burbuja, lee del registro de llamadas (número + duración)
 *               y lanza la notificación "Llamada terminada con X · toca para registrar".
 * Todo defensivo: cualquier fallo no interrumpe la llamada ni la app.
 */
public class CallScopeReceiver extends BroadcastReceiver {

    private static String lastState = TelephonyManager.EXTRA_STATE_IDLE;
    private static String lastNumber = null;
    private static boolean wasIncoming = false;

    static final String CH_ID = "callscope_register";
    static final String PREFS = "callscope";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null || !TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) return;
            Context app = context.getApplicationContext();
            String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            String incoming = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            if (state == null) return;

            if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
                wasIncoming = true;
                if (incoming != null && !incoming.isEmpty()) lastNumber = incoming;
                JSONObject c = ContactStore.lookup(app, lastNumber);
                BubbleManager.show(app, c, lastNumber);
                CallScopePlugin.emit("callStateChanged", "ringing", lastNumber, c, 0, "incoming");

            } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
                // activa: si fue entrante, mantener/actualizar burbuja
                if (wasIncoming) {
                    JSONObject c = ContactStore.lookup(app, lastNumber);
                    BubbleManager.show(app, c, lastNumber);
                }
                CallScopePlugin.emit("callStateChanged", "offhook", lastNumber,
                        ContactStore.lookup(app, lastNumber), 0, wasIncoming ? "incoming" : "outgoing");

            } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
                BubbleManager.hide();
                if (!TelephonyManager.EXTRA_STATE_IDLE.equals(lastState)) {
                    // acaba de colgar: leer el registro de llamadas
                    handleEnded(app);
                }
                wasIncoming = false;
                lastNumber = null;
            }
            lastState = state;
        } catch (Throwable t) { /* nunca romper la llamada */ }
    }

    private void handleEnded(Context app) {
        try {
            String number = lastNumber;
            long durationSec = 0;
            String type = wasIncoming ? "incoming" : "outgoing";
            // leer la última entrada del registro de llamadas (número + duración reales)
            try {
                Cursor cur = app.getContentResolver().query(
                        CallLog.Calls.CONTENT_URI,
                        new String[]{CallLog.Calls.NUMBER, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.DATE},
                        null, null, CallLog.Calls.DATE + " DESC");
                if (cur != null) {
                    if (cur.moveToFirst()) {
                        String n = cur.getString(0);
                        if (n != null && !n.isEmpty()) number = n;
                        durationSec = cur.getLong(1);
                        int t = cur.getInt(2);
                        if (t == CallLog.Calls.OUTGOING_TYPE) type = "outgoing";
                        else if (t == CallLog.Calls.MISSED_TYPE) type = "missed";
                        else type = "incoming";
                    }
                    cur.close();
                }
            } catch (Throwable ignored) { /* sin permiso de registro: usamos lo que haya */ }

            if (number == null || number.isEmpty()) return;

            JSONObject c = ContactStore.lookup(app, number);
            String nombre = c != null ? c.optString("nombre", c.optString("name", "")) : "";
            String label = !nombre.isEmpty() ? nombre : prettyNumber(number);

            savePending(app, number, durationSec, type, nombre);
            notifyRegister(app, label, durationSec, number, type, nombre);
            CallScopePlugin.emit("callEnded", type, number, c, durationSec, type);
        } catch (Throwable t) { /* best-effort */ }
    }

    private void savePending(Context app, String tel, long dur, String tipo, String nombre) {
        try {
            JSONObject o = new JSONObject();
            o.put("tel", tel);
            o.put("dur", dur);
            o.put("tipo", tipo);
            o.put("nombre", nombre);
            o.put("ts", System.currentTimeMillis());
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("pending", o.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private void notifyRegister(Context app, String label, long dur, String tel, String tipo, String nombre) {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(CH_ID, "Registro de llamadas",
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Avisa al colgar para registrar la llamada en Solar Scope");
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(app, com.ignotek.solarscope.MainActivity.class);
            open.setAction("com.ignotek.solarscope.REGISTER_CALL");
            open.putExtra("regtel", tel);
            open.putExtra("regdur", dur);
            open.putExtra("regtipo", tipo);
            open.putExtra("regnombre", nombre);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(app, 7001, open, flags);

            String durTxt = fmtDur(dur);
            String text = "Llamada terminada con " + label
                    + (durTxt.isEmpty() ? "" : " · " + durTxt) + " · toca para registrar";
            Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(app, CH_ID) : new Notification.Builder(app);
            b.setSmallIcon(app.getApplicationInfo().icon)
                    .setContentTitle("Solar Scope · registrar llamada")
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(7001, b.build());
        } catch (Throwable ignored) {}
    }

    static String fmtDur(long sec) {
        if (sec <= 0) return "";
        long m = sec / 60, s = sec % 60;
        if (m > 0) return m + " min " + s + " s";
        return s + " s";
    }

    private String prettyNumber(String number) {
        String d = ContactStore.last10(number);
        if (d != null && d.length() == 10)
            return "(" + d.substring(0, 3) + ") " + d.substring(3, 6) + "-" + d.substring(6);
        return number;
    }
}
