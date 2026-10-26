package com.ignotek.solarscope.callscope;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Almacén local de contactos del CRM (Solar Scope), para identificar llamadas
 * aun sin datos. La app web empuja la lista con CallScope.syncContacts(...).
 * Cada contacto: { tel, nombre, compania, estado, ultima, id, tipo }.
 * El emparejamiento es por los últimos 10 dígitos del número (PR/US).
 */
public class ContactStore {
    private static final String PREFS = "callscope";
    private static final String KEY = "contacts";

    private static Map<String, JSONObject> index; // últimos10 -> contacto
    private static boolean loaded = false;

    public static synchronized void save(Context ctx, JSONArray arr) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putString(KEY, arr != null ? arr.toString() : "[]").apply();
            buildIndex(arr);
            loaded = true;
        } catch (Throwable t) { /* best-effort */ }
    }

    public static synchronized int count(Context ctx) {
        ensureLoaded(ctx);
        return index == null ? 0 : index.size();
    }

    public static synchronized JSONObject lookup(Context ctx, String number) {
        ensureLoaded(ctx);
        if (index == null || number == null) return null;
        String key = last10(number);
        if (key == null || key.isEmpty()) return null;
        return index.get(key);
    }

    private static void ensureLoaded(Context ctx) {
        if (loaded && index != null) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY, "[]");
            buildIndex(new JSONArray(raw));
        } catch (Throwable t) {
            index = new HashMap<>();
        }
        loaded = true;
    }

    private static void buildIndex(JSONArray arr) {
        Map<String, JSONObject> m = new HashMap<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String tel = o.optString("tel", o.optString("telefono", ""));
                String k = last10(tel);
                if (k != null && k.length() >= 7) m.put(k, o);
            }
        }
        index = m;
    }

    /** Deja solo dígitos y toma los últimos 10 (ignora +1, guiones, paréntesis, espacios). */
    public static String last10(String s) {
        if (s == null) return null;
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') d.append(c);
        }
        String digits = d.toString();
        if (digits.length() > 10) digits = digits.substring(digits.length() - 10);
        return digits;
    }
}
