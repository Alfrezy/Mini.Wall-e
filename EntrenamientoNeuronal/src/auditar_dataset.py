"""
Mini.Wall-e - Auditoría del dataset (no modifica ni borra imágenes).
Uso:  python3 src/auditar_dataset.py
"""

import hashlib
from collections import Counter
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageOps

BASE_DIR = Path(__file__).resolve().parent.parent
SALIDA = BASE_DIR / "resultados"

PARTICIONES = {
    "entrenamiento": BASE_DIR / "dataset" / "entrenamiento",
    "validacion": BASE_DIR / "dataset" / "validacion",
}
CLASES = ["Caja", "bota", "boteAluminio", "papelHigienico"]
EXT = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}

IMG = 96
UMBRAL_CERCANA = 10
N_PARES_PNG = 12


def sha256(ruta):
    h = hashlib.sha256()
    with open(ruta, "rb") as f:
        for bloque in iter(lambda: f.read(1 << 20), b""):
            h.update(bloque)
    return h.hexdigest()


def dhash(gris):
    a = np.asarray(gris.resize((9, 8), Image.LANCZOS), dtype=np.int16)
    return (a[:, 1:] > a[:, :-1]).flatten()


def miniatura_normalizada(gris):
    v = np.asarray(gris.resize((32, 32), Image.LANCZOS), dtype=np.float32).flatten()
    v = v - v.mean()
    n = np.linalg.norm(v)
    return v / n if n > 0 else v


def vista_de_la_red(gris):
    return ImageOps.pad(gris, (IMG, IMG), method=Image.LANCZOS, color=128)


registros, problemas, extras = [], [], []

print("=" * 60)
print("AUDITORÍA DEL DATASET")
print("=" * 60)

for particion, carpeta in PARTICIONES.items():
    if not carpeta.exists():
        problemas.append(f"No existe la carpeta: {carpeta}")
        continue
    for sub in carpeta.iterdir():
        if sub.is_dir() and sub.name not in CLASES:
            extras.append(f"Carpeta inesperada: {sub}")
    for clase in CLASES:
        dir_clase = carpeta / clase
        if not dir_clase.exists():
            problemas.append(f"Falta la carpeta: {dir_clase}")
            continue
        for ruta in sorted(dir_clase.iterdir()):
            if ruta.is_dir():
                extras.append(f"Subcarpeta dentro de clase: {ruta}")
                continue
            if ruta.suffix.lower() not in EXT:
                extras.append(f"Archivo ignorado (extensión): {ruta}")
                continue
            try:
                with Image.open(ruta) as img:
                    img.load()
                    w, h = img.size
                    exif = img.getexif().get(274, 1)
                    gris = ImageOps.exif_transpose(img).convert("L")
                    registros.append({
                        "particion": particion,
                        "clase": clase,
                        "ruta": ruta,
                        "sha": sha256(ruta),
                        "dhash": dhash(gris),
                        "thumb": miniatura_normalizada(gris),
                        "vista": vista_de_la_red(gris),
                        "w": w, "h": h, "exif": exif,
                        "brillo": float(np.asarray(vista_de_la_red(gris)).mean()),
                        "kb": ruta.stat().st_size / 1024,
                    })
            except Exception as e:
                problemas.append(f"No se pudo abrir {ruta}: {e}")

if not registros:
    raise SystemExit("No se encontraron imágenes.")

print("\n1) CONTEO POR CLASE Y PARTICIÓN")
cuenta = Counter((r["particion"], r["clase"]) for r in registros)
for particion in PARTICIONES:
    total = 0
    print(f"  {particion}:")
    for clase in CLASES:
        n = cuenta[(particion, clase)]
        total += n
        print(f"    {clase:16s} {n}")
    print(f"    {'TOTAL':16s} {total}")

print("\n2) IMÁGENES NO VÁLIDAS / ARCHIVOS EXTRAÑOS")
if problemas or extras:
    for p in problemas + extras:
        print("  -", p)
else:
    print("  Ninguno.")

print("\n3) DUPLICADOS EXACTOS")
por_sha = {}
for r in registros:
    por_sha.setdefault(r["sha"], []).append(r)
hay = False
for lista in por_sha.values():
    if len(lista) > 1:
        hay = True
        entre = len({x["particion"] for x in lista}) > 1
        print("  [ENTRE PARTICIONES]" if entre else "  [misma partición]")
        for x in lista:
            print("     ", x["ruta"].relative_to(BASE_DIR))
if not hay:
    print("  Ninguno.")

print("\n4) VECINO MÁS PARECIDO (dHash, distancia de Hamming sobre 64 bits)")

H = np.stack([r["dhash"] for r in registros])
T = np.stack([r["thumb"] for r in registros])
D = (H[:, None, :] != H[None, :, :]).sum(-1)
C = T @ T.T

