# Mini Wall-E (Java + ONNX Runtime)

1. Copia tu modelo a `model/model.onnx`
2. `docker compose up --build`
3. Abre http://localhost:8080

API: `POST /api/predict` (multipart, campo `file`).
Ajustes por variables de entorno en `docker-compose.yml` (CLASSES, IMG_SIZE, UMBRAL).
