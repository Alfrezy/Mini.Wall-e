
from pathlib import Path
import cv2
import numpy as np
import sys

# ============================================================
# CONFIGURACIÓN DEL PROYECTO
# ============================================================

ROOT = Path(__file__).resolve().parents[1]

MODELOS_DIR = ROOT / "modelos"

DETECTOR_PATH = MODELOS_DIR / "face_detection_yunet_2023mar.onnx"
RECONOCEDOR_PATH = MODELOS_DIR / "face_recognition_sface_2021dec.onnx"
EMBEDDINGS_PATH = MODELOS_DIR / "embeddings.npz"

# Umbral inicial de similitud coseno.
# Se deberá calibrar con las fotografías reales.
UMBRAL = 0.40

# ============================================================
# CARGAR MODELOS Y EMBEDDINGS
# ============================================================

def cargar_modelos():

    if not DETECTOR_PATH.exists() or not RECONOCEDOR_PATH.exists():
        print("[ERROR] Faltan los modelos ONNX.")
        sys.exit(1)

    if not EMBEDDINGS_PATH.exists():
        print("[ERROR] No existe embeddings.npz.")
        print("Ejecuta primero generar_embeddings.py")
        sys.exit(1)

    detector = cv2.FaceDetectorYN_create(
        str(DETECTOR_PATH),
        "",
        (320, 320),
        0.8,
        0.3,
        5000
    )

    reconocedor = cv2.FaceRecognizerSF_create(
        str(RECONOCEDOR_PATH),
        ""
    )

    datos = np.load(EMBEDDINGS_PATH)

    embeddings = datos["embeddings"]
    etiquetas = datos["etiquetas"]

    return detector, reconocedor, embeddings, etiquetas


# ============================================================
# COMPARAR IDENTIDADES
# ============================================================

def reconocer_rostro(embedding, embeddings, etiquetas):

    embedding = embedding.flatten()

    similitudes = np.dot(
        embeddings,
        embedding
    ) / (
        np.linalg.norm(embeddings, axis=1)
        * np.linalg.norm(embedding)
        + 1e-10
    )

    mejor_indice = np.argmax(similitudes)

    mejor_similitud = float(similitudes[mejor_indice])

    if mejor_similitud >= UMBRAL:
        return etiquetas[mejor_indice], mejor_similitud

    return "Cara no reconocida", mejor_similitud


# ============================================================
# RECONOCIMIENTO EN TIEMPO REAL
# ============================================================

def reconocimiento():

    detector, reconocedor, embeddings, etiquetas = cargar_modelos()

    cap = cv2.VideoCapture(0)

    if not cap.isOpened():
        print("[ERROR] No se pudo abrir la cámara.")
        return

    while True:

        ret, frame = cap.read()

        if not ret:
            break

        detector.setInputSize(
            (frame.shape[1], frame.shape[0])
        )

        _, rostros = detector.detect(frame)

        if rostros is not None:

            for rostro in rostros:

                x, y, w, h = map(int, rostro[:4])

                rostro_alineado = reconocedor.alignCrop(
                    frame,
                    rostro
                )

                embedding = reconocedor.feature(
                    rostro_alineado
                )

                nombre, similitud = reconocer_rostro(
                    embedding,
                    embeddings,
                    etiquetas
                )

                color = (
                    (0, 255, 0)
                    if nombre != "Cara no reconocida"
                    else (0, 0, 255)
                )

                cv2.rectangle(
                    frame,
                    (x, y),
                    (x + w, y + h),
                    color,
                    2
                )

                cv2.putText(
                    frame,
                    f"{nombre} ({similitud:.2f})",
                    (x, max(20, y - 10)),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.6,
                    color,
                    2
                )

        cv2.imshow("Reconocimiento Facial", frame)

        if cv2.waitKey(1) & 0xFF == 27:
            break

    cap.release()
    cv2.destroyAllWindows()


# ============================================================
# PUNTO DE ENTRADA
# ============================================================

if __name__ == "__main__":
    reconocimiento()
