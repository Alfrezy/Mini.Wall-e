package com.walle.robot;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Esp32Cliente.java
 *
 * Es la "mano" del backend: se conecta por WebSocket a la ESP32 principal
 * (ws://192.168.137.200/ws) y le manda los mismos mensajes JSON que le manda
 * la página web (teclado.js):
 *   {"accion": N, "pwm": 0-255}     -> motores
 *   {"canal": 0-3, "angulo": 0-180} -> servos
 *
 * Si la conexión se cae o la ESP32 está apagada, reintenta cada 3 segundos.
 * No hace falta cambiar el código de la ESP32.
 */
@Component
public class Esp32Cliente {

    private final String url;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ScheduledExecutorService reconectador = Executors.newSingleThreadScheduledExecutor();

    private volatile WebSocket ws;
    private volatile boolean conectando = false;
    private volatile boolean avisoDesconectado = false;

    public Esp32Cliente(@Value("${walle.esp32.ws-url}") String url) {
        this.url = url;
    }

    @PostConstruct
    public void iniciar() {
        reconectador.scheduleWithFixedDelay(this::asegurarConexion, 0, 3, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void cerrar() {
        reconectador.shutdownNow();
        WebSocket actual = ws;
        if (actual != null) {
            actual.sendClose(WebSocket.NORMAL_CLOSURE, "backend apagado");
        }
    }

    private void asegurarConexion() {
        if (estaConectado() || conectando) return;
        conectando = true;
        try {
            ws = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .buildAsync(URI.create(url), new Escucha())
                    .get(5, TimeUnit.SECONDS);
            avisoDesconectado = false;
            System.out.println("🟢 [ESP32] Backend conectado a " + url);
        } catch (Exception e) {
            ws = null;
            if (!avisoDesconectado) {
                System.err.println("🔴 [ESP32] No se pudo conectar a " + url + " (reintentando cada 3 s)");
                avisoDesconectado = true;
            }
        } finally {
            conectando = false;
        }
    }

    public boolean estaConectado() {
        WebSocket actual = ws;
        return actual != null && !actual.isInputClosed() && !actual.isOutputClosed();
    }

    private synchronized boolean enviar(String json) {
        if (!estaConectado()) {
            System.err.println("⚠️ [ESP32] Sin conexión. No se envió: " + json);
            return false;
        }
        try {
            ws.sendText(json, true).get(2, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            System.err.println("❌ [ESP32] Error al enviar " + json + ": " + e.getMessage());
            ws = null;
            return false;
        }
    }

    /** accion: 0 parar, 1 giro izquierda, 2 adelante, 3 giro derecha, 8 atrás. pwm: 0-255 */
    public boolean mover(int accion, int pwm) {
        return enviar("{\"accion\":" + accion + ",\"pwm\":" + pwm + "}");
    }

    public boolean parar() {
        return mover(0, 0);
    }

    /** canal: 0 brazo der, 1 brazo izq, 2 compuerta der, 3 compuerta izq. angulo: 0-180 */
    public boolean servo(int canal, int angulo) {
        return enviar("{\"canal\":" + canal + ",\"angulo\":" + angulo + "}");
    }

    private class Escucha implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (ws == webSocket) ws = null;
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (ws == webSocket) ws = null;
        }
    }
}
