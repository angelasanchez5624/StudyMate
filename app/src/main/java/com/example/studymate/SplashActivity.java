package com.example.studymate;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

/**
 * Pantalla de carga (splash) con el logo animado, al estilo de las apps de bancos:
 *  1. El logo aparece creciendo con un pequeño rebote.
 *  2. "StudyMate" se escribe de izquierda a derecha y luego aparece el lema "Tu agenda universitaria".
 *  3. Tres puntos laten mientras carga.
 *  4. Abre Inicio (si ya hay sesión) o el Login. La sesión se revisa DURANTE la animación,
 *     así no queda una pantalla en blanco entre el splash y la app.
 *     Sin internet, la siguiente pantalla muestra arriba la franja "Sin conexión a internet".
 *
 * En Android 12+ el sistema muestra primero su propio splash (mismo color y mismo logo,
 * ver Theme.StudyMate.Splash), así que la animación continúa sin saltos.
 * Si el teléfono tiene las animaciones desactivadas (accesibilidad), se salta la animación.
 */
public class SplashActivity extends AppCompatActivity {

    /** Tiempo total en pantalla (ms). */
    private static final long DURACION = 2200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean salio = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        // El splash del sistema se desvanece suave (antes se quitaba de golpe y el logo "saltaba"):
        // debajo ya va corriendo la animación, así que se ve como una sola transición.
        splash.setOnExitAnimationListener(vista -> vista.getView().animate()
                .alpha(0f).setDuration(450)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(vista::remove).start());
        setContentView(R.layout.activity_splash);
        Ajustes.cargar(this); // formato de hora elegido en Configuración
        prepararSesion();     // mientras corre la animación se revisa la sesión y se lee el perfil

        View logo = findViewById(R.id.ivLogoSplash);
        View nombre = findViewById(R.id.tvNombreSplash);
        View lema = findViewById(R.id.tvLemaSplash);
        View puntos = findViewById(R.id.puntosSplash);

        if (sinAnimaciones()) {
            nombre.setAlpha(1f);
            lema.setAlpha(1f);
            handler.postDelayed(this::revisarConexion, 900);
            return;
        }

        // 1. Logo: crece con rebote y da un pequeño giro
        // Empieza casi del mismo tamaño del logo del sistema y crece suave, sin aparecer de golpe
        logo.setScaleX(0.85f);
        logo.setScaleY(0.85f);
        logo.setAlpha(0.6f);
        logo.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(650)
                .setInterpolator(new OvershootInterpolator(1.4f)).start();
        ObjectAnimator giro = ObjectAnimator.ofFloat(logo, View.ROTATION, -8f, 6f, -3f, 0f);
        giro.setDuration(700);
        giro.setStartDelay(150);
        giro.start();

