#include <Arduino.h>
#include <WiFi.h>
#include <ESPAsyncWebServer.h>
#include <ArduinoJson.h>
#include <Wire.h>
#include <Adafruit_PWMServoDriver.h>

// ==========================================
// CREDENCIALES WIFI + IP FIJA
// ==========================================
const char *ssid = "ALFREZZY";
const char *password = "Canario71";

IPAddress local_IP(192, 168, 137, 200);
IPAddress gateway(192, 168, 137, 1);
IPAddress subnet(255, 255, 255, 0);
IPAddress primaryDNS(8, 8, 8, 8);

AsyncWebServer server(80);
AsyncWebSocket ws("/ws");

#define PIN_SDA 18
#define PIN_SCL 19

#define MOVER_SERVOS_AL_ARRANQUE true

// Velocidad suave y fluida para los servos
#define PASO_PULSO 4          
#define INTERVALO_SERVO_MS 15 

Adafruit_PWMServoDriver pwm = Adafruit_PWMServoDriver(0x40);

#define SERVOMIN  150 
#define SERVOMAX  600 
#define SERVO_FREQ 50 

#define NUM_SERVOS 4
#define CANAL_BRAZO_DER      0  // Servo Brazo Derecho
#define CANAL_BRAZO_IZQ      1  // Servo Brazo Izquierdo
#define CANAL_COMPUERTA_DER  2  // Compuerta Derecha
#define CANAL_COMPUERTA_IZQ  3  // Compuerta Izquierda

// ==========================================
// CONFIGURACIÓN DRIVER L298N / MOTORES
// ==========================================
#define ENA 32
#define IN1 33
#define IN2 25
#define ENB 26
#define IN3 27
#define IN4 14

void configurarMotores() {
  pinMode(IN1, OUTPUT);
  pinMode(IN2, OUTPUT);
  pinMode(IN3, OUTPUT);
  pinMode(IN4, OUTPUT);
  pinMode(ENA, OUTPUT);
  pinMode(ENB, OUTPUT);
}

void moverOrugas(int velIzq, int velDer) {
  if (velIzq > 0) {
    digitalWrite(IN1, HIGH);
    digitalWrite(IN2, LOW);
    analogWrite(ENA, velIzq);
  } else if (velIzq < 0) {
    digitalWrite(IN1, LOW);
    digitalWrite(IN2, HIGH);
    analogWrite(ENA, -velIzq);
  } else {
    digitalWrite(IN1, LOW);
    digitalWrite(IN2, LOW);
    analogWrite(ENA, 0);
  }

  if (velDer > 0) {
    digitalWrite(IN3, HIGH);
    digitalWrite(IN4, LOW);
    analogWrite(ENB, velDer);
  } else if (velDer < 0) {
    digitalWrite(IN3, LOW);
    digitalWrite(IN4, HIGH);
    analogWrite(ENB, -velDer);
  } else {
    digitalWrite(IN3, LOW);
    digitalWrite(IN4, LOW);
    analogWrite(ENB, 0);
  }
}

void realizarAccion(int accion, int pwmVal) {
  switch (accion) {
    case 0: moverOrugas(0, 0); break;                 
    case 1: moverOrugas(-pwmVal, pwmVal); break;      
    case 2: moverOrugas(pwmVal, pwmVal); break;       
    case 3: moverOrugas(pwmVal, -pwmVal); break;      
    case 8: moverOrugas(-pwmVal, -pwmVal); break;     
    case 10: moverOrugas(-pwmVal, pwmVal); break;     
    case 12: moverOrugas(pwmVal, -pwmVal); break;    
    default: moverOrugas(0, 0); break;
  }
}

// ==========================================
// SERVOS CON MOVIMIENTO SUAVE
// ==========================================
volatile int pulsoActual[NUM_SERVOS];
volatile int pulsoObjetivo[NUM_SERVOS];
volatile bool servoActivo[NUM_SERVOS];
volatile bool pendiente[NUM_SERVOS];
bool pcaOk = false;

bool pcaResponde() {
  Wire.beginTransmission(0x40);
  return Wire.endTransmission() == 0;
}

void iniciarPCA() {
  pwm.begin();
  pwm.setOscillatorFrequency(27000000);
  pwm.setPWMFreq(SERVO_FREQ);
  delay(10);
}

void fijarObjetivo(uint8_t canal, int pulso) {
  if (canal >= NUM_SERVOS) return;
  pulsoObjetivo[canal] = pulso;
  if (!servoActivo[canal]) {
    pulsoActual[canal] = pulso;
    pendiente[canal] = true;
    servoActivo[canal] = true;
  }
}

