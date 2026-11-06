package com.walle.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * ArucoCliente.java
 *
 * Le manda la foto de la ESP32-CAM al servicio de visión (Python + OpenCV, contenedor ai-engine)
 * y recibe los marcadores ArUco que ve, cada uno con su "bounding box".
 *
 * Si el servicio no responde, devuelve una lista vacía (el robot sigue buscando) y avisa en consola.
 */
@Component
public class ArucoCliente {

    /** Un marcador. x, y, w, h = bounding box normalizado de 0 a 1 (x, y = esquina superior izquierda). */
    public record Marcador(int id, double x, double y, double w, double h) {
        public double centroX() { return x + w / 2.0; }
    }

    private final String url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile boolean disponible = false;
    private volatile long ultimoIntento = 0;
    private volatile long ultimoAviso = 0;

    public ArucoCliente(@Value("${walle.vision.url}") String url) {
        this.url = url;
    }

    /** true si la última consulta al servicio de visión funcionó. */
    public boolean disponible() {
        return disponible;
    }

    public List<Marcador> detectar(byte[] jpeg) {
        long ahora = System.currentTimeMillis();
        if (!disponible && ahora - ultimoIntento < 5000) {
            return List.of();   // falló hace poco: no insiste todavía (así una caída no frena las fotos)
        }
        ultimoIntento = ahora;
        try {
            HttpRequest peticion = HttpRequest.newBuilder(URI.create(url + "/aruco"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "image/jpeg")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(jpeg))
                    .build();
            HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString());
            if (respuesta.statusCode() != 200) {
                avisar("respondió " + respuesta.statusCode());
                return List.of();
            }

            List<Marcador> lista = new ArrayList<>();
            JsonNode raiz = mapper.readTree(respuesta.body());
            for (JsonNode n : raiz.path("marcadores")) {
                lista.add(new Marcador(n.path("id").asInt(), n.path("x").asDouble(), n.path("y").asDouble(),
                        n.path("w").asDouble(), n.path("h").asDouble()));
            }
            disponible = true;
            return lista;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            avisar(e.getMessage());
            return List.of();
        }
    }

    /** Avisa como máximo una vez cada 10 segundos para no llenar la consola. */
    private void avisar(String motivo) {
        disponible = false;
        long ahora = System.currentTimeMillis();
        if (ahora - ultimoAviso > 10_000) {
            ultimoAviso = ahora;
            System.err.println("⚠️ [VISION] No se pudo consultar " + url + "/aruco: " + motivo);
        }
    }
}
