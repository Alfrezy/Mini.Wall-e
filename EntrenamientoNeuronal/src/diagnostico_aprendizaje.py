
from pathlib import Path
import numpy as np
import tensorflow as tf
from tensorflow import keras
from sklearn.metrics import confusion_matrix, classification_report

BASE_DIR = Path(__file__).resolve().parent.parent
TRAIN_DIR = BASE_DIR / "dataset" / "entrenamiento"
MODEL_PATH = BASE_DIR / "modelos" / "diagnostico_aprendizaje.keras"

CLASES = ["Caja", "bota", "boteAluminio", "papelHigienico"]

dataset = keras.utils.image_dataset_from_directory(
    str(TRAIN_DIR),
    labels="inferred",
    label_mode="int",
    class_names=CLASES,
    color_mode="grayscale",
    image_size=(96, 96),
    batch_size=8,
    shuffle=False,
)

modelo = keras.models.load_model(MODEL_PATH)

etiquetas_reales = np.concatenate([
    etiquetas.numpy()
    for _, etiquetas in dataset
])

probabilidades = modelo.predict(dataset, verbose=0)
predicciones = np.argmax(probabilidades, axis=1)

print("\n=== MATRIZ DE CONFUSIÓN ===")
print("Filas = clase real; columnas = clase predicha")
print(confusion_matrix(
    etiquetas_reales,
    predicciones,
    labels=range(len(CLASES))
))

print("\n=== RESULTADOS POR CLASE ===")
print(classification_report(
    etiquetas_reales,
    predicciones,
    labels=range(len(CLASES)),
    target_names=CLASES,
    zero_division=0,
))

print("\n=== DISTRIBUCIÓN DE PREDICCIONES ===")
for i, clase in enumerate(CLASES):
    cantidad = int(np.sum(predicciones == i))
    print(f"{clase}: {cantidad} de {len(predicciones)} imágenes")
