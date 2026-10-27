
from pathlib import Path
import shutil
import sys

from ultralytics import YOLO

# ============================================================
# CONFIGURACIÓN DEL PROYECTO
# ============================================================

ROOT = Path(__file__).resolve().parents[1]
DATASET_YAML = ROOT / "dataset" / "deteccion" / "data.yaml"
MODELOS_DIR = ROOT / "modelos"
RUNS_DIR = ROOT / "runs" / "detect"

CLASSES = [
    "Caja",
    "bota",
    "boteAluminio",
    "papelHigienico",
]

IMAGE_EXTENSIONS = {
    ".jpg", ".jpeg", ".png", ".bmp", ".webp"
}

# Parámetros de entrenamiento
EPOCHS = 100
IMAGE_SIZE = 640
BATCH_SIZE = 8
DEVICE = "cpu"


# ============================================================
# VALIDACIÓN DEL DATASET
# ============================================================

def validar_dataset():
    print("\n" + "=" * 60)
    print(" VALIDANDO DATASET")
    print("=" * 60)

    if not DATASET_YAML.exists():
        print(f"[ERROR] No existe: {DATASET_YAML}")
        sys.exit(1)

    errores = []
    total_imagenes = 0
    total_cajas = 0

    for split in ("train", "val"):
        images_dir = ROOT / "dataset" / "deteccion" / "images" / split
        labels_dir = ROOT / "dataset" / "deteccion" / "labels" / split

        if not images_dir.exists():
            errores.append(f"No existe la carpeta: {images_dir}")
            continue

        if not labels_dir.exists():
            errores.append(f"No existe la carpeta: {labels_dir}")
            continue

        imagenes = sorted(
            p for p in images_dir.iterdir()
            if p.is_file() and p.suffix.lower() in IMAGE_EXTENSIONS
        )

        if not imagenes:
            errores.append(f"No hay imágenes en {images_dir}")
            continue

        print(f"\nConjunto: {split.upper()}")
        print(f"Imágenes encontradas: {len(imagenes)}")

        for imagen in imagenes:
            total_imagenes += 1
            etiqueta = labels_dir / f"{imagen.stem}.txt"

            if not etiqueta.exists():
                errores.append(f"Falta etiqueta: {etiqueta.name}")
                continue

            lineas = [
                linea.strip()
                for linea in etiqueta.read_text(
                    encoding="utf-8"
                ).splitlines()
                if linea.strip()
            ]

            if not lineas:
                errores.append(
                    f"Etiqueta vacía: {split}/{etiqueta.name}"
                )
                continue

            for numero_linea, linea in enumerate(lineas, start=1):
                try:
                    valores = linea.split()

                    if len(valores) != 5:
                        raise ValueError(
                            "Se esperan 5 valores: clase xc yc ancho alto"
                        )

                    clase_texto = valores[0]
                    clase = int(clase_texto)

                    if clase not in range(len(CLASSES)):
                        raise ValueError(
                            f"Clase {clase} fuera del rango "
                            f"0-{len(CLASSES) - 1}"
                        )

                    xc, yc, ancho, alto = map(float, valores[1:])

                    coordenadas = (xc, yc, ancho, alto)

                    if not all(
                        0.0 <= valor <= 1.0
                        for valor in coordenadas
                    ):
                        raise ValueError(
                            "Las coordenadas deben estar entre 0 y 1"
                        )

                    if ancho <= 0 or alto <= 0:
                        raise ValueError(
                            "El ancho y el alto deben ser mayores que cero"
                        )

                    if (
                        xc - ancho / 2 < -1e-6
                        or yc - alto / 2 < -1e-6
                        or xc + ancho / 2 > 1 + 1e-6
                        or yc + alto / 2 > 1 + 1e-6
                    ):
                        raise ValueError(
                            "La caja se sale de los límites de la imagen"
                        )

                    total_cajas += 1

                except (ValueError, OverflowError) as exc:
                    errores.append(
                        f"{etiqueta.name}, línea {numero_linea}: "
                        f"{exc} | Contenido: {linea}"
                    )

    print(f"\nTotal de imágenes revisadas: {total_imagenes}")
    print(f"Total de cajas válidas: {total_cajas}")

    if errores:
        print("\n" + "=" * 60)
        print(f"VALIDACIÓN FALLIDA: {len(errores)} problema(s)")
        print("=" * 60)

        for error in errores[:60]:
            print(f"[ERROR] {error}")

        if len(errores) > 60:
            print(f"... y {len(errores) - 60} errores adicionales.")

        print(
            "\nCorrige las etiquetas con el anotador y vuelve "
            "a ejecutar el entrenamiento."
        )
        sys.exit(1)

    if total_cajas == 0:
        print("[ERROR] No hay cajas anotadas.")
        sys.exit(1)

    print("\n[OK] Todas las etiquetas son válidas.")


# ============================================================
# ENTRENAMIENTO
# ============================================================

def entrenar():
    MODELOS_DIR.mkdir(parents=True, exist_ok=True)
    RUNS_DIR.mkdir(parents=True, exist_ok=True)

    print("\n" + "=" * 60)
    print(" ENTRENAMIENTO DE MINI.WALL-E")
    print("=" * 60)
    print(f"Dataset:       {DATASET_YAML}")
    print(f"Épocas:        {EPOCHS}")
    print(f"Tamaño imagen: {IMAGE_SIZE}")
    print(f"Batch:         {BATCH_SIZE}")
    print(f"Dispositivo:   {DEVICE}")
    print(f"Clases:        {', '.join(CLASSES)}")

    # Carga los pesos preentrenados de YOLOv8 Nano.
    modelo = YOLO("yolov8n.pt")

    resultados = modelo.train(
        data=str(DATASET_YAML),
        epochs=EPOCHS,
        imgsz=IMAGE_SIZE,
        batch=BATCH_SIZE,
        device=DEVICE,
        workers=2,

        # Directorio de resultados
        project=str(RUNS_DIR),
        name="mini_walle",
        exist_ok=True,

        # Control del entrenamiento
        patience=20,
        seed=42,
        pretrained=True,
        plots=True,
        save=True,
        save_period=10,
        cache=False,
        amp=False,

        # Aumento de datos: las cajas se transforman
        # junto con las imágenes.
        hsv_h=0.015,
        hsv_s=0.5,
        hsv_v=0.35,
        degrees=8.0,
        translate=0.10,
        scale=0.40,
        fliplr=0.5,
        flipud=0.0,
        mosaic=0.8,
        mixup=0.05,
        close_mosaic=10,
    )

    carpeta_pesos = (
        RUNS_DIR / "mini_walle" / "weights"
    )

    mejor_modelo = carpeta_pesos / "best.pt"
    ultimo_modelo = carpeta_pesos / "last.pt"

    if mejor_modelo.exists():
        destino = MODELOS_DIR / "mini_walle_best.pt"
        shutil.copy2(mejor_modelo, destino)
        print(f"\n[OK] Mejor modelo guardado en:\n{destino}")
    else:
        print(
            "\n[AVISO] No se encontró best.pt. "
            "Revisa los resultados del entrenamiento."
        )

    if ultimo_modelo.exists():
        print(f"[INFO] Último checkpoint: {ultimo_modelo}")

    print("\nEntrenamiento finalizado.")
    print(f"Resultados: {RUNS_DIR / 'mini_walle'}")


# ============================================================
# PUNTO DE ENTRADA
# ============================================================

if __name__ == "__main__":
    validar_dataset()
    entrenar()
