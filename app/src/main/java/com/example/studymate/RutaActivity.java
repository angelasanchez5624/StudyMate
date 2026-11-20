package com.example.studymate;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.DashPathEffect;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

/**
 * Cómo llegar, dentro de la app (OpenStreetMap): dibuja la ruta a pie desde donde estás
 * hasta el salón, con la distancia y el tiempo aproximado. No abre Google Maps.
 *
 * La ruta la calcula el servicio público de rutas de OpenStreetMap (OSRM, perfil "a pie",
 * routing.openstreetmap.de). Si no responde, se muestra una línea recta con la distancia.
 *
 * Recibe: "lat", "lon" (destino) y "nombre" (materia o salón).
 */
public class RutaActivity extends AppCompatActivity {

    /** Servicio de rutas a pie de OpenStreetMap (FOSSGIS). Formato: lon,lat;lon,lat */
    private static final String SERVICIO_RUTAS =
            "https://routing.openstreetmap.de/routed-foot/route/v1/driving/%s,%s;%s,%s?overview=full&geometries=geojson";

    private MapView mapa;
    private GeoPoint destino;
    private Polyline linea, borde;
    private Marker yo;
    private TextView tvInfo, tvDetalle;
    private View progreso;
    private boolean calculada = false;

    private final ActivityResultLauncher<String[]> pedirUbicacion =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> {
                if (tienePermiso()) empezar();
                else {
                    mostrarInfo(getString(R.string.ruta_sin_permiso), false);
                    Toast.makeText(this, R.string.sin_permiso_ubicacion, Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Mapa.configurar(this); // antes de crear el mapa
        setContentView(R.layout.activity_ruta);
        Ui.insets(this);

        double lat = getIntent().getDoubleExtra("lat", Mapa.LAT_DEFECTO);
        double lon = getIntent().getDoubleExtra("lon", Mapa.LON_DEFECTO);
        String nombre = getIntent().getStringExtra("nombre");
        destino = new GeoPoint(lat, lon);

        ((TextView) findViewById(R.id.tvDestino)).setText(nombre == null ? "" : nombre);
        tvInfo = findViewById(R.id.tvRutaInfo);
        tvDetalle = findViewById(R.id.tvRutaDetalle);
        progreso = findViewById(R.id.progresoRuta);
        findViewById(R.id.btnAtras).setOnClickListener(v -> finish());

        mapa = findViewById(R.id.mapa);
        Mapa.preparar(mapa); // el mismo mapa de OpenStreetMap del resto de la app
        mapa.setMaxZoomLevel(19.0); // más cerca el mapa se ve pixelado
        mapa.getController().setZoom(Mapa.ZOOM_LUGAR - 1);
        mapa.getController().setCenter(destino);
        Mapa.marcador(mapa, destino, nombre, null); // el pin del salón (la mano)

        findViewById(R.id.btnRecalcular).setOnClickListener(v -> {
            calculada = false;
            if (tienePermiso()) empezar();
            else pedirPermiso();
        });

        if (tienePermiso()) empezar();
        else pedirPermiso();
    }

    private void pedirPermiso() {
        pedirUbicacion.launch(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
    }

    private boolean tienePermiso() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------- Ubicación precisa (GPS) ----------------

    /** Precisión que se espera antes de calcular la ruta (metros). */
    private static final float PRECISION_BUENA = 20f;
    /** Si en este tiempo no llega a esa precisión, se usa la mejor que haya. */
    private static final long ESPERA_MAX_MS = 15000;

    private android.location.LocationManager gestor;
    private android.location.Location mejor;
    private final android.os.Handler espera = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Peor precisión aceptada para calcular la ruta (metros). Más que esto es una ubicación "adivinada". */
    private static final float PRECISION_MAXIMA = 60f;
    /** Ubicación y precisión con las que se calculó la ruta (para recalcular si llega una mejor). */
    private GeoPoint origenRuta;
    private float precisionRuta = Float.MAX_VALUE;

    private final android.location.LocationListener escucha = loc -> {
        // Se queda con la más precisa; una más nueva solo reemplaza si no es mucho peor
        if (mejor == null || loc.getAccuracy() <= mejor.getAccuracy()
                || (loc.getTime() - mejor.getTime() > 20000 && loc.getAccuracy() <= mejor.getAccuracy() * 2)) {
            mejor = loc;
        }
        GeoPoint p = new GeoPoint(mejor.getLatitude(), mejor.getLongitude());
        if (mejor.getAccuracy() <= PRECISION_MAXIMA) moverPunto(p); // no se muestra una ubicación muy imprecisa
        if (!calculada) {
            mostrarInfo(getString(R.string.mejorando_precision, Math.round(mejor.getAccuracy())), true);
            if (mejor.getAccuracy() <= PRECISION_BUENA) {
                espera.removeCallbacksAndMessages(null);
                calcular(p);
            }
        } else if (origenRuta != null && mejor.getAccuracy() < precisionRuta / 2
                && p.distanceToAsDouble(origenRuta) > 25) {
            // Llegó una ubicación bastante más precisa y distinta: se recalcula sola
            calculada = false;
            calcular(p);
        }
    };

    /**
     * Busca tu ubicación (GPS + ubicación combinada de Android) y espera a que sea precisa
     * (±20 m) antes de calcular la ruta. Si en 15 s solo hay una aproximada (±60 m o menos),
     * usa esa; si es peor, sigue esperando y te avisa.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private void empezar() {
        if (gestor == null) gestor = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        mostrarInfo(getString(R.string.buscando_ubicacion), true);
        tvDetalle.setVisibility(View.GONE);
        try {
            // "fused" combina GPS, wifi y sensores (la más exacta); "gps" como respaldo.
            // No se usa solo la red: puede ubicarte a más de 1 km.
            for (String prov : new String[]{android.location.LocationManager.FUSED_PROVIDER,
                    android.location.LocationManager.GPS_PROVIDER}) {
                if (!gestor.getAllProviders().contains(prov) || !gestor.isProviderEnabled(prov)) continue;
                gestor.requestLocationUpdates(prov, 1000, 0f, escucha, android.os.Looper.getMainLooper());
                android.location.Location ultima = gestor.getLastKnownLocation(prov);
                if (ultima != null && System.currentTimeMillis() - ultima.getTime() < 30000) escucha.onLocationChanged(ultima);
            }
        } catch (SecurityException ignored) { }
        espera.removeCallbacksAndMessages(null);
        espera.postDelayed(() -> {
            if (calculada) return;
            if (mejor != null && mejor.getAccuracy() <= PRECISION_MAXIMA) {
                calcular(new GeoPoint(mejor.getLatitude(), mejor.getLongitude()));
            } else {
                mostrarInfo(getString(R.string.ubicacion_imprecisa), true);
                tvDetalle.setText(R.string.ubicacion_imprecisa_detalle);
                tvDetalle.setVisibility(View.VISIBLE);
            }
        }, ESPERA_MAX_MS);
    }

    /** Punto azul: dónde estás (se mueve mientras caminas). */
    private void moverPunto(GeoPoint p) {
        if (yo == null) {
            yo = new Marker(mapa);
            yo.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            yo.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_mi_ubicacion_punto));
            yo.setTitle(getString(R.string.estas_aqui));
            yo.setInfoWindow(null);
            mapa.getOverlays().add(yo);
        }
        yo.setPosition(p);
        mapa.invalidate();
    }

    /** Pide la ruta a pie al servicio de OpenStreetMap (fuera del hilo principal) y la dibuja. */
    private void calcular(GeoPoint origen) {
        if (calculada || isFinishing()) return;
        calculada = true;
        origenRuta = origen;
        precisionRuta = mejor == null ? Float.MAX_VALUE : mejor.getAccuracy();
        // Ya estás en el salón: el GPS tiene un margen de error de unos metros (y más bajo techo),
        // así que si estás a menos de 35 m (o dentro del margen de error) no se dibuja ruta
        double margen = Math.max(LLEGASTE_METROS, mejor == null ? 0 : mejor.getAccuracy());
        if (origen.distanceToAsDouble(destino) <= margen) {
            moverPunto(origen);
            if (linea != null) mapa.getOverlays().remove(linea);
            if (borde != null) mapa.getOverlays().remove(borde);
            mapa.getController().animateTo(destino, Mapa.ZOOM_LUGAR, 600L);
            mostrarInfo(getString(R.string.ruta_llegaste), false);
            tvDetalle.setText(R.string.ruta_llegaste_detalle);
            tvDetalle.setVisibility(View.VISIBLE);
            return;
        }
        mostrarInfo(getString(R.string.ruta_calculando), true);
        new Thread(() -> {
            Ruta r = pedirRuta(origen, destino);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                dibujar(origen, r);
            });
        }).start();
    }

    /** A esta distancia (metros) o menos, se considera que ya estás en el salón. */
    private static final double LLEGASTE_METROS = 35;

    /** Velocidad al caminar (≈ 4,7 km/h) para calcular el tiempo. */
    private static final double METROS_POR_SEGUNDO = 1.3;

    /**
     * Dibuja: tu ubicación → camino por las calles → el salón. El servicio de rutas solo
     * llega hasta la calle más cercana, así que se unen los extremos para que la línea
     * empiece en ti y termine en el pin.
     */
    private void dibujar(GeoPoint origen, Ruta r) {
        List<GeoPoint> camino = new ArrayList<>();
        camino.add(origen);
        if (r != null) camino.addAll(r.puntos);
        camino.add(destino);
        double metros = 0;
        for (int k = 1; k < camino.size(); k++) metros += camino.get(k - 1).distanceToAsDouble(camino.get(k));
        boolean aproximada = r == null;

        if (linea != null) mapa.getOverlays().remove(linea);
        if (borde != null) mapa.getOverlays().remove(borde);
        // Borde blanco debajo de la línea: hace que la ruta resalte sobre el mapa
        borde = new Polyline(mapa);
        borde.setPoints(camino);
        borde.getOutlinePaint().setColor(0xFFFFFFFF);
        borde.getOutlinePaint().setStrokeWidth(26f);
        borde.getOutlinePaint().setStrokeCap(android.graphics.Paint.Cap.ROUND);
        borde.getOutlinePaint().setStrokeJoin(android.graphics.Paint.Join.ROUND);
        borde.setInfoWindow(null);
        mapa.getOverlays().add(0, borde);
        linea = new Polyline(mapa);
        linea.setPoints(camino);
        linea.getOutlinePaint().setColor(ContextCompat.getColor(this, R.color.boton));
        linea.getOutlinePaint().setStrokeWidth(14f);
        linea.getOutlinePaint().setStrokeCap(android.graphics.Paint.Cap.ROUND);
        linea.getOutlinePaint().setStrokeJoin(android.graphics.Paint.Join.ROUND);
        if (aproximada) linea.getOutlinePaint().setPathEffect(new DashPathEffect(new float[]{28f, 20f}, 0));
        linea.setInfoWindow(null);
        mapa.getOverlays().add(1, linea); // encima del borde y debajo de los pines

        moverPunto(origen);

        // Encuadre: que se vean tú y el salón, con margen
        BoundingBox caja = BoundingBox.fromGeoPointsSafe(camino);
        mapa.post(() -> mapa.zoomToBoundingBox(caja.increaseByScale(1.6f), true));
        mapa.invalidate();

        String distancia = metros >= 1000
                ? String.format(Locale.getDefault(), "%.1f km", metros / 1000)
                : Math.round(metros) + " m";
        long min = Math.max(1, Math.round(metros / METROS_POR_SEGUNDO / 60));
        mostrarInfo(getString(R.string.ruta_minutos, min), false);
        tvDetalle.setText(getString(aproximada ? R.string.ruta_detalle_recta : R.string.ruta_detalle, distancia));
        tvDetalle.setVisibility(View.VISIBLE);
    }

    private void mostrarInfo(String texto, boolean cargando) {
        tvInfo.setText(texto);
        progreso.setVisibility(cargando ? View.VISIBLE : View.GONE);
    }

    /** Resultado del servicio de rutas. */
    private static class Ruta {
        List<GeoPoint> puntos = new ArrayList<>();
        double metros, segundos;
    }

    /** Llama al servicio OSRM a pie. Devuelve null si no hay internet o no hay ruta. */
    private static Ruta pedirRuta(GeoPoint a, GeoPoint b) {
        HttpURLConnection con = null;
        try {
            URL url = new URL(String.format(Locale.US, SERVICIO_RUTAS,
                    a.getLongitude(), a.getLatitude(), b.getLongitude(), b.getLatitude()));
            con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(10000);
            con.setReadTimeout(15000);
            con.setRequestProperty("User-Agent", "StudyMate/1.0 (Android; agenda universitaria; UPC Aguachica)");
            if (con.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"))) {
                String l;
                while ((l = in.readLine()) != null) sb.append(l);
            }
            JSONObject json = new JSONObject(sb.toString());
            if (!"Ok".equals(json.optString("code"))) return null;
            JSONArray rutas = json.getJSONArray("routes");
            if (rutas.length() == 0) return null;
            JSONObject ruta = rutas.getJSONObject(0);
            Ruta r = new Ruta();
            r.metros = ruta.getDouble("distance");
            r.segundos = ruta.getDouble("duration");
            JSONArray coords = ruta.getJSONObject("geometry").getJSONArray("coordinates");
            for (int i = 0; i < coords.length(); i++) {
                JSONArray c = coords.getJSONArray(i);
                r.puntos.add(new GeoPoint(c.getDouble(1), c.getDouble(0))); // viene como [lon, lat]
            }
            return r.puntos.size() < 2 ? null : r;
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapa.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        espera.removeCallbacksAndMessages(null);
        if (gestor != null) gestor.removeUpdates(escucha); // deja de usar el GPS al salir
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapa.onPause();
    }
}
