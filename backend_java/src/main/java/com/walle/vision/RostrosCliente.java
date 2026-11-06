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
 * RostrosCliente.java
 *
 * Le manda la foto al servicio de visión (Python, contenedor ai-engine) para que reconozca caras
 * con tu red de reconocimiento facial (YuNet + SFace + embeddings.npz).
 *
 * Si el servicio no responde, devuelve una lista vacía y no lo vuelve a intentar durante 5 segundos
 * (así una caída de ai-engine no hace lenta la llegada de las fotos).
 */
@Component
public class RostrosCliente {

    /**
     * Una cara. x, y, w, h = bounding box normalizado de 0 a 1 (x, y = esquina superior izquierda).
     * nombre = "Fredi", "Odir"... o "desconocido" si no se parece a nadie.
     */
    public record Rostro(String nombre, double similitud, boolean reconocido, double x, double y, double w, double h) {
        public double area() { return w * h; }
    }

    private final String url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile boolean disponible = false;
    private volatile long ultimoIntento = 0;
    private volatile long ultimoAviso = 0;

    public RostrosCliente(@Value("${walle.vision.url}") String url) {
        this.url = url;
    }

    public boolean disponible() {
        return disponible;
    }

    public List<Rostro> detectar(byte[] jpeg) {
        long ahora = System.currentTimeMillis();
        if (!disponible && ahora - ultimoIntento < 5000) {
            return List.of();   // falló hace poco: no insiste todavía
        }
        ultimoIntento = ahora;
        try {
            HttpRequest peticion = HttpRequest.newBuilder(URI.create(url + "/rostros"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "image/jpeg")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(jpeg))
                    .build();
            HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString());
            if (respuesta.statusCode() != 200) {
                avisar("respondió " + respuesta.statusCode() + ": " + respuesta.body());
                return List.of();
            }

            List<Rostro> lista = new ArrayList<>();
            JsonNode raiz = mapper.readTree(respuesta.body());
            for (JsonNode n : raiz.path("rostros")) {
                lista.add(new Rostro(n.path("nombre").asText("desconocido"), n.path("similitud").asDouble(),
                        n.path("reconocido").asBoolean(false), n.path("x").asDouble(), n.path("y").asDouble(),
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

    private void avisar(String motivo) {
        disponible = false;
        long ahora = System.currentTimeMillis();
        if (ahora - ultimoAviso > 10_000) {
            ultimoAviso = ahora;
            System.err.println("⚠️ [CARAS] No se pudo consultar " + url + "/rostros: " + motivo);
        }
    }
}
