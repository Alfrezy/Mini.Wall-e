<div align="center">

# 🤖 MINI WALL·E // CENTRAL SYSTEM UNIT
### *Transforma tu perfil interactiva de robótica y ciberseguridad*

<img src="https://img.shields.io/badge/STATUS-OPERATIONAL-00ff66?style=for-the-badge&logo=probot&logoColor=00ff66" />
<img src="https://img.shields.io/badge/STACK-JAVA%20%7C%20DOCKER%20%7C%20ESP32-blueviolet?style=for-the-badge" />
<img src="https://img.shields.io/badge/THEME-HACKER%20%2F%20CYBERPUNK-ff0055?style=for-the-badge" />

</div>

---

## 🖥️ `whoami` // Perfil del Sistema

```ansi
[0;36mconst[0m [0;33mwall_e_system[0m = {
    [0;32mname[0m: [0;31m"Mini Wall-E Bot"[0m,
    [0;32mrole[0m: [0;31m"Autonomous Patrol & Rescue Unit"[0m,
    [0;32mlocation[0m: [0;31m"Local Network / Docker Environment"[0m,
    [0;32mcurrently[0m: [0;31m"Building hardware integrations & web interface"[0m,
    [0;32mstacks[0m: [[0;31m"Java"[0m, [0;31m"Docker"[0m, [0;31m"C++ (Arduino)"[0m, [0;31m"JavaScript"[0m, [0;31m"PostgreSQL"[0m],
    [0;32mmantra[0m: [0;31m"Make autonomous systems useful, then make them delightful."[0m
};

```

---

## 🏗️ Arquitectura del Sistema Actual

El sistema utiliza contenedores Docker para aislar y desplegar de forma limpia cada servicio:

```text
                  ┌──────────────────────────────────────────┐
                  │          ESP32-CAM (Video IP)            │
                  │     • Transmisión de Stream MJPEG        │
                  └────────────────────┬─────────────────────┘
                                       │
                                Stream de Video
                                       │
                                       ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 CONTENEDORES DOCKER                                    │
│                                                                                        │
│   ┌──────────────────────────────────┐        ┌────────────────────────────────────┐   │
│   │    Dashboard Web (Nginx)         │        │     Backend Servidor (Java)        │   │
│   │    • HTML5 / CSS3 / JavaScript   │ ─────> │     • Endpoints REST de Control    │   │
│   │    • Interfaz estilo Gamepad     │  HTTP  │     • Conexión con Base de Datos   │   │
│   └──────────────────────────────────┘        └─────────────────┬──────────────────┘   │
└───────────────────────────────────────────────────────────────────┼────────────────────┘
                                                                    │
                                                                    ▼
                                                    ┌────────────────────────────────────┐
                                                    │     Base de Datos (PostgreSQL)     │
                                                    │   • Almacenamiento y Persistencia  │
                                                    └────────────────────────────────────┘

```

---

## 🎮 Panel de Control Web y Comandos

La interfaz gráfica del frontend está diseñada con un estilo retro-cyberpunk e incluye un mando de control interactivo con respuesta visual al hacer clic o usar el teclado.

* **Movimiento (Orugas):** Teclas **`W`**, **`A`**, **`S`**, **`D`**. *(Nota: El robot se detiene automáticamente al soltar las teclas).*
* **Cámara (Servos):** Teclas **`Q`** (Arriba) / **`E`** (Abajo).
* **Brazos:** Teclas **`Z`** (Brazo Izquierdo) / **`X`** (Brazo Derecho).
* **Dataset / Entrenamiento:** Tecla **`F`** o botón en pantalla para capturar fotos directamente hacia el dataset de entrenamiento.

---

## 🚀 Tecnologías Utilizadas

* **Backend:** Java (HTTP Server nativo / Spring Boot base).
* **Base de Datos:** PostgreSQL 15+.
* **Contenedores:** Docker & Docker Compose.
* **Frontend:** HTML5, CSS3, JavaScript (Nginx).
* **Hardware de Video:** ESP32-CAM.

---

## 🛠️ Guía Rápida de Despliegue

### 1. Clonar el Repositorio

```bash
git clone https://github.com/Alfrezy/Mini.Wall-e.git
cd Mini.Wall-eub.com/Alfrezy/Mini.Wall-e.git]

```

### 2. Levantar el Entorno con Docker Compose

```bash
docker compose up --build -d

```

---

## 🧠 Entrenamiento de la Red Neuronal Desde Cero

Para que la IA aprenda a reconocer los objetos del entorno de Wall-E:

1. **Captura:** Usa el botón de captura de dataset desde la interfaz web mientras manejas el robot.
2. **Etiquetado:** Usa herramientas como LabelImg o Roboflow para marcar las clases (basura, planta, persona).
3. **Entrenamiento:** Entrena el modelo utilizando YOLOv8 en Python y exporta los pesos en formato `.onnx` para integrarlos con el backend de Java.

---

## 📄 Licencia

Este proyecto está bajo la licencia MIT. Siéntete libre de usarlo, modificarlo y mejorarlo.