package com.ignotek.solarscope.callscope;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Burbuja flotante (overlay) sobre la pantalla de llamada. Muestra quién llama
 * según el CRM: nombre, compañía, estado y última interacción. Sin servicio en
 * primer plano: se adjunta al WindowManager con contexto de aplicación y vive
 * hasta que la llamada termina (IDLE) o el usuario la cierra.
 */
public class BubbleManager {
    private static View bubble;
    private static WindowManager wm;
    private static WindowManager.LayoutParams lp;

    private static final int NAVY = Color.parseColor("#0d2b63");
    private static final int NAVY2 = Color.parseColor("#133b86");
    private static final int GOLD = Color.parseColor("#ffd66b");
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.parseColor("#c7d4ef");

    public static synchronized void show(Context appCtx, JSONObject contact, String number) {
        try {
            if (wm == null) wm = (WindowManager) appCtx.getSystemService(Context.WINDOW_SERVICE);
            if (bubble != null) { update(contact, number); return; }

            LinearLayout card = new LinearLayout(appCtx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(appCtx, 16), dp(appCtx, 12), dp(appCtx, 16), dp(appCtx, 12));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(NAVY);
            bg.setCornerRadius(dp(appCtx, 16));
            bg.setStroke(dp(appCtx, 1), NAVY2);
            card.setBackground(bg);
            card.setElevation(dp(appCtx, 8));

            // fila título
            LinearLayout top = new LinearLayout(appCtx);
            top.setOrientation(LinearLayout.HORIZONTAL);
            TextView brand = new TextView(appCtx);
            brand.setText("☀  SOLAR SCOPE");
            brand.setTextColor(GOLD);
            brand.setTextSize(11);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            brand.setLayoutParams(blp);
            TextView close = new TextView(appCtx);
            close.setText("✕");
            close.setTextColor(MUTED);
            close.setTextSize(14);
            close.setPadding(dp(appCtx, 8), 0, dp(appCtx, 2), 0);
            close.setOnClickListener(v -> hide());
            top.addView(brand);
            top.addView(close);
            card.addView(top);

            TextView name = new TextView(appCtx);
            name.setId(1001);
            name.setTextColor(WHITE);
            name.setTextSize(18);
            name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
            name.setPadding(0, dp(appCtx, 4), 0, 0);
            card.addView(name);

            TextView comp = new TextView(appCtx);
            comp.setId(1002);
            comp.setTextColor(MUTED);
            comp.setTextSize(13);
            card.addView(comp);

            TextView meta = new TextView(appCtx);
            meta.setId(1003);
            meta.setTextColor(MUTED);
            meta.setTextSize(12);
            meta.setPadding(0, dp(appCtx, 4), 0, 0);
            card.addView(meta);

            bubble = card;
            fill(contact, number);

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            lp = new WindowManager.LayoutParams(
                    dp(appCtx, 260),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = dp(appCtx, 12);
            lp.y = dp(appCtx, 90);

            // arrastrable
            card.setOnTouchListener(new View.OnTouchListener() {
                int ix, iy; float tx, ty;
                @Override public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            ix = lp.x; iy = lp.y; tx = e.getRawX(); ty = e.getRawY();
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            lp.x = ix + (int) (e.getRawX() - tx);
                            lp.y = iy + (int) (e.getRawY() - ty);
                            try { wm.updateViewLayout(bubble, lp); } catch (Throwable ignored) {}
                            return true;
                    }
                    return false;
                }
            });

            wm.addView(bubble, lp);
        } catch (Throwable t) {
            // si el overlay falla (permiso/fabricante), no rompe la llamada ni la app
            bubble = null;
        }
    }

    private static void update(JSONObject contact, String number) {
        try { fill(contact, number); } catch (Throwable ignored) {}
    }

    private static void fill(JSONObject contact, String number) {
        TextView name = bubble.findViewById(1001);
        TextView comp = bubble.findViewById(1002);
        TextView meta = bubble.findViewById(1003);
        if (contact != null) {
            String n = contact.optString("nombre", contact.optString("name", "Cliente"));
            String c = contact.optString("compania", contact.optString("empresa", ""));
            String estado = contact.optString("estado", "");
            String ultima = contact.optString("ultima", contact.optString("ultimaInteraccion", ""));
            name.setText(n.isEmpty() ? pretty(number) : n);
            comp.setText(c);
            comp.setVisibility(c.isEmpty() ? View.GONE : View.VISIBLE);
            StringBuilder m = new StringBuilder();
            if (!estado.isEmpty()) m.append("● ").append(estado);
            if (!ultima.isEmpty()) { if (m.length() > 0) m.append("   "); m.append(ultima); }
            meta.setText(m.toString());
            meta.setVisibility(m.length() == 0 ? View.GONE : View.VISIBLE);
        } else {
            name.setText(pretty(number));
            comp.setText("No está en tus prospectos");
            comp.setVisibility(View.VISIBLE);
            meta.setText("Al colgar puedes registrarlo");
            meta.setVisibility(View.VISIBLE);
        }
    }

    public static synchronized void hide() {
        try {
            if (bubble != null && wm != null) wm.removeView(bubble);
        } catch (Throwable ignored) {}
        bubble = null;
    }

    private static String pretty(String number) {
        if (number == null || number.isEmpty()) return "Llamada";
        String d = ContactStore.last10(number);
        if (d != null && d.length() == 10)
            return "(" + d.substring(0, 3) + ") " + d.substring(3, 6) + "-" + d.substring(6);
        return number;
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }
}
