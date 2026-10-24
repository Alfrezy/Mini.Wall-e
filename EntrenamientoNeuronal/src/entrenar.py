"""
Mini.Wall-e - Entrenamiento de CNN en escala de grises (96x96x1)

Cambios respecto a la versión anterior (todos deliberados):
  1. Carga con PIL: corrige orientación EXIF y conserva la proporción
     (padding en lugar de deformar 3:4 -> 1:1).
  2. Un único criterio de selección: val_loss en checkpoint, EarlyStopping
     y ReduceLROnPlateau.
  3. class_weight DESACTIVADO por defecto (interruptor USAR_CLASS_WEIGHT).
  4. Batch size 16 (BatchNormalization más estable que con 8).
  5. Augmentation más suave + flip horizontal.
  6. Diagnóstico final: métricas en validación Y en entrenamiento (inferencia).
  7. Cada ejecución usa su propia carpeta de logs; el modelo anterior no se
     sobrescribe, se renombra.

La arquitectura es IDÉNTICA a la anterior.
"""

import hashlib
import json
import os
from datetime import datetime
from pathlib import Path

import numpy as np
import tensorflow as tf
from PIL import Image, ImageOps
from sklearn.metrics import classification_report, confusion_matrix
from sklearn.utils.class_weight import compute_class_weight
from tensorflow import keras
from tensorflow.keras import layers

# ============================================================
# 1. CONFIGURACIÓN
# ============================================================

BASE_DIR = Path(__file__).resolve().parent.parent

TRAIN_DIR = BASE_DIR / "dataset" / "entrenamiento"
VAL_DIR = BASE_DIR / "dataset" / "validacion"
MODEL_DIR = BASE_DIR / "modelos"
LOG_ROOT = BASE_DIR / "logs"

# Los nombres y el orden de las clases definen los índices de las etiquetas.
CLASES = ["Caja", "bota", "boteAluminio", "papelHigienico"]
EXTENSIONES = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}

IMG_SIZE = (96, 96)   # (alto, ancho)
BATCH_SIZE = 16
EPOCAS = 40
SEED = int(os.environ.get("SEED", "42"))   # SEED=3 python3 src/entrenar.py
LEARNING_RATE = 0.001
USAR_CLASS_WEIGHT = False
BN_MOMENTUM = 0.9    # 0.99 = valor por defecto de Keras; 0.9 mejoró train_inferencia (46% -> 63%)
USAR_AUGMENTATION = os.environ.get("USAR_AUG", "0") == "1"   # USAR_AUG=1 activa el perfil suave
NOMBRE_EXP = f"aug{int(USAR_AUGMENTATION)}_bn{BN_MOMENTUM}_s{SEED}"
PACIENCIA_ES = 40    # = EPOCAS: en este diagnóstico EarlyStopping no corta ni restaura

keras.utils.set_random_seed(SEED)

MARCA_TIEMPO = datetime.now().strftime("%Y%m%d_%H%M%S")
LOG_DIR = LOG_ROOT / f"{MARCA_TIEMPO}_{NOMBRE_EXP}"
MODEL_DIR.mkdir(parents=True, exist_ok=True)
LOG_DIR.mkdir(parents=True, exist_ok=True)

RUTA_MODELO = MODEL_DIR / f"{NOMBRE_EXP}_mejor_val_loss.keras"
RUTA_ULTIMA = MODEL_DIR / f"{NOMBRE_EXP}_ultima_epoca.keras"
RUTA_CLASES = MODEL_DIR / "clases.json"

print("=" * 60)
print("ENTRENAMIENTO DE MINI.WALL-E")
print("=" * 60)
print(f"TensorFlow: {tf.__version__} | Keras: {keras.__version__}")
print(f"Clases: {CLASES}")
print(f"Entrada: {IMG_SIZE[0]}x{IMG_SIZE[1]}x1 (escala de grises)")
print(f"Batch: {BATCH_SIZE} | Épocas máx.: {EPOCAS} | Semilla: {SEED}")
print(f"class_weight: {'ACTIVADO' if USAR_CLASS_WEIGHT else 'desactivado'}")
print(f"BatchNorm momentum: {BN_MOMENTUM}")
print(f"Data augmentation: {'ACTIVADA' if USAR_AUGMENTATION else 'DESACTIVADA'}")
print(f"Experimento: {NOMBRE_EXP}")
print(f"Logs de esta ejecución: {LOG_DIR}")


