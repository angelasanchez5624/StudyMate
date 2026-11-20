package com.example.studymate;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;

public class LoginActivity extends AppCompatActivity {

    private FirebaseAuth auth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        auth = FirebaseAuth.getInstance();
        Ajustes.cargar(this); // formato de hora elegido en Configuración

        // Solo entra directo si inició sesión desde este Login antes
        if (Sesion.activa(this)) {
            // Mientras confirma la cuenta se ve el logo (no una pantalla en blanco)
            setContentView(R.layout.activity_splash);
            findViewById(R.id.tvNombreSplash).setAlpha(1f);
            findViewById(R.id.tvLemaSplash).setAlpha(1f);
            // Confirma que la cuenta todavía existe en Firebase
            auth.getCurrentUser().reload().addOnCompleteListener(t -> {
                if (t.isSuccessful() || t.getException() instanceof FirebaseNetworkException) {
                    entrarConPerfil(); // sin internet se deja entrar con la sesión guardada
                } else {
                    Sesion.cerrar(this);
                    mostrarLogin();
                }
            });
            return;
        }

        // Había una sesión de Firebase que no vino del Login (p. ej. recién registrado): se cierra
        if (auth.getCurrentUser() != null) Sesion.cerrar(this);

        mostrarLogin();
    }

    // Muestra la pantalla de Login y configura sus botones
    private void mostrarLogin() {
        setContentView(R.layout.activity_login);
        Ui.insets(this);

        EditText etCorreo = findViewById(R.id.etCorreo);
        EditText etPassword = findViewById(R.id.etPassword);
        MaterialButton btnIngresar = findViewById(R.id.btnIngresar);
        android.view.View progreso = findViewById(R.id.progresoLogin);
        android.widget.TextView tvError = findViewById(R.id.tvErrorLogin);
        // El aviso rojo se quita apenas la persona vuelve a escribir
        android.text.TextWatcher quitarError = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { tvError.setVisibility(android.view.View.GONE); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        };
        etCorreo.addTextChangedListener(quitarError);
        etPassword.addTextChangedListener(quitarError);

        // Sin internet no se puede iniciar sesión: se avisa al abrir y al tocar los botones
        SinConexion.vigilar(this, false);

        btnIngresar.setOnClickListener(v -> {
            if (!SinConexion.revisar(this)) return;
            String correo = etCorreo.getText().toString().trim();
            String pass = etPassword.getText().toString();

            if (!Patterns.EMAIL_ADDRESS.matcher(correo).matches()) {
                etCorreo.setError(getString(R.string.correo_invalido));
                return;
            }
            if (pass.length() < 6) {
                mostrarError(tvError, getString(R.string.min_6));
                etPassword.requestFocus();
                return;
            }

            btnIngresar.setEnabled(false);
            btnIngresar.setText(R.string.iniciando_sesion);
            progreso.setVisibility(android.view.View.VISIBLE);
            auth.signInWithEmailAndPassword(correo, pass).addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    Sesion.iniciar(this); // ahora sí puede entrar directo la próxima vez
                    entrarConPerfil(); // la barra de carga sigue hasta que abre Inicio
                } else {
                    btnIngresar.setEnabled(true);
                    btnIngresar.setText(R.string.iniciar_sesion);
                    progreso.setVisibility(android.view.View.GONE);
                    // El error se muestra junto al campo de contraseña (y se conserva el correo)
                    // "⚠ Correo o contraseña incorrectos" en rojo debajo del campo (el correo se conserva)
                    mostrarError(tvError, mensajeError(task.getException()));
                    etPassword.requestFocus();
                }
            });
        });

        // Continuar con Google
        MaterialButton btnGoogle = findViewById(R.id.btnGoogle);
        btnGoogle.setOnClickListener(v -> {
            // La barra de carga de Google va debajo de su botón (la del botón morado es del login con correo)
            if (SinConexion.revisar(this)) entrarConGoogle(btnGoogle, findViewById(R.id.progresoGoogle), tvError);
        });

        findViewById(R.id.tvCrearCuenta).setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));

        // Restablecer contraseña por correo
        findViewById(R.id.tvOlvidaste).setOnClickListener(v -> {
            String correo = etCorreo.getText().toString().trim();
            if (!Patterns.EMAIL_ADDRESS.matcher(correo).matches()) {
                etCorreo.setError(getString(R.string.correo_para_enlace));
                return;
            }
            if (!SinConexion.revisar(this)) return;
            auth.sendPasswordResetEmail(correo).addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    Toast.makeText(this, R.string.enlace_enviado, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, mensajeError(task.getException()), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    // ---------------- Iniciar sesión con Google ----------------

    /**
     * Muestra la ventana de Google para elegir la cuenta (Credential Manager) y con esa cuenta
     * inicia sesión en Firebase. Si es la primera vez, crea su perfil en la base de datos.
     *
     * Requisitos en Firebase (una sola vez): Authentication → Sign-in method → Google activado,
     * la huella SHA-1 del proyecto agregada y el google-services.json descargado de nuevo.
     */
    private void entrarConGoogle(MaterialButton btn, android.view.View progreso, android.widget.TextView tvError) {
        // "default_web_client_id" lo genera Gradle a partir de google-services.json
        int idCliente = getResources().getIdentifier("default_web_client_id", "string", getPackageName());
        if (idCliente == 0) {
            mostrarError(tvError, getString(R.string.google_sin_configurar));
            return;
        }
        tvError.setVisibility(android.view.View.GONE);
        btn.setEnabled(false);
        btn.setText(R.string.conectando_google);
        progreso.setVisibility(android.view.View.VISIBLE);

        com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption opcion =
                new com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption.Builder(getString(idCliente)).build();
        androidx.credentials.GetCredentialRequest pedido = new androidx.credentials.GetCredentialRequest.Builder()
                .addCredentialOption(opcion).build();

        androidx.credentials.CredentialManager.create(this).getCredentialAsync(this, pedido,
                new android.os.CancellationSignal(), androidx.core.content.ContextCompat.getMainExecutor(this),
                new androidx.credentials.CredentialManagerCallback<androidx.credentials.GetCredentialResponse,
                        androidx.credentials.exceptions.GetCredentialException>() {
                    @Override
                    public void onResult(androidx.credentials.GetCredentialResponse respuesta) {
                        androidx.credentials.Credential c = respuesta.getCredential();
                        if (c instanceof androidx.credentials.CustomCredential && c.getType().equals(
                                com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)) {
                            // Ya eligió la cuenta: el botón de Google muestra "Iniciando sesión…"
                            btn.setText(R.string.iniciando_sesion);
                            String token = com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
                                    .createFrom(c.getData()).getIdToken();
                            firebaseConGoogle(token, btn, progreso, tvError);
                        } else {
                            fallaGoogle(btn, progreso, tvError, getString(R.string.google_error));
                        }
                    }

                    @Override
                    public void onError(androidx.credentials.exceptions.GetCredentialException e) {
                        if (e instanceof androidx.credentials.exceptions.GetCredentialCancellationException) {
                            fallaGoogle(btn, progreso, tvError, null); // la persona cerró la ventana
                        } else if (e instanceof androidx.credentials.exceptions.NoCredentialException) {
                            fallaGoogle(btn, progreso, tvError, getString(R.string.google_sin_cuenta));
                        } else {
                            fallaGoogle(btn, progreso, tvError, getString(R.string.google_error));
                        }
                    }
                });
    }

    private void firebaseConGoogle(String token, MaterialButton btn, android.view.View progreso, android.widget.TextView tvError) {
        auth.signInWithCredential(com.google.firebase.auth.GoogleAuthProvider.getCredential(token, null))
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful() || auth.getCurrentUser() == null) {
                        fallaGoogle(btn, progreso, tvError, mensajeError(task.getException()));
                        return;
                    }
                    com.google.firebase.auth.FirebaseUser u = auth.getCurrentUser();
                    com.google.firebase.database.DatabaseReference ref = com.google.firebase.database.FirebaseDatabase
                            .getInstance().getReference("users").child(u.getUid());
                    // Primera vez con Google: se crea el perfil (mismos datos que al registrarse)
                    ref.get().addOnCompleteListener(t -> {
                        if (t.isSuccessful() && !t.getResult().exists()) {
                            java.util.Map<String, Object> datos = new java.util.HashMap<>();
                            datos.put("nombre", u.getDisplayName() == null ? "" : u.getDisplayName());
                            datos.put("correo", u.getEmail() == null ? "" : u.getEmail());
                            datos.put("carrera", "");
                            datos.put("semestre", "");
                            datos.put("creado", com.google.firebase.database.ServerValue.TIMESTAMP);
                            ref.setValue(datos);
                        }
                        Sesion.iniciar(this);
                        entrarConPerfil();
                    });
                });
    }

    private void fallaGoogle(MaterialButton btn, android.view.View progreso, android.widget.TextView tvError, String mensaje) {
        btn.setEnabled(true);
        btn.setText(R.string.continuar_google);
        progreso.setVisibility(android.view.View.GONE);
        if (mensaje != null) mostrarError(tvError, mensaje);
    }

    /**
     * Lee el perfil ANTES de abrir Inicio, para que salga "Hola, <nombre>" desde el primer momento
     * (tanto al iniciar sesión como al abrir la app con la sesión ya iniciada).
     * Si Firebase tarda más de 3 segundos (o no hay internet), entra igual.
     */
    private void entrarConPerfil() {
        final boolean[] entro = {false};
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable entrar = () -> { if (!entro[0]) { entro[0] = true; irAlInicio(); } };
        h.postDelayed(entrar, 3000);
        Repo.leerPerfil(p -> { h.removeCallbacks(entrar); entrar.run(); });
    }

    private void irAlInicio() {
        startActivity(new Intent(this, DashboardActivity.class));
        finish();
    }

    private void mostrarError(android.widget.TextView tv, String mensaje) {
        tv.setText(mensaje);
        tv.setVisibility(android.view.View.VISIBLE);
    }

    private String mensajeError(Exception e) {
        if (e instanceof FirebaseNetworkException) return getString(R.string.sin_internet);
        if (e instanceof FirebaseAuthInvalidUserException
                || e instanceof FirebaseAuthInvalidCredentialsException) return getString(R.string.credenciales_mal);
        return getString(R.string.error_generico);
    }
}
