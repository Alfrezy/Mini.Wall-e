
from pathlib import Path

import numpy as np
import tensorflow as tf
from tensorflow import keras


# ============================================================
# 1. CONFIGURACION
# ============================================================

BASE_DIR = Path(__file__).resolve().parent.parent

VAL_DIR = BASE_DIR / "dataset" / "validacion"
MODELO_PATH = BASE_DIR / "modelos" / "mejor_modelo.keras"

CLASES = [
    "Caja",
    "bota",
    "boteAluminio",
    "papelHigienico",
]

IMG_SIZE = (96, 96)

if not MODELO_PATH.exists():
    raise FileNotFoundError(
        f"No se encontro el modelo: {MODELO_PATH}"
    )

if not VAL_DIR.exists():
    raise FileNotFoundError(
        f"No se encontro el dataset: {VAL_DIR}"
    )


# ============================================================
# 2. CARGAR MODELO
# ============================================================

modelo = keras.models.load_model(MODELO_PATH)

print("=" * 60)
print("EVALUACION DE MINI.WALL-E")
print("=" * 60)
print("Modelo:", MODELO_PATH)
print("Entrada esperada:", modelo.input_shape)

if modelo.input_shape[-1] != 1:
    raise ValueError(
        "El modelo no espera una imagen de un canal. "
        f"Entrada encontrada: {modelo.input_shape}"
    )


# ============================================================
# 3. CARGAR VALIDACION EN ESCALA DE GRISES
# ============================================================

val_ds = keras.utils.image_dataset_from_directory(
    str(VAL_DIR),
    labels="inferred",
    label_mode="int",
    class_names=CLASES,
    color_mode="grayscale",
    image_size=IMG_SIZE,
    batch_size=1,
    shuffle=False,
)

print("\nClases:", val_ds.class_names)


# ============================================================
# 4. EVALUAR Y MOSTRAR PREDICCIONES
# ============================================================

etiquetas_reales = []
etiquetas_predichas = []
confianzas = []

print("\n--- PREDICCIONES DE VALIDACION ---\n")

for imagenes, etiquetas in val_ds:
    probabilidades = modelo.predict(imagenes, verbose=0)[0]

    indice_real = int(etiquetas.numpy()[0])
    indice_predicho = int(np.argmax(probabilidades))
    confianza = float(probabilidades[indice_predicho])

    clase_real = CLASES[indice_real]
    clase_predicha = CLASES[indice_predicho]

    resultado = (
        "CORRECTO"
        if indice_real == indice_predicho
        else "INCORRECTO"
    )

    print(
        f"Real: {clase_real:18} | "
        f"Prediccion: {clase_predicha:18} | "
        f"Confianza: {confianza:.3f} | "
        f"{resultado}"
    )

    etiquetas_reales.append(indice_real)
    etiquetas_predichas.append(indice_predicho)
    confianzas.append(confianza)


# ============================================================
# 5. MATRIZ DE CONFUSION
# ============================================================

matriz = np.zeros(
    (len(CLASES), len(CLASES)),
    dtype=int,
)

for real, predicha in zip(
    etiquetas_reales,
    etiquetas_predichas,
):
    matriz[real, predicha] += 1

print("\n--- MATRIZ DE CONFUSION ---")
print("Filas = clase real; columnas = prediccion")
print("Orden:", CLASES)
print(matriz)


# ============================================================
# 6. METRICAS
# ============================================================

total = len(etiquetas_reales)
aciertos = int(np.trace(matriz))

accuracy = aciertos / total if total else 0.0

print("\n--- RESULTADOS ---")
print(f"Aciertos: {aciertos}/{total}")
print(f"Accuracy: {accuracy * 100:.2f}%")

print("\nAccuracy por clase:")

for indice, clase in enumerate(CLASES):
    cantidad = int(matriz[indice].sum())
    correctos = int(matriz[indice, indice])

    porcentaje = (
        correctos / cantidad * 100
        if cantidad
        else 0.0
    )

    print(
        f"{clase:18}: "
        f"{correctos}/{cantidad} ({porcentaje:.2f}%)"
    )

print(
    "\nConfianza media de las predicciones: "
    f"{np.mean(confianzas):.3f}"
)