        // 2. "StudyMate" se va escribiendo de izquierda a derecha; luego sube el lema
        float sube = 24 * getResources().getDisplayMetrics().density;
        nombre.setAlpha(1f);
        nombre.setClipBounds(new android.graphics.Rect(0, 0, 0, 0)); // empieza oculto
        nombre.post(() -> {
            int ancho = nombre.getWidth(), alto = nombre.getHeight();
            ValueAnimator escribir = ValueAnimator.ofInt(0, ancho);
            escribir.setStartDelay(450);
            escribir.setDuration(700);
            escribir.setInterpolator(new DecelerateInterpolator());
            escribir.addUpdateListener(a ->
                    nombre.setClipBounds(new android.graphics.Rect(0, 0, (int) a.getAnimatedValue(), alto)));
            escribir.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) { nombre.setClipBounds(null); }
            });
            escribir.start();
        });
        lema.setTranslationY(sube / 2);
        lema.animate().alpha(0.9f).translationY(0f).setStartDelay(1050).setDuration(450)
                .setInterpolator(new DecelerateInterpolator()).start();

        // 3. Puntos que laten uno detrás de otro
        puntos.animate().alpha(1f).setStartDelay(900).setDuration(300).start();
        latir(findViewById(R.id.punto1), 900);
        latir(findViewById(R.id.punto2), 1050);
        latir(findViewById(R.id.punto3), 1200);

        // 4. Al terminar: revisa la conexión
        handler.postDelayed(this::revisarConexion, DURACION - 250);
    }

    // ---------------- Conexión a internet ----------------

    /** Al terminar la animación entra a la app (el aviso de conexión lo muestra la siguiente pantalla). */
    private void revisarConexion() {
        if (isFinishing()) return;
        terminar(); // sin internet, el aviso aparece arriba en el Login o en Inicio (estilo Nequi)
    }

    /** Al terminar la animación: el logo crece un poco y abre la app (sin pasar por pantalla blanca). */
    private void terminar() {
        if (salio || isFinishing()) return;
        if (!listo) { // la sesión aún se está revisando: los puntos siguen latiendo un momento
            alListo = this::terminar;
            return;
        }
        View logo = findViewById(R.id.ivLogoSplash);
        logo.animate().scaleX(1.12f).scaleY(1.12f).setDuration(200)
                .setInterpolator(new AccelerateInterpolator()).withEndAction(this::salir).start();
    }

    // ---------------- Sesión (antes la revisaba el Login con la pantalla en blanco) ----------------

    /** true cuando ya se sabe a dónde ir (Inicio o Login). */
    private boolean listo = false;
    private Class<?> destino = LoginActivity.class;
    private Runnable alListo;

    private void prepararSesion() {
        com.google.firebase.auth.FirebaseAuth auth = com.google.firebase.auth.FirebaseAuth.getInstance();
        if (!Sesion.activa(this)) {
            // Había una sesión de Firebase que no vino del Login (p. ej. recién registrado): se cierra
            if (auth.getCurrentUser() != null) Sesion.cerrar(this);
            marcarListo(LoginActivity.class);
            return;
        }
        // Confirma que la cuenta existe y lee el perfil, para que Inicio salude con el nombre.
        // Sin internet entra con la sesión guardada; máximo 3 s de espera.
        handler.postDelayed(() -> marcarListo(DashboardActivity.class), 3000);
        auth.getCurrentUser().reload().addOnCompleteListener(t -> {
            if (t.isSuccessful() || t.getException() instanceof com.google.firebase.FirebaseNetworkException) {
                Repo.leerPerfil(perfil -> marcarListo(DashboardActivity.class));
            } else {
                Sesion.cerrar(this);
                marcarListo(LoginActivity.class);
            }
        });
    }

    private void marcarListo(Class<?> a) {
        if (listo) return;
        listo = true;
        destino = a;
        if (alListo != null) { Runnable r = alListo; alListo = null; r.run(); }
    }


    private void latir(View punto, long retraso) {
        ObjectAnimator x = ObjectAnimator.ofFloat(punto, View.SCALE_X, 1f, 1.6f, 1f);
        ObjectAnimator y = ObjectAnimator.ofFloat(punto, View.SCALE_Y, 1f, 1.6f, 1f);
        ObjectAnimator a = ObjectAnimator.ofFloat(punto, View.ALPHA, 0.4f, 1f, 0.4f);
        for (ObjectAnimator o : new ObjectAnimator[]{x, y, a}) {
            o.setRepeatCount(ValueAnimator.INFINITE);
            o.setDuration(600);
        }
        AnimatorSet set = new AnimatorSet();
        set.playTogether(x, y, a);
        set.setStartDelay(retraso);
        set.start();
    }

    /** Animaciones desactivadas en el teléfono (Opciones de accesibilidad / desarrollador). */
    private boolean sinAnimaciones() {
        return Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
    }

    /** Abre Inicio (si ya hay sesión) o el Login. */
    @SuppressWarnings("deprecation") // overridePendingTransition solo en Android 13
    private void salir() {
        if (salio || isFinishing()) return;
        salio = true;
        startActivity(new Intent(this, destino));
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, android.R.anim.fade_out);
        } else {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