# ============================================================
# 2. CARGA DEL DATASET (PIL: EXIF + proporción conservada)
# ============================================================

def sha256(ruta):
    h = hashlib.sha256()
    with open(ruta, "rb") as f:
        for bloque in iter(lambda: f.read(1 << 20), b""):
            h.update(bloque)
    return h.hexdigest()


def cargar_particion(carpeta):
    """Devuelve X (N,96,96,1) float32 en [0,255], y (N,), rutas y hashes."""
    X, y, rutas = [], [], []
    for idx, clase in enumerate(CLASES):
        dir_clase = carpeta / clase
        if not dir_clase.is_dir():
            raise FileNotFoundError(f"Falta la carpeta: {dir_clase}")
        for ruta in sorted(dir_clase.iterdir()):
            if ruta.suffix.lower() not in EXTENSIONES:
                continue
            with Image.open(ruta) as img:
                img = ImageOps.exif_transpose(img)   # aplica rotación EXIF
                img = img.convert("L")               # escala de grises
                # Redimensiona SIN deformar y rellena con gris medio (128).
                # ImageOps.pad usa (ancho, alto).
                img = ImageOps.pad(
                    img, (IMG_SIZE[1], IMG_SIZE[0]),
                    method=Image.LANCZOS, color=128,
                )
                arr = np.asarray(img, dtype=np.float32)[..., None]
            X.append(arr)
            y.append(idx)
            rutas.append(ruta)
    if not X:
        raise RuntimeError(f"No se encontraron imágenes en {carpeta}")
    return np.stack(X), np.array(y, dtype=np.int32), rutas


print("\nCargando imágenes (esto puede tardar unos segundos)...")
X_train, y_train, rutas_train = cargar_particion(TRAIN_DIR)
X_val, y_val, rutas_val = cargar_particion(VAL_DIR)

print("\nConteo por clase:")
print(f"  {'Clase':16s} {'entren.':>8s} {'valid.':>8s}")
for i, clase in enumerate(CLASES):
    print(f"  {clase:16s} {int(np.sum(y_train == i)):8d} {int(np.sum(y_val == i)):8d}")
print(f"  {'TOTAL':16s} {len(y_train):8d} {len(y_val):8d}")

# Aviso de duplicados exactos entre particiones (no borra nada)
hashes_train = {sha256(r): r for r in rutas_train}
repetidas = [(hashes_train[h], r) for r in rutas_val
             if (h := sha256(r)) in hashes_train]
if repetidas:
    print(f"\n[AVISO] {len(repetidas)} imágenes de validación son idénticas "
          f"a otras de entrenamiento:")
    for a, b in repetidas[:10]:
        print(f"   {a.relative_to(BASE_DIR)}  ==  {b.relative_to(BASE_DIR)}")
else:
    print("\nNo hay duplicados exactos entre entrenamiento y validación.")

# Datasets de tf.data
train_ds = (
    tf.data.Dataset.from_tensor_slices((X_train, y_train))
    .shuffle(len(X_train), seed=SEED, reshuffle_each_iteration=True)
    .batch(BATCH_SIZE)
    .prefetch(tf.data.AUTOTUNE)
)
val_ds = (
    tf.data.Dataset.from_tensor_slices((X_val, y_val))
    .batch(BATCH_SIZE)
    .prefetch(tf.data.AUTOTUNE)
)


# ============================================================
# 3. CLASS WEIGHT (opcional)
# ============================================================

class_weight = None
if USAR_CLASS_WEIGHT:
    pesos = compute_class_weight(
        class_weight="balanced",
        classes=np.arange(len(CLASES)),
        y=y_train,
    )
    class_weight = {i: float(p) for i, p in enumerate(pesos)}
    print("\nPesos de clase:", class_weight)