idx_tr = [i for i, r in enumerate(registros) if r["particion"] == "entrenamiento"]
idx_va = [i for i, r in enumerate(registros) if r["particion"] == "validacion"]

BINS = [(0, 5), (6, 10), (11, 15), (16, 20), (21, 64)]


def histograma(distancias):
    out = []
    for a, b in BINS:
        n = int(np.sum((distancias >= a) & (distancias <= b)))
        out.append(f"{a}-{b}: {n}")
    return "  ".join(out)


pares_va = []
for i in idx_va:
    sub = D[i, idx_tr]
    j = idx_tr[int(np.argmin(sub))]
    pares_va.append((int(D[i, j]), i, j))
d_va = np.array([p[0] for p in pares_va])

d_tr = []
for i in idx_tr:
    otros = [j for j in idx_tr if j != i]
    d_tr.append(int(D[i, otros].min()))
d_tr = np.array(d_tr)

print(f"  Validación -> entrenamiento : {histograma(d_va)}")
print(f"  Entrenamiento -> entren.    : {histograma(d_tr)}")
print(f"  Mediana: validación {np.median(d_va):.0f} | entrenamiento {np.median(d_tr):.0f}")

muy_cercanas = [p for p in pares_va if p[0] <= UMBRAL_CERCANA]
print(f"\n  Imágenes de validación con un vecino de entrenamiento a distancia "
      f"<= {UMBRAL_CERCANA}: {len(muy_cercanas)} de {len(idx_va)}")

mismo = sum(registros[i]["clase"] == registros[j]["clase"] for _, i, j in pares_va)
print(f"  Vecino más parecido de la MISMA clase: {mismo} de {len(pares_va)}")

pares_va.sort(key=lambda t: t[0])
print("\n  Los pares más parecidos (validación | entrenamiento):")
for d, i, j in pares_va[:N_PARES_PNG]:
    marca = "" if registros[i]["clase"] == registros[j]["clase"] else "  <- CLASES DISTINTAS"
    print(f"    d={d:2d} r={C[i, j]:.2f}  {registros[i]['ruta'].relative_to(BASE_DIR)}")
    print(f"               {registros[j]['ruta'].relative_to(BASE_DIR)}{marca}")

print("\n5) ESTADÍSTICAS POR CLASE (posibles atajos)")
for clase in CLASES:
    rs = [r for r in registros if r["clase"] == clase]
    if not rs:
        continue
    dims = Counter((r["w"], r["h"]) for r in rs).most_common(2)
    exifs = Counter(r["exif"] for r in rs)
    print(f"  {clase}")
    print(f"    tamaños más comunes: {dims}")
    print(f"    orientación EXIF   : {dict(exifs)}")
    print(f"    brillo medio       : {np.mean([r['brillo'] for r in rs]):.1f} "
          f"(desv. {np.std([r['brillo'] for r in rs]):.1f})")
    print(f"    peso medio (KB)    : {np.mean([r['kb'] for r in rs]):.0f}")

SALIDA.mkdir(exist_ok=True)

esc = 144
col_w = esc * 2 + 24
fila_h = esc + 34
cols = 3
n = min(N_PARES_PNG, len(pares_va))
filas = (n + cols - 1) // cols
lienzo = Image.new("RGB", (cols * col_w, filas * fila_h), "white")
dib = ImageDraw.Draw(lienzo)
for k, (d, i, j) in enumerate(pares_va[:n]):
    x0 = (k % cols) * col_w
    y0 = (k // cols) * fila_h
    a = registros[i]["vista"].resize((esc, esc)).convert("RGB")
    b = registros[j]["vista"].resize((esc, esc)).convert("RGB")
    lienzo.paste(a, (x0 + 4, y0 + 18))
    lienzo.paste(b, (x0 + 4 + esc + 8, y0 + 18))
    dib.text((x0 + 4, y0 + 3),
             f"d={d} r={C[i, j]:.2f}  VAL:{registros[i]['clase']} | "
             f"TRAIN:{registros[j]['clase']}", fill="black")
lienzo.save(SALIDA / "pares_cercanos.png")

por_fila = 10
lienzo2 = Image.new("RGB", (por_fila * IMG + 110, len(CLASES) * (IMG + 4)), "white")
dib2 = ImageDraw.Draw(lienzo2)
for fila, clase in enumerate(CLASES):
    rs = [r for r in registros if r["clase"] == clase and r["particion"] == "entrenamiento"]
    dib2.text((4, fila * (IMG + 4) + IMG // 2), clase, fill="black")
    for col, r in enumerate(rs[:por_fila]):
        lienzo2.paste(r["vista"].convert("RGB"), (110 + col * IMG, fila * (IMG + 4)))
lienzo2.save(SALIDA / "vista_red.png")

print(f"\nImágenes guardadas en {SALIDA}:")
print("  - pares_cercanos.png")
print("  - vista_red.png")
print("\nAuditoría terminada. No se modificó ninguna imagen del dataset.")
