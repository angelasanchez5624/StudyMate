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
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import java.util.HashMap;
import java.util.Map;

public class RegisterActivity extends AppCompatActivity {

    private FirebaseAuth auth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);
        Ui.insets(this);

        auth = FirebaseAuth.getInstance();

        EditText etNombre = findViewById(R.id.etNombre);
        EditText etCorreo = findViewById(R.id.etCorreo);
        EditText etPassword = findViewById(R.id.etPassword);
        PasswordToggle.setup(etPassword);
        EditText etConfirmar = findViewById(R.id.etConfirmar);
        PasswordToggle.setup(etConfirmar);
        MaterialButton btnRegistrar = findViewById(R.id.btnRegistrar);

        findViewById(R.id.btnAtras).setOnClickListener(v -> finish());
        findViewById(R.id.tvIniciarSesion).setOnClickListener(v -> finish());

        SinConexion.vigilar(this, false); // sin internet no se puede crear la cuenta

        // Aviso de error debajo de los campos (⚠ + texto rojo), en vez del "!" de Android que tapaba el ojito
        android.widget.TextView tvError = findViewById(R.id.tvErrorRegistro);
        android.text.TextWatcher ocultarError = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { tvError.setVisibility(android.view.View.GONE); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        };
        etNombre.addTextChangedListener(ocultarError);
        etCorreo.addTextChangedListener(ocultarError);
        etPassword.addTextChangedListener(ocultarError);
        etConfirmar.addTextChangedListener(ocultarError);

        btnRegistrar.setOnClickListener(v -> {
            if (!SinConexion.revisar(this)) return;
            String nombre = etNombre.getText().toString().trim();
            String correo = etCorreo.getText().toString().trim();
            String pass = etPassword.getText().toString();
            String confirmar = etConfirmar.getText().toString();

            if (nombre.isEmpty()) { mostrarError(tvError, etNombre, R.string.nombre_error); return; }
            if (!Patterns.EMAIL_ADDRESS.matcher(correo).matches()) { mostrarError(tvError, etCorreo, R.string.correo_invalido); return; }
            if (pass.length() < 6) { mostrarError(tvError, etPassword, R.string.min_6); return; }
            if (!pass.equals(confirmar)) { mostrarError(tvError, etConfirmar, R.string.claves_no_coinciden); return; }

            btnRegistrar.setEnabled(false);

            Sesion.cerrar(this);

            auth.createUserWithEmailAndPassword(correo, pass).addOnCompleteListener(task -> {
                if (!task.isSuccessful()) {
                    btnRegistrar.setEnabled(true);
                    tvError.setText(mensajeError(task.getException()));
                    tvError.setVisibility(android.view.View.VISIBLE);
                    return;
                }

                // El nombre también queda en la cuenta, para saludar al instante al iniciar sesión
                auth.getCurrentUser().updateProfile(new com.google.firebase.auth.UserProfileChangeRequest.Builder()
                        .setDisplayName(nombre).build());

                String uid = auth.getCurrentUser().getUid();
                Map<String, Object> datos = new HashMap<>();
                datos.put("nombre", nombre);
                datos.put("correo", correo);
                datos.put("carrera", "");
                datos.put("semestre", "");
                datos.put("creado", ServerValue.TIMESTAMP);

                FirebaseDatabase.getInstance().getReference("users").child(uid).setValue(datos)
                        .addOnCompleteListener(t -> {
                            if (!t.isSuccessful()) {
                                Toast.makeText(this, R.string.cuenta_sin_datos, Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(this, R.string.cuenta_creada, Toast.LENGTH_LONG).show();
                            }

                            Sesion.cerrar(this);
                            Intent i = new Intent(this, LoginActivity.class);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            startActivity(i);
                        });
            });
        });
    }

    private String mensajeError(Exception e) {
        if (e instanceof FirebaseNetworkException) return getString(R.string.sin_internet);
        if (e instanceof FirebaseAuthUserCollisionException) return getString(R.string.correo_registrado);
        if (e instanceof FirebaseAuthWeakPasswordException) return getString(R.string.clave_debil);
        if (e instanceof FirebaseAuthInvalidCredentialsException) return getString(R.string.correo_no_valido);
        return getString(R.string.error_crear_cuenta);
    }

    /** Muestra el error debajo de los campos y pone el cursor en el campo que hay que corregir. */
    private void mostrarError(android.widget.TextView tv, android.widget.EditText campo, int mensaje) {
        tv.setText(mensaje);
        tv.setVisibility(android.view.View.VISIBLE);
        campo.requestFocus();
    }
}
