# Mini.Wall-e

> **Sistema Robótico Autónomo de Patrullaje, Recolección de Residuos y Reforestación Urbana**

EcoWallE es una plataforma robótica autónoma inspirada en Wall-E que combina tecnología embebida, procesamiento en el borde, redes neuronales personalizadas y arquitecturas distribuidas. El robot patrulla un entorno delimitado, detecta e identifica basura para su recolección, saluda a transeúntes y realiza tareas de siembra de plantas tras alcanzar metas de limpieza.

---

## 🏗️ Arquitectura del Sistema

El sistema utiliza un enfoque distribuido multinivel (*Multi-Language Ecosystem*) para maximizar el rendimiento del hardware y la flexibilidad en el procesamiento de Inteligencia Artificial.

## 🎮 Panel de Control Web y Comandos

La interfaz gráfica cuenta con un panel de control interactivo optimizado tanto para clics con el ratón como para control directo mediante el teclado de tu computadora.

### Mapeo de Teclado y Controles:
* **Movimiento (Orugas):** Teclas **`W`**, **`A`**, **`S`**, **`D`**. *(Nota: El robot se detiene automáticamente en cuanto dejas de presionar la tecla de dirección).*
* **Cámara (Servos):** Teclas **`Q`** (Arriba) / **`E`** (Abajo).
* **Brazos:** Teclas **`Z`** (Brazo Izquierdo) / **`X`** (Brazo Derecho).
* **Dataset / Captura:** Tecla **`F`** para capturar fotogramas destinados al entrenamiento de la red neuronal.

---

## 🚀 Tecnologías Utilizadas

* **Backend:** Java (HTTP Server nativo / Spring Boot base).
* **Base de Datos:** PostgreSQL 15+.
* **Contenedores:** Docker & Docker Compose.
* **Frontend:** HTML5, CSS3, JavaScript (Nginx).
* **Hardware de Video:** ESP32-CAM.