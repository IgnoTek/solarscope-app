package com.ignotek.solarscope.callscope;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * CallScope — identificación de llamadas de Solar Scope para Android.
 * La app web (dentro del APK) llama a:
 *   CallScope.requestPermissions()      pide teléfono + registro de llamadas + notificaciones
 *   CallScope.requestOverlay()          pide "mostrar sobre otras apps" (la burbuja)
 *   CallScope.syncContacts({contacts})  empuja la lista del CRM para identificar números offline
 *   CallScope.getPendingRegister()      lee la llamada recién terminada para abrir la tarjeta
 * Eventos: "callStateChanged" (ringing/offhook) y "callEnded".
 */
@CapacitorPlugin(
        name = "CallScope",
        permissions = {
                @Permission(alias = "phone", strings = { Manifest.permission.READ_PHONE_STATE }),
                @Permission(alias = "calllog", strings = { Manifest.permission.READ_CALL_LOG }),
                @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
        }
)
public class CallScopePlugin extends Plugin {

    private static CallScopePlugin instance;

    @Override
    public void load() {
        instance = this;
    }

    @PluginMethod
    public void hasPermissions(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("phone", granted(Manifest.permission.READ_PHONE_STATE));
        ret.put("calllog", granted(Manifest.permission.READ_CALL_LOG));
        ret.put("notifications", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS));
        ret.put("overlay", canOverlay());
        boolean ok = granted(Manifest.permission.READ_PHONE_STATE) && canOverlay();
        ret.put("ready", ok);
        ret.put("contacts", ContactStore.count(getContext()));
        call.resolve(ret);
    }

    @PluginMethod
    public void requestPermissions(PluginCall call) {
        requestPermissionForAliases(new String[]{"phone", "calllog", "notifications"}, call, "permsResult");
    }

    @PermissionCallback
    private void permsResult(PluginCall call) {
        hasPermissions(call);
    }

    @PluginMethod
    public void requestOverlay(PluginCall call) {
        try {
            if (canOverlay()) { call.resolve(new JSObject().put("overlay", true)); return; }
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve(new JSObject().put("overlay", canOverlay()));
        } catch (Throwable t) {
            call.resolve(new JSObject().put("overlay", false));
        }
    }

    @PluginMethod
    public void syncContacts(PluginCall call) {
        try {
            JSONArray arr = null;
            if (call.getData().has("contacts")) {
                Object c = call.getData().get("contacts");
                if (c instanceof JSONArray) arr = (JSONArray) c;
                else arr = new JSONArray(c.toString());
            }
            ContactStore.save(getContext(), arr);
            call.resolve(new JSObject().put("ok", true).put("count", ContactStore.count(getContext())));
        } catch (Throwable t) {
            call.reject("No se pudo sincronizar contactos: " + t.getMessage());
        }
    }

    @PluginMethod
    public void getPendingRegister(PluginCall call) {
        try {
            String raw = getContext().getSharedPreferences(CallScopeReceiver.PREFS, Context.MODE_PRIVATE)
                    .getString("pending", null);
            if (raw == null) { call.resolve(new JSObject().put("pending", false)); return; }
            JSONObject o = new JSONObject(raw);
            JSObject ret = new JSObject();
            ret.put("pending", true);
            ret.put("tel", o.optString("tel"));
            ret.put("dur", o.optLong("dur"));
            ret.put("tipo", o.optString("tipo"));
            ret.put("nombre", o.optString("nombre"));
            ret.put("ts", o.optLong("ts"));
            call.resolve(ret);
        } catch (Throwable t) {
            call.resolve(new JSObject().put("pending", false));
        }
    }

    @PluginMethod
    public void clearPendingRegister(PluginCall call) {
        try {
            getContext().getSharedPreferences(CallScopeReceiver.PREFS, Context.MODE_PRIVATE)
                    .edit().remove("pending").apply();
        } catch (Throwable ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Throwable ignored) {}
        call.resolve();
    }

    private boolean granted(String perm) {
        try {
            return getContext().checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) { return false; }
    }

    private boolean canOverlay() {
        try {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(getContext());
        } catch (Throwable t) { return false; }
    }

    /** Lo invoca el receptor para notificar a la app web (si está viva). */
    static void emit(String event, String state, String number, JSONObject contact, long dur, String type) {
        try {
            CallScopePlugin p = instance;
            if (p == null) return;
            JSObject data = new JSObject();
            data.put("state", state);
            data.put("number", number);
            data.put("duration", dur);
            data.put("type", type);
            if (contact != null) data.put("contact", JSObject.fromJSONObject(contact));
            p.notifyListeners(event, data);
        } catch (Throwable ignored) {}
    }
}