void moverServoDirecto(uint8_t canal, int anguloReal) {
  anguloReal = constrain(anguloReal, 0, 180);
  int pulso = map(anguloReal, 0, 180, SERVOMIN, SERVOMAX);
  fijarObjetivo(canal, pulso);
}

void actualizarServos(bool forzar) {
  static unsigned long ultimo = 0;
  unsigned long ahora = millis();
  if (!forzar && (ahora - ultimo < INTERVALO_SERVO_MS)) return;
  ultimo = ahora;

  if (!pcaOk) return;

  for (int c = 0; c < NUM_SERVOS; c++) {
    if (!servoActivo[c]) continue;

    int diff = pulsoObjetivo[c] - pulsoActual[c];
    if (diff == 0 && !pendiente[c]) continue;

    if (abs(diff) <= PASO_PULSO) {
      pulsoActual[c] = pulsoObjetivo[c];
    } else {
      pulsoActual[c] += (diff > 0) ? PASO_PULSO : -PASO_PULSO;
    }

    pwm.setPWM(c, 0, pulsoActual[c]);
    pendiente[c] = false;
  }
}

void vigilarPCA() {
  static unsigned long ultimo = 0;
  unsigned long ahora = millis();
  if (ahora - ultimo < 1000) return;
  ultimo = ahora;

  bool ok = pcaResponde();
  if (ok && !pcaOk) {
    iniciarPCA();
    for (int c = 0; c < NUM_SERVOS; c++) pendiente[c] = true;
  }
  pcaOk = ok;
}

void posicionesIniciales() {
  moverServoDirecto(CANAL_BRAZO_DER, 50);  // Reposo inicial Brazo Der
  moverServoDirecto(CANAL_BRAZO_IZQ, 50);  // Reposo inicial Brazo Izq
  moverServoDirecto(CANAL_COMPUERTA_DER, 90); // Cerrada Compuerta Der
  moverServoDirecto(CANAL_COMPUERTA_IZQ, 5);  // Cerrada Compuerta Izq
  actualizarServos(true);
}

// ==========================================
// WEBSOCKET (RECIBE ÓRDENES DIRECTAS DE JS)
// ==========================================
void onEvent(AsyncWebSocket *server, AsyncWebSocketClient *client, AwsEventType type, void *arg, uint8_t *data, size_t len) {
  switch (type) {
    case WS_EVT_CONNECT:
      Serial.printf("Cliente WebSocket #%u conectado\n", client->id());
      break;
    case WS_EVT_DISCONNECT:
      Serial.printf("Cliente WebSocket #%u desconectado\n", client->id());
      moverOrugas(0, 0);
      break;
    case WS_EVT_DATA: {
      AwsFrameInfo *info = (AwsFrameInfo *)arg;
      if (info->final && info->index == 0 && info->len == len && info->opcode == WS_TEXT) {
        String recibido = "";
        for (size_t i = 0; i < info->len; i++) {
          recibido += (char)data[i];
        }

        StaticJsonDocument<200> jsonDoc;
        DeserializationError error = deserializeJson(jsonDoc, recibido);
        if (!error) {
          // Recibe directamente el canal y el ángulo exacto calculado en el .js
          if (jsonDoc.containsKey("canal") && jsonDoc.containsKey("angulo")) {
            int canal = jsonDoc["canal"];
            int angulo = jsonDoc["angulo"];
            moverServoDirecto(canal, angulo);
          } 
          // Comandos de motores
          else if (jsonDoc.containsKey("accion")) {
            int accion = jsonDoc["accion"] | 0;
            int pwmVal = jsonDoc["pwm"] | 200; 
            pwmVal = constrain(pwmVal, 0, 255);
            realizarAccion(accion, pwmVal);
          }
        }
      }
      break;
    }
    default:
      break;
  }
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  configurarMotores();
  moverOrugas(0, 0);

  if (!WiFi.config(local_IP, gateway, subnet, primaryDNS)) {
    Serial.println("Fallo IP estática");
  }

  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  WiFi.setSleep(false);

  Wire.setPins(PIN_SDA, PIN_SCL);
  Wire.begin();

  pcaOk = pcaResponde();
  iniciarPCA();

  if (MOVER_SERVOS_AL_ARRANQUE) {
    posicionesIniciales();
  }

  ws.onEvent(onEvent);
  server.addHandler(&ws);
  server.begin();
}

void loop() {
  ws.cleanupClients();
  vigilarPCA();
  actualizarServos(false);
}