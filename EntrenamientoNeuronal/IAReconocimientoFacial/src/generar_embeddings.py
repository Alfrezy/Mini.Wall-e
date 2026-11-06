
from pathlib import Path
import cv2
import numpy as np
import json
import sys

# ============================================================
# CONFIGURACIÓN DEL PROYECTO
# ============================================================

ROOT = Path(__file__).resolve().parents[1]

DATA_DIR = ROOT / "Data"
MODELOS_DIR = ROOT / "modelos"

DETECTOR_PATH = MODELOS_DIR / "face_detection_yunet_2023mar.onnx"
RECONOCEDOR_PATH = MODELOS_DIR / "face_recognition_sface_2021dec.onnx"

EMBEDDINGS_PATH = MODELOS_DIR / "embeddings.npz"
CLASES_PATH = MODELOS_DIR / "clases.json"

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png"}

# ============================================================
# CARGAR MODELOS
# ============================================================

def cargar_modelos():

    if not DETECTOR_PATH.exists() or not RECONOCEDOR_PATH.exists():
        print("[ERROR] Faltan los modelos ONNX.")
        print("Ejecuta primero modelos/descargar_modelos.py")
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

    return detector, reconocedor


# ============================================================
# GENERAR EMBEDDINGS
# ============================================================

def generar_embeddings():

    detector, reconocedor = cargar_modelos()

    embeddings = []
    etiquetas = []

    personas = sorted([
        carpeta for carpeta in DATA_DIR.iterdir()
        if carpeta.is_dir()
    ])

    for persona in personas:

        print(f"\nProcesando: {persona.name}")

        imagenes = [
            archivo for archivo in persona.iterdir()
            if archivo.suffix.lower() in IMAGE_EXTENSIONS
        ]

        for imagen_path in imagenes:

            imagen = cv2.imread(str(imagen_path))

            if imagen is None:
                print(f"[AVISO] No se pudo leer: {imagen_path.name}")
                continue

            detector.setInputSize(
                (imagen.shape[1], imagen.shape[0])
            )

            _, rostros = detector.detect(imagen)

            if rostros is None or len(rostros) == 0:
                print(f"[AVISO] No se detectó rostro: {imagen_path.name}")
                continue

            # Seleccionar el rostro más grande.
            rostro = max(
                rostros,
                key=lambda r: r[2] * r[3]
            )

            # Alinear el rostro y extraer sus características.
            rostro_alineado = reconocedor.alignCrop(
                imagen,
                rostro
            )

            embedding = reconocedor.feature(rostro_alineado)

            embeddings.append(embedding.flatten())
            etiquetas.append(persona.name)

        print(f"[OK] {persona.name}: procesada")

    if not embeddings:
        print("[ERROR] No se generaron embeddings.")
        sys.exit(1)

    embeddings = np.array(embeddings, dtype=np.float32)
    etiquetas = np.array(etiquetas)

    np.savez_compressed(
        EMBEDDINGS_PATH,
        embeddings=embeddings,
        etiquetas=etiquetas
    )

    clases = sorted(set(etiquetas.tolist()))

    with open(CLASES_PATH, "w", encoding="utf-8") as archivo:
        json.dump(
            clases,
            archivo,
            ensure_ascii=False,
            indent=4
        )

    print("\n" + "=" * 60)
    print(" EMBEDDINGS GENERADOS")
    print("=" * 60)
    print(f"Vectores: {len(embeddings)}")
    print(f"Identidades: {clases}")
    print(f"Guardado: {EMBEDDINGS_PATH}")


# ============================================================
# PUNTO DE ENTRADA
# ============================================================

if __name__ == "__main__":
    generar_embeddings()
