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
    private static long offhookTime = 0;    // momento en que se contestó/conectó (para la duración real, sin depender del registro)
    private static long callStartTime = 0;  // momento en que empezó ESTA llamada (para no leer el registro de la ANTERIOR)

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

            boolean fromIdle = TelephonyManager.EXTRA_STATE_IDLE.equals(lastState);

            if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
                if (fromIdle) {                 // NUEVA llamada entrante: olvidar por completo la anterior (si no, el caller ID muestra al anterior)
                    lastNumber = null; offhookTime = 0; callStartTime = System.currentTimeMillis();
                }
                wasIncoming = true;
                if (incoming != null && !incoming.isEmpty()) lastNumber = incoming;   // número REAL de ESTA llamada (viene en el broadcast de timbre)
                JSONObject c = ContactStore.lookup(app, lastNumber);
                BubbleManager.show(app, c, lastNumber);                                 // lastNumber null -> burbuja "desconocido", NUNCA el del anterior
                CallScopePlugin.emit("callStateChanged", "ringing", lastNumber, c, 0, "incoming");

            } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
                if (fromIdle) {                 // SALIENTE marcada desde el teléfono (sin timbre previo): nueva, olvidar la anterior
                    lastNumber = (incoming != null && !incoming.isEmpty()) ? incoming : null;
                    wasIncoming = false; callStartTime = System.currentTimeMillis();
                } else if (lastNumber == null && incoming != null && !incoming.isEmpty()) {
                    lastNumber = incoming;
                }
                if (offhookTime == 0) offhookTime = System.currentTimeMillis();         // se contestó/conectó ahora -> base para la duración real
                if (wasIncoming) {
                    JSONObject c = ContactStore.lookup(app, lastNumber);
                    BubbleManager.show(app, c, lastNumber);
                }
                CallScopePlugin.emit("callStateChanged", "offhook", lastNumber,
                        ContactStore.lookup(app, lastNumber), 0, wasIncoming ? "incoming" : "outgoing");

            } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
                BubbleManager.hide();
                if (!fromIdle) {
                    // acaba de colgar: captura los datos de ESTA llamada y procésalos (el reset de los estáticos
                    // ocurre justo abajo, así una llamada nueva arranca limpia aunque el procesamiento siga en segundo plano)
                    handleEnded(app, lastNumber, offhookTime, callStartTime, wasIncoming);
                }
                wasIncoming = false; lastNumber = null; offhookTime = 0; callStartTime = 0;
            }
            lastState = state;
        } catch (Throwable t) { /* nunca romper la llamada */ }
    }

    /* Procesa el fin de llamada con los datos CAPTURADOS de ESA llamada (no de estáticos que una
       llamada nueva podría pisar). En segundo plano (goAsync) porque el sistema escribe la entrada
       en el registro de llamadas con RETRASO: leerla al instante devolvía la llamada ANTERIOR, y por
       eso el caller ID mostraba a quien había llamado antes. Aquí el número de referencia es el que
       capturamos en el timbre (capNumber) y SOLO se usa una fila del registro si es de ESTA llamada. */
    private void handleEnded(final Context app, final String capNumber, final long offhook,
                             final long started, final boolean incomingCall) {
        final PendingResult pr = goAsync();
        final long duration0 = (offhook > 0) ? Math.max(0, (System.currentTimeMillis() - offhook) / 1000) : 0;
        new Thread(new Runnable() {
            public void run() {
                String number = capNumber;
                long durationSec = duration0;
                String type = incomingCall ? "incoming" : "outgoing";
                try {
                    // Reintenta leer el registro hasta que aparezca la fila de ESTA llamada (número igual, o fecha
                    // igual/posterior al inicio de esta llamada). Para entrantes ya tenemos el número del timbre;
                    // para salientes (sin timbre) el número solo llega por el registro, que puede tardar.
                    for (int attempt = 0; attempt < 6; attempt++) {
                        boolean matched = false;
                        try {
                            Cursor cur = app.getContentResolver().query(
                                    CallLog.Calls.CONTENT_URI,
                                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.DATE},
                                    null, null, CallLog.Calls.DATE + " DESC");
                            if (cur != null) {
                                if (cur.moveToFirst()) {
                                    String n = cur.getString(0);
                                    long d = cur.getLong(1);
                                    int t = cur.getInt(2);
                                    long when = cur.getLong(3);
                                    boolean sameNum = eq10(number, n);
                                    boolean thisCall = started > 0 && when >= started - 3000;   // fila de ESTA llamada, no de una anterior
                                    if (sameNum || thisCall) {
                                        if (n != null && !n.isEmpty()) number = n;
                                        if (d > 0) durationSec = d;
                                        if (t == CallLog.Calls.OUTGOING_TYPE) type = "outgoing";
                                        else if (t == CallLog.Calls.MISSED_TYPE) type = "missed";
                                        else type = "incoming";
                                        matched = true;
                                    }
                                }
                                cur.close();
                            }
                        } catch (Throwable ignored) { /* sin permiso de registro: seguimos con lo capturado */ }
                        if (matched || number != null) break;   // entrante: ya hay número fiable; o ya hallamos la fila de esta llamada
                        try { Thread.sleep(350); } catch (InterruptedException ie) { break; }
                    }

                    if (number == null || number.isEmpty()) return;   // sin número fiable de ESTA llamada: no notificar (nunca al anterior)

                    JSONObject c = ContactStore.lookup(app, number);
                    String nombre = c != null ? c.optString("nombre", c.optString("name", "")) : "";
                    String label = !nombre.isEmpty() ? nombre : prettyNumber(number);

                    savePending(app, number, durationSec, type, nombre);
                    notifyRegister(app, label, durationSec, number, type, nombre);
                    CallScopePlugin.emit("callEnded", type, number, c, durationSec, type);
                } catch (Throwable t) { /* best-effort */ }
                finally { try { pr.finish(); } catch (Throwable ignored) {} }
            }
        }).start();
    }

    /** true si dos números tienen los mismos últimos 10 dígitos (PR/US). */
    private static boolean eq10(String a, String b) {
        if (a == null || b == null) return false;
        String x = ContactStore.last10(a), y = ContactStore.last10(b);
        return x != null && x.length() >= 7 && x.equals(y);
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
