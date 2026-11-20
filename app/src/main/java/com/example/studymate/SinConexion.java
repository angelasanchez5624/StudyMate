package com.example.studymate;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

/**
 * Franja "Sin conexión a internet" arriba de la pantalla (estilo Nequi):
 * fondo rosado claro, ícono de wifi tachado, título en negrita y texto. Sin botones:
 * aparece sola cuando se va el internet y se quita sola cuando vuelve.
 *
 *  - Sin sesión (Login, Crear cuenta):
 *      "Necesitas internet para iniciar sesión o crear tu cuenta. Revisa tu wifi o tus datos móviles."
 *  - Dentro de la app (Inicio):
 *      "Revisa tu wifi o tus datos móviles. Mientras tanto puedes ver tus materias, horarios,
 *       tareas y apuntes guardados."
 *
 * Uso: SinConexion.vigilar(this, conSesion) en onCreate (después de setContentView).
 * Antes de una acción que necesita internet: if (!SinConexion.revisar(this)) return;
 */
public class SinConexion {

    public static boolean hayInternet(Activity a) {
        ConnectivityManager cm = a.getSystemService(ConnectivityManager.class);
        NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /** Agrega la franja a la pantalla y la muestra / oculta según la conexión. */
    public static void vigilar(AppCompatActivity a, boolean conSesion) {
        FrameLayout raiz = a.findViewById(android.R.id.content);
        View aviso = raiz.findViewById(R.id.avisoSinConexion);
        if (aviso == null) {
            aviso = a.getLayoutInflater().inflate(R.layout.aviso_sin_conexion, raiz, false);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            int m = dp(a, 16);
            lp.setMargins(m, m, m, 0);
            raiz.addView(aviso, lp);
            // Debajo de la barra de estado (la hora y la batería)
            View v = aviso;
            ViewCompat.setOnApplyWindowInsetsListener(aviso, (vista, insets) -> {
                Insets barras = insets.getInsets(WindowInsetsCompat.Type.statusBars());
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) vista.getLayoutParams();
                p.topMargin = barras.top + dp(a, 8);
                vista.setLayoutParams(p);
                return insets;
            });
            ViewCompat.requestApplyInsets(aviso);
            v.setVisibility(View.GONE);
        }
        ((TextView) aviso.findViewById(R.id.tvSinConexionTexto)).setText(
                conSesion ? R.string.sin_conexion_dentro : R.string.sin_conexion_fuera);

        final View franja = aviso;
        if (!hayInternet(a)) mostrar(franja);

        ConnectivityManager cm = a.getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        ConnectivityManager.NetworkCallback escucha = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) {
                boolean ok = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                a.runOnUiThread(() -> { if (ok) ocultar(franja); else mostrar(franja); });
            }
            @Override public void onLost(Network n) {
                a.runOnUiThread(() -> { if (!hayInternet(a)) mostrar(franja); });
            }
        };
        try { cm.registerDefaultNetworkCallback(escucha); } catch (Exception ignored) { }
        // Al cerrar la pantalla se deja de escuchar
        a.getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onDestroy(LifecycleOwner owner) {
                try { cm.unregisterNetworkCallback(escucha); } catch (Exception ignored) { }
            }
        });
    }

    /**
     * Para acciones que necesitan internet (Iniciar sesión, Google, Crear cuenta…).
     * Devuelve true si hay internet; si no, muestra la franja y la "sacude" para llamar la atención.
     */
    public static boolean revisar(Activity a) {
        if (hayInternet(a)) return true;
        View aviso = a.findViewById(R.id.avisoSinConexion);
        if (aviso != null) {
            mostrar(aviso);
            ObjectAnimator.ofFloat(aviso, View.TRANSLATION_X, 0, 16, -16, 10, -10, 5, 0).setDuration(400).start();
        }
        return false;
    }

    private static void mostrar(View aviso) {
        if (aviso.getVisibility() == View.VISIBLE) return;
        aviso.setVisibility(View.VISIBLE);
        aviso.setAlpha(0f);
        aviso.setTranslationY(-aviso.getResources().getDisplayMetrics().density * 24);
        aviso.animate().alpha(1f).translationY(0f).setDuration(250).start();
    }

    private static void ocultar(View aviso) {
        if (aviso.getVisibility() != View.VISIBLE) return;
        aviso.animate().alpha(0f).translationY(-aviso.getResources().getDisplayMetrics().density * 24)
                .setDuration(200).withEndAction(() -> aviso.setVisibility(View.GONE)).start();
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
