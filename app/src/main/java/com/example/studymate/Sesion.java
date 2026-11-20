package com.example.studymate;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.auth.FirebaseAuth;

public class Sesion {

    private static final String PREFS = "studymate_sesion";
    private static final String KEY_ACTIVA = "sesion_activa";

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void iniciar(Context c) {
        prefs(c).edit().putBoolean(KEY_ACTIVA, true).apply();
    }

    public static boolean activa(Context c) {
        return prefs(c).getBoolean(KEY_ACTIVA, false)
                && FirebaseAuth.getInstance().getCurrentUser() != null;
    }

    public static void cerrar(Context c) {
        Recordatorios.cancelarTodoDelUsuario(c); // los avisos (tareas y clases) son de este usuario
        FirebaseAuth.getInstance().signOut();
        // Olvida la cuenta de Google elegida, para poder entrar con otra la próxima vez
        try {
            androidx.credentials.CredentialManager.create(c.getApplicationContext()).clearCredentialStateAsync(
                    new androidx.credentials.ClearCredentialStateRequest(), null,
                    java.util.concurrent.Executors.newSingleThreadExecutor(),
                    new androidx.credentials.CredentialManagerCallback<Void,
                            androidx.credentials.exceptions.ClearCredentialException>() {
                        @Override public void onResult(Void r) { }
                        @Override public void onError(androidx.credentials.exceptions.ClearCredentialException e) { }
                    });
        } catch (Exception ignored) { }
        prefs(c).edit().putBoolean(KEY_ACTIVA, false).apply();
        Datos.limpiar();
    }
}