# ============================================================
# 4. DATA AUGMENTATION (solo activa durante el entrenamiento)
# ============================================================
# RandomRotation(0.05) = ±5 % de vuelta completa = ±18°.
# (El 0.08 anterior equivalía a ±28.8°, bastante agresivo.)

data_augmentation = keras.Sequential(
    [
        layers.RandomFlip("horizontal"),
        layers.RandomTranslation(
            height_factor=0.05, width_factor=0.05,
            fill_mode="constant", fill_value=0.5,
        ),
        layers.RandomZoom(0.10, fill_mode="constant", fill_value=0.5),
        layers.RandomContrast(0.10),
    ],
    name="data_augmentation",
)


# ============================================================
# 5. MODELO CNN (arquitectura idéntica a la anterior)
# ============================================================

modelo = keras.Sequential(
    [
        keras.Input(shape=(IMG_SIZE[0], IMG_SIZE[1], 1)),
        layers.Rescaling(1.0 / 255.0),
        *([data_augmentation] if USAR_AUGMENTATION else []),

        layers.Conv2D(16, (3, 3), padding="same", activation="relu"),
        layers.BatchNormalization(momentum=BN_MOMENTUM),
        layers.MaxPooling2D((2, 2)),

        layers.Conv2D(32, (3, 3), padding="same", activation="relu"),
        layers.BatchNormalization(momentum=BN_MOMENTUM),
        layers.MaxPooling2D((2, 2)),

        layers.Conv2D(64, (3, 3), padding="same", activation="relu"),
        layers.BatchNormalization(momentum=BN_MOMENTUM),
        layers.MaxPooling2D((2, 2)),

        layers.Conv2D(128, (3, 3), padding="same", activation="relu"),
        layers.BatchNormalization(momentum=BN_MOMENTUM),

        layers.GlobalAveragePooling2D(),
        layers.Dense(64, activation="relu"),
        layers.Dropout(0.30),
        layers.Dense(len(CLASES), activation="softmax"),
    ],
    name="MiniWallE_CNN",
)

modelo.compile(
    optimizer=keras.optimizers.Adam(learning_rate=LEARNING_RATE),
    loss="sparse_categorical_crossentropy",
    metrics=["accuracy"],
)

print()
modelo.summary()


# ============================================================
# 6. CALLBACKS (criterio único: val_loss)
# ============================================================

# Si ya existe un modelo, se renombra (no se borra ni se sobrescribe).
if RUTA_MODELO.exists():
    respaldo = RUTA_MODELO.with_name(
        f"MiniWallE_grayscale_anterior_{MARCA_TIEMPO}.keras"
    )
    RUTA_MODELO.rename(respaldo)
    print(f"\nModelo previo conservado como: {respaldo.name}")

class DiagnosticoEpoca(keras.callbacks.Callback):
    """Tras cada época mide train y val en modo INFERENCIA (sin augmentation)
    y cuenta cuántas veces se predice cada clase."""

    def on_epoch_end(self, epoch, logs=None):
        partes = []
        for nombre, X, y in (("train", X_train, y_train), ("val", X_val, y_val)):
            probs = self.model.predict(X, batch_size=BATCH_SIZE, verbose=0)
            pred = np.argmax(probs, axis=1)
            acc = float(np.mean(pred == y))
            cuenta = np.bincount(pred, minlength=len(CLASES)).tolist()
            partes.append(f"{nombre}_inferencia={acc * 100:5.1f}% pred={cuenta}")
        print(f"\n  [DIAG época {epoch + 1}] " + " | ".join(partes))


callbacks = [
    DiagnosticoEpoca(),
    keras.callbacks.ModelCheckpoint(
        filepath=str(RUTA_MODELO),
        monitor="val_loss",
        mode="min",
        save_best_only=True,
        verbose=1,
    ),
    keras.callbacks.EarlyStopping(
        monitor="val_loss",
        mode="min",
        patience=PACIENCIA_ES,
        restore_best_weights=False,
        verbose=1,
    ),
    keras.callbacks.ReduceLROnPlateau(
        monitor="val_loss",
        mode="min",
        factor=0.5,
        patience=5,
        min_lr=1e-5,
        verbose=1,
    ),
    keras.callbacks.CSVLogger(str(LOG_DIR / "historial.csv")),
    keras.callbacks.TensorBoard(log_dir=str(LOG_DIR), histogram_freq=1),
]


