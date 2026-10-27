"""
Mini.Wall-e - Evaluación de un modelo guardado.

IMPORTANTE: la función preparar_imagen() debe ser IDÉNTICA a la de
entrenar.py (EXIF + escala de grises + padding a 96x96 con relleno 128).
Si el preprocesamiento difiere, las métricas no son comparables.

Uso:
  python3 src/evaluar.py --modelo modelos/aug0_bn0.9_ultima_epoca.keras
  python3 src/evaluar.py --modelo modelos/X.keras --particion entrenamiento
"""

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps
from sklearn.metrics import classification_report, confusion_matrix
from tensorflow import keras

BASE_DIR = Path(__file__).resolve().parent.parent

CLASES = ["Caja", "bota", "boteAluminio", "papelHigienico"]
EXTENSIONES = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
IMG_SIZE = (96, 96)  # (alto, ancho)

PARTICIONES = {
    "validacion": BASE_DIR / "dataset" / "validacion",
    "entrenamiento": BASE_DIR / "dataset" / "entrenamiento",
}

parser = argparse.ArgumentParser(description="Evalúa un modelo de Mini.Wall-e")
parser.add_argument(
    "--modelo",
    default=str(BASE_DIR / "modelos" / "aug0_bn0.9_s2_mejor_val_loss.keras"),
    help="Ruta del archivo .keras",
)
parser.add_argument("--particion", choices=list(PARTICIONES), default="validacion")
args = parser.parse_args()

MODELO_PATH = Path(args.modelo)
if not MODELO_PATH.is_absolute():
    MODELO_PATH = Path.cwd() / MODELO_PATH
DATA_DIR = PARTICIONES[args.particion]

if not MODELO_PATH.exists():
    raise FileNotFoundError(f"No se encontró el modelo: {MODELO_PATH}")
if not DATA_DIR.exists():
    raise FileNotFoundError(f"No se encontró el dataset: {DATA_DIR}")

ruta_clases = BASE_DIR / "modelos" / "clases.json"
if ruta_clases.exists():
    with open(ruta_clases, encoding="utf-8") as f:
        clases_guardadas = json.load(f).get("clases")
    if clases_guardadas != CLASES:
        raise ValueError(
            f"Las clases de clases.json {clases_guardadas} no coinciden "
            f"con las de este script {CLASES}"
        )


def preparar_imagen(ruta):
    with Image.open(ruta) as img:
        img = ImageOps.exif_transpose(img)
        img = img.convert("L")
        img = ImageOps.pad(
            img, (IMG_SIZE[1], IMG_SIZE[0]),
            method=Image.LANCZOS, color=128,
        )
        return np.asarray(img, dtype=np.float32)[..., None]


def cargar_particion(carpeta):
    X, y, rutas = [], [], []
    for idx, clase in enumerate(CLASES):
        dir_clase = carpeta / clase
        if not dir_clase.is_dir():
            raise FileNotFoundError(f"Falta la carpeta: {dir_clase}")
        for ruta in sorted(dir_clase.iterdir()):
            if ruta.suffix.lower() not in EXTENSIONES:
                continue
            X.append(preparar_imagen(ruta))
            y.append(idx)
            rutas.append(ruta)
    if not X:
        raise RuntimeError(f"No hay imágenes en {carpeta}")
    return np.stack(X), np.array(y, dtype=np.int32), rutas


modelo = keras.models.load_model(MODELO_PATH)

print("=" * 60)
print("EVALUACIÓN DE MINI.WALL-E")
print("=" * 60)
print("Modelo   :", MODELO_PATH)
print("Partición:", args.particion)
print("Entrada esperada:", modelo.input_shape)

if modelo.input_shape[-1] != 1:
    raise ValueError(f"El modelo no espera un canal. Entrada: {modelo.input_shape}")

X, y, rutas = cargar_particion(DATA_DIR)
print(f"Imágenes cargadas: {len(y)}")

probs = modelo.predict(X, batch_size=16, verbose=0)
pred = np.argmax(probs, axis=1)
conf = probs[np.arange(len(pred)), pred]

print("\n--- PREDICCIONES ---\n")
for r, real, p, c in zip(rutas, y, pred, conf):
    estado = "CORRECTO" if real == p else "INCORRECTO"
    print(
        f"Real: {CLASES[real]:15s} | Pred: {CLASES[p]:15s} | "
        f"Conf: {c:.3f} | {estado:10s} | {r.name}"
    )

matriz = confusion_matrix(y, pred, labels=np.arange(len(CLASES)))
aciertos = int(np.trace(matriz))

print("\n--- MATRIZ DE CONFUSIÓN (filas = real, columnas = predicho) ---")
print("Orden:", CLASES)
print(matriz)

print("\n--- RESULTADOS ---")
print(f"Aciertos: {aciertos}/{len(y)}")
print(f"Accuracy: {aciertos / len(y) * 100:.2f}%")
print("Predicciones por clase:",
      {c: int(np.sum(pred == i)) for i, c in enumerate(CLASES)})

print("\nAccuracy por clase:")
for i, clase in enumerate(CLASES):
    n = int(matriz[i].sum())
    ok = int(matriz[i, i])
    print(f"  {clase:15s}: {ok}/{n} ({ok / n * 100 if n else 0:.1f}%)")

ok_mask = pred == y
print(f"\nConfianza media (aciertos) : "
      f"{conf[ok_mask].mean() if ok_mask.any() else float('nan'):.3f}")
print(f"Confianza media (errores)  : "
      f"{conf[~ok_mask].mean() if (~ok_mask).any() else float('nan'):.3f}")

print()
print(classification_report(
    y, pred, labels=np.arange(len(CLASES)),
    target_names=CLASES, zero_division=0,
))
