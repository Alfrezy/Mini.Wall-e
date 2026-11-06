
from pathlib import Path
from urllib.request import urlretrieve

# ============================================================
# CONFIGURACIÓN
# ============================================================

ROOT = Path(__file__).resolve().parent

MODELOS = {
    "face_detection_yunet_2023mar.onnx":
        "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx",

    "face_recognition_sface_2021dec.onnx":
        "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx"
}

# ============================================================
# DESCARGA
# ============================================================

def descargar_modelos():

    ROOT.mkdir(parents=True, exist_ok=True)

    for nombre, url in MODELOS.items():

        destino = ROOT / nombre

        if destino.exists():
            print(f"[EXISTE] {nombre}")
            continue

        print(f"\n[DESCARGANDO] {nombre}")

        try:
            urlretrieve(url, destino)
            print(f"[OK] Guardado en: {destino}")

        except Exception as error:
            print(f"[ERROR] No se pudo descargar {nombre}")
            print(error)


# ============================================================
# PUNTO DE ENTRADA
# ============================================================

if __name__ == "__main__":
    descargar_modelos()