# ============================================================
# 7. ENTRENAMIENTO
# ============================================================

print("\n" + "=" * 60)
print("INICIANDO ENTRENAMIENTO")
print("=" * 60)

historial = modelo.fit(
    train_ds,
    validation_data=val_ds,
    epochs=EPOCAS,
    class_weight=class_weight,
    callbacks=callbacks,
)

mejor_epoca = int(np.argmin(historial.history["val_loss"])) + 1
print(f"\nMejor época según val_loss: {mejor_epoca} "
      f"(val_loss={min(historial.history['val_loss']):.4f})")


# ============================================================
# 8. EVALUACIÓN
# ============================================================
# 'modelo' tiene los pesos restaurados por EarlyStopping; el archivo del
# checkpoint debería dar el mismo resultado (ambos usan val_loss).

def evaluar(nombre, modelo_eval, X, y):
    """Evalúa en modo inferencia (sin augmentation, BN con medias móviles)."""
    probs = modelo_eval.predict(X, batch_size=BATCH_SIZE, verbose=0)
    pred = np.argmax(probs, axis=1)
    loss = float(keras.losses.sparse_categorical_crossentropy(y, probs).numpy().mean())
    acc = float(np.mean(pred == y))

    print("\n" + "=" * 60)
    print(f"{nombre}")
    print("=" * 60)
    print(f"Loss: {loss:.4f} | Accuracy: {acc * 100:.2f}% ({int(np.sum(pred == y))}/{len(y)})")
    print("\nMatriz de confusión (filas = real, columnas = predicho):")
    print(confusion_matrix(y, pred, labels=np.arange(len(CLASES))))
    print("\nPredicciones por clase:",
          {c: int(np.sum(pred == i)) for i, c in enumerate(CLASES)})
    print()
    print(classification_report(
        y, pred, labels=np.arange(len(CLASES)),
        target_names=CLASES, zero_division=0,
    ))
    return loss, acc


modelo.save(RUTA_ULTIMA)
print(f"\nModelo de la ÚLTIMA época guardado en: {RUTA_ULTIMA.name}")

# 'modelo' = pesos de la última época (no se restauró nada).
_, acc_val_ult = evaluar("VALIDACIÓN (última época)", modelo, X_val, y_val)
_, acc_tr_ult = evaluar("ENTRENAMIENTO en modo inferencia (última época)", modelo, X_train, y_train)

# El checkpoint guarda la mejor val_loss (probablemente la época 1).
mejor_modelo = keras.models.load_model(RUTA_MODELO)
_, acc_val_mejor = evaluar("VALIDACIÓN (checkpoint de mejor val_loss)", mejor_modelo, X_val, y_val)
print(f"RESULTADO aug={int(USAR_AUGMENTATION)} seed={SEED} "
      f"val_ultima={acc_val_ult:.4f} train_inf_ultima={acc_tr_ult:.4f} "
      f"val_mejor={acc_val_mejor:.4f}")


# ============================================================
# 9. GUARDAR METADATOS
# ============================================================

with open(RUTA_CLASES, "w", encoding="utf-8") as f:
    json.dump(
        {
            "clases": CLASES,
            "img_size": list(IMG_SIZE),
            "canales": 1,
            "preprocesado": "EXIF + gris + pad a 96x96 (relleno 128); "
                            "el modelo incluye Rescaling(1/255)",
        },
        f, ensure_ascii=False, indent=2,
    )

print("\n" + "=" * 60)
print("ENTRENAMIENTO FINALIZADO")
print("=" * 60)
print(f"Modelo:    {RUTA_MODELO}")
print(f"Clases:    {RUTA_CLASES}")
print(f"Logs:      {LOG_DIR}")
print(f"TensorBoard: tensorboard --logdir {LOG_ROOT}")