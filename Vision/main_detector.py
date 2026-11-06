"""
main_detector.py  ·  Servicio de visión (Python + OpenCV)

Por ahora hace una cosa: detectar marcadores ArUco (diccionario 4x4_50, los mismos
que crea generar_arucos.py) en la foto que le manda el backend de Java.

  POST /aruco   (cuerpo = JPEG)  ->  {"marcadores": [{"id", "x", "y", "w", "h"}, ...], "ancho", "alto"}
  GET  /salud                    ->  {"estado": "ok"}

x, y, w, h son el "bounding box" del marcador, normalizados de 0 a 1 (x, y = esquina
superior izquierda). El backend usa el centro para alinearse y el alto para saber
qué tan cerca está.
"""
import cv2
import numpy as np
from flask import Flask, jsonify, request

app = Flask(__name__)

DICCIONARIO = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)

# OpenCV >= 4.7 usa ArucoDetector; las versiones anteriores usan detectMarkers directo.
if hasattr(cv2.aruco, "ArucoDetector"):
    _detector = cv2.aruco.ArucoDetector(DICCIONARIO, cv2.aruco.DetectorParameters())

    def _detectar(gris):
        esquinas, ids, _ = _detector.detectMarkers(gris)
        return esquinas, ids
else:
    _parametros = cv2.aruco.DetectorParameters_create()

    def _detectar(gris):
        esquinas, ids, _ = cv2.aruco.detectMarkers(gris, DICCIONARIO, parameters=_parametros)
        return esquinas, ids


@app.get("/salud")
def salud():
    return jsonify(estado="ok", opencv=cv2.__version__)


@app.post("/aruco")
def aruco():
    datos = request.get_data()
    imagen = cv2.imdecode(np.frombuffer(datos, np.uint8), cv2.IMREAD_GRAYSCALE)
    if imagen is None:
        return jsonify(error="La imagen recibida no es válida"), 400

    alto, ancho = imagen.shape[:2]
    esquinas, ids = _detectar(imagen)

    marcadores = []
    if ids is not None:
        for puntos, marcador_id in zip(esquinas, ids.flatten()):
            p = puntos.reshape(4, 2)
            x1, y1 = p.min(axis=0)
            x2, y2 = p.max(axis=0)
            marcadores.append({
                "id": int(marcador_id),
                "x": float(x1 / ancho),
                "y": float(y1 / alto),
                "w": float((x2 - x1) / ancho),
                "h": float((y2 - y1) / alto),
            })

    return jsonify(marcadores=marcadores, ancho=ancho, alto=alto)


if __name__ == "__main__":
    print("👁️ [VISION] Servicio de ArUco listo en el puerto 5000.", flush=True)
    app.run(host="0.0.0.0", port=5000, threaded=True)
