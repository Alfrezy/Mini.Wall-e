#include "secrets.h"   // Aquí van WIFI_SSID y WIFI_PASS (copia secrets.h.example como secrets.h)

#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include "esp_camera.h"

// ===========================
// Pines para AI-THINKER ESP32-CAM
// ===========================
#define PWDN_GPIO_NUM     32
#define RESET_GPIO_NUM    -1
#define XCLK_GPIO_NUM      0
#define SIOD_GPIO_NUM     26
#define SIOC_GPIO_NUM     27
#define Y9_GPIO_NUM       35
#define Y8_GPIO_NUM       34
#define Y7_GPIO_NUM       39
#define Y6_GPIO_NUM       36
#define Y5_GPIO_NUM       21
#define Y4_GPIO_NUM       19
#define Y3_GPIO_NUM       18
#define Y2_GPIO_NUM        5
#define VSYNC_GPIO_NUM    25
#define HREF_GPIO_NUM     23
#define PCLK_GPIO_NUM     22

// Configuración de IP Fija para la ESP32-CAM
IPAddress local_IP_cam(192, 168, 137, 201);
IPAddress gateway_cam(192, 168, 137, 1);
IPAddress subnet_cam(255, 255, 255, 0);
IPAddress primaryDNS(8, 8, 8, 8);

// URL del backend de Java (puerto 9090: el 8080 es el panel web, NO el backend)
// 192.168.137.1 = la computadora que comparte el hotspot de Windows
const char* backendUrl = "http://192.168.137.1:9090/api/robot/reconocer";

// Intervalo entre fotos (sin bloquear la ejecución). El backend ignora las fotos mientras el robot se mueve.
unsigned long lastCaptureTime = 0;
const unsigned long captureInterval = 1500; // 1500 ms = 1.5 segundos
int fallosSeguidos = 0;                       // para reconectar el WiFi si falla muchas veces

// ---- Orientación de la imagen: míralo en el panel (Última foto) y cambia lo que haga falta ----
const bool VOLTEAR_VERTICAL  = true;   // pon false si la imagen sale cabeza abajo
const bool ESPEJO_HORIZONTAL = false;  // pon true si la izquierda y la derecha salen invertidas

// ---- Estabilidad del WiFi (la cámara hace ruido y consume corriente en ráfagas) ----
const int XCLK_HZ = 10000000;                          // reloj de la cámara: 10 MHz interfiere menos con el WiFi (si la cámara no inicia, vuelve a 20000000)
const wifi_power_t POTENCIA_WIFI = WIFI_POWER_15dBm;   // menos corriente por ráfaga; con señal de -30 dBm sobra

void setup() {
    Serial.begin(115200);
    Serial.println("\nIniciando ESP32-CAM en modo de intervalos estables...");

    // Diagnóstico de memoria (si dice PSRAM: NO, activa Herramientas > PSRAM > Enabled en el Arduino IDE)
    Serial.printf("PSRAM: %s | Tamaño: %u bytes | Libre: %u bytes | Memoria interna libre: %u bytes\n",
                  psramFound() ? "SI" : "NO", ESP.getPsramSize(), ESP.getFreePsram(), ESP.getFreeHeap());

    // 1. PRIMERO la cámara: necesita memoria continua y el WiFi la fragmenta si se inicia antes
    camera_config_t config = {};   // en cero: evita que campos no usados de la librería tengan basura
    config.ledc_channel = LEDC_CHANNEL_0;
    config.ledc_timer = LEDC_TIMER_0;
    config.pin_d0 = Y2_GPIO_NUM;
    config.pin_d1 = Y3_GPIO_NUM;
    config.pin_d2 = Y4_GPIO_NUM;
    config.pin_d3 = Y5_GPIO_NUM;
    config.pin_d4 = Y6_GPIO_NUM;
    config.pin_d5 = Y7_GPIO_NUM;
    config.pin_d6 = Y8_GPIO_NUM;
    config.pin_d7 = Y9_GPIO_NUM;
    config.pin_xclk = XCLK_GPIO_NUM;
    config.pin_pclk = PCLK_GPIO_NUM;
    config.pin_vsync = VSYNC_GPIO_NUM;
    config.pin_href = HREF_GPIO_NUM;
    config.pin_sccb_sda = SIOD_GPIO_NUM;
    config.pin_sccb_scl = SIOC_GPIO_NUM;
    config.pin_pwdn = PWDN_GPIO_NUM;
    config.pin_reset = RESET_GPIO_NUM;

    config.xclk_freq_hz = XCLK_HZ;
    config.pixel_format = PIXFORMAT_JPEG;

    // Mismo patrón del ejemplo oficial CameraWebServer (que contempla el sensor OV3660):
    // se inicia con un tamaño grande para reservar un búfer amplio y DESPUÉS se baja a QVGA.
    config.frame_size = FRAMESIZE_UXGA;
    config.jpeg_quality = 12;
    config.fb_count = 1;
    config.fb_location = CAMERA_FB_IN_PSRAM;
    config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;

    esp_err_t err = ESP_FAIL;
    if (psramFound()) {
        config.jpeg_quality = 10;
        config.fb_count = 2;
        config.grab_mode = CAMERA_GRAB_LATEST;      // siempre la foto más reciente
        err = esp_camera_init(&config);
        if (err != ESP_OK) {
            Serial.printf("Falló con PSRAM (0x%x). Probando con la memoria interna...\n", err);
            esp_camera_deinit();
        }
    }

    // Intento 2: sin PSRAM (1 búfer en la memoria interna, tamaño inicial menor)
    if (err != ESP_OK) {
        config.frame_size = FRAMESIZE_SVGA;
        config.jpeg_quality = 12;
        config.fb_count = 1;
        config.fb_location = CAMERA_FB_IN_DRAM;
        config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;
        err = esp_camera_init(&config);
    }

    if (err != ESP_OK) {
        Serial.printf("Error al iniciar la cámara: 0x%x. Reiniciando en 3 s...\n", err);
        delay(3000);
        ESP.restart();
    }
    Serial.printf("Cámara iniciada usando %s.\n", config.fb_location == CAMERA_FB_IN_PSRAM ? "PSRAM" : "memoria interna");

    // 2. Ajustes del sensor y tamaño final de las fotos (320x240)
    sensor_t *s = esp_camera_sensor_get();
    if (s != NULL) {
        Serial.printf("Sensor detectado: PID=0x%x (%s)\n", s->id.PID,
                      s->id.PID == OV3660_PID ? "OV3660" : (s->id.PID == OV2640_PID ? "OV2640" : "otro"));
        s->set_vflip(s, VOLTEAR_VERTICAL ? 1 : 0);
        s->set_hmirror(s, ESPEJO_HORIZONTAL ? 1 : 0);
        if (s->id.PID == OV3660_PID) {
            s->set_brightness(s, 1);   // ajustes que recomienda el ejemplo oficial para el OV3660
            s->set_saturation(s, -2);
        } else {
            s->set_contrast(s, 1);
            s->set_saturation(s, 1);
        }
        s->set_framesize(s, FRAMESIZE_QVGA);   // 320x240: ligero para enviar cada 1.5 s
    }

    // 3. Ahora sí: IP fija y Wi-Fi
    WiFi.config(local_IP_cam, gateway_cam, subnet_cam, primaryDNS);
    WiFi.begin(WIFI_SSID, WIFI_PASS);
    WiFi.setSleep(false); // ¡Clave! Desactiva el ahorro de energía para evitar microcortes

    while (WiFi.status() != WL_CONNECTED) {
        delay(500);
        Serial.print(".");
    }
    Serial.println("\nWiFi Conectado!");
    WiFi.setSleep(false);               // sin ahorro de energía del módem (se aplica después de conectar)
    WiFi.setTxPower(POTENCIA_WIFI);
    Serial.print("IP Fija de la Cámara: ");
    Serial.println(WiFi.localIP());

    Serial.println("¡Lista! Enviando fotogramas al backend para reconocimiento...");
}

void loop() {
    unsigned long currentMillis = millis();

    // Capturar y enviar foto cada captureInterval de forma no bloqueante
    if (currentMillis - lastCaptureTime >= captureInterval) {
        lastCaptureTime = currentMillis;

        if (WiFi.status() == WL_CONNECTED) {
            camera_fb_t *fb = esp_camera_fb_get();
            if (!fb) {
                Serial.println("¡Error al capturar la imagen del sensor!");
                return;
            }

            // Realizar petición HTTP POST enviando los bytes del JPEG actual
            unsigned long inicio = millis();
            HTTPClient http;
            http.begin(backendUrl);
            http.setReuse(false);          // una conexión nueva por foto: más estable
            http.setConnectTimeout(2000);  // si el backend no responde, no se queda colgada
            http.setTimeout(4000); 
            http.addHeader("Content-Type", "image/jpeg");
            http.addHeader("Connection", "close");

            int httpResponseCode = http.POST(fb->buf, fb->len);
            unsigned long ms = millis() - inicio;

            if (httpResponseCode > 0) {
                fallosSeguidos = 0;
                String response = http.getString();
                Serial.printf("📸 Foto enviada (%u KB, %lu ms). Código: %d | Objeto detectado: %s\n",
                              (unsigned)(fb->len / 1024), ms, httpResponseCode, response.c_str());
            } else {
                fallosSeguidos++;
                Serial.printf("❌ Error al enviar la foto: %s (%d) | %lu ms | señal WiFi: %d dBm | foto: %u KB\n",
                              HTTPClient::errorToString(httpResponseCode).c_str(), httpResponseCode, ms,
                              WiFi.RSSI(), (unsigned)(fb->len / 1024));
                if (fallosSeguidos % 10 == 0) {
                    // El WiFi está bien (se ve en la señal): el problema es llegar al backend
                    Serial.println("⚠️ Muchos fallos seguidos. Revisa que el backend esté encendido (docker compose ps)");
                    Serial.println("   y que el firewall de Windows permita el puerto 9090.");
                }
            }

            http.end();
            esp_camera_fb_return(fb); // Liberar memoria del búfer obligatoriamente
        } else {
            Serial.println("WiFi desconectado. Reintentando reconexión...");
            WiFi.reconnect();
        }
    }
}