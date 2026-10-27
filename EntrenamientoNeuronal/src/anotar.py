
from pathlib import Path
from flask import Flask, jsonify, request, render_template_string, send_from_directory, abort
import os

ROOT = Path(__file__).resolve().parents[1]
DET = ROOT / "dataset" / "deteccion"

CLASSES = ["Caja", "bota", "boteAluminio", "papelHigienico"]
EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}

app = Flask(__name__)


def image_files(split):
    if split not in ("train", "val"):
        abort(400, "Split inválido")

    folder = DET / "images" / split
    if not folder.exists():
        return []

    return sorted(
        p.name for p in folder.iterdir()
        if p.is_file() and p.suffix.lower() in EXTENSIONS
    )


def get_label_path(split, filename):
    if split not in ("train", "val"):
        abort(400, "Split inválido")

    if filename not in image_files(split):
        abort(404, "Imagen no encontrada")

    return DET / "labels" / split / f"{Path(filename).stem}.txt"


def read_labels(split, filename):
    path = get_label_path(split, filename)
    boxes = []

    if not path.exists():
        return boxes

    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) != 5:
            continue

        try:
            cls = int(parts[0])
            xc, yc, w, h = map(float, parts[1:])
            if cls in range(len(CLASSES)):
                boxes.append({
                    "class_id": cls,
                    "xc": xc, "yc": yc, "w": w, "h": h
                })
        except ValueError:
            continue

    return boxes


PAGE = r"""
<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Mini.Wall-e | Anotador YOLO</title>
<style>
* { box-sizing: border-box; }
body {
  margin: 0; padding: 20px;
  background: #111827; color: #f9fafb;
  font: 16px system-ui, sans-serif;
}
main { max-width: 1100px; margin: auto; }
h1 { margin-top: 0; }
.toolbar {
  display: flex; gap: 10px; flex-wrap: wrap;
  align-items: center; margin: 14px 0;
}
select, button {
  padding: 10px 13px; border-radius: 8px;
  border: 1px solid #4b5563; font-size: 15px;
}
select { background: #1f2937; color: white; }
button { cursor: pointer; background: #2563eb; color: white; }
button.secondary { background: #374151; }
button.danger { background: #b91c1c; }
button:disabled { opacity: .45; cursor: not-allowed; }
#status { color: #a7f3d0; min-height: 24px; }
#filename { overflow-wrap: anywhere; color: #d1d5db; }
#stage {
  position: relative; width: 100%; max-width: 1000px;
  line-height: 0; background: #030712;
}
#photo { display: block; width: 100%; height: auto; }
#overlay {
  position: absolute; inset: 0;
  width: 100%; height: 100%; cursor: crosshair;
}
.help { color: #d1d5db; line-height: 1.5; }
kbd { background: #374151; padding: 2px 6px; border-radius: 4px; }
</style>
</head>
<body>
<main>
  <h1>Mini.Wall-e — Anotación YOLO</h1>
  <p class="help">Dibuja una caja arrastrando el ratón alrededor de cada objeto.
  Selecciona la clase antes de dibujar. Guarda antes de cambiar de imagen.</p>

  <div class="toolbar">
    <label>Conjunto:
      <select id="split">
        <option value="train">Entrenamiento</option>
        <option value="val">Validación</option>
      </select>
    </label>
    <label>Clase:
      <select id="class"></select>
    </label>
  </div>

  <div class="toolbar">
    <button class="secondary" id="prev">← Anterior</button>
    <button class="secondary" id="next">Siguiente →</button>
    <button class="secondary" id="undo">Deshacer caja</button>
    <button class="danger" id="clear">Borrar todas las cajas</button>
    <button id="save">Guardar etiquetas</button>
  </div>

  <p id="filename"></p>
  <p id="counter"></p>
  <div id="stage">
    <img id="photo" alt="Imagen a anotar">
    <canvas id="overlay"></canvas>
  </div>
  <p id="status">Cargando imágenes...</p>
  <p class="help">
    Atajos: <kbd>A</kbd> anterior, <kbd>D</kbd> siguiente,
    <kbd>Ctrl+S</kbd> guardar, <kbd>Z</kbd> deshacer.
    Las cajas ya guardadas se muestran sobre la imagen.
  </p>
</main>

<script>
const classNames = {{ classes|tojson }};
const splitEl = document.getElementById('split');
const classEl = document.getElementById('class');
const photo = document.getElementById('photo');
const canvas = document.getElementById('overlay');
const ctx = canvas.getContext('2d');

classNames.forEach((name, i) => {
  const option = document.createElement('option');
  option.value = i;
  option.textContent = `${i}: ${name}`;
  classEl.appendChild(option);
});

let images = [];
let index = 0;
let boxes = [];
let drawing = false;
let start = null;
let current = null;
let dirty = false;

const status = msg => {
  document.getElementById('status').textContent = msg;
};

function currentSplit() {
  return splitEl.value;
}

function currentName() {
  return images[index];
}

async function loadImages() {
  const response = await fetch(`/api/images/${currentSplit()}`);
  if (!response.ok) throw new Error('No se pudieron cargar las imágenes');
  images = await response.json();
  index = 0;

  if (!images.length) {
    photo.removeAttribute('src');
    boxes = [];
    draw();
    document.getElementById('filename').textContent = 'No hay imágenes';
    document.getElementById('counter').textContent = '';
    status('Ejecuta primero preparar_dataset.py.');
    return;
  }
  await loadCurrent();
}

async function loadCurrent() {
  const name = currentName();
  document.getElementById('filename').textContent = name;
  document.getElementById('counter').textContent =
    `${index + 1} de ${images.length} imágenes`;

  const response = await fetch(
    `/api/labels/${currentSplit()}/${encodeURIComponent(name)}`
  );
  if (!response.ok) throw new Error('No se pudieron cargar las etiquetas');
  boxes = await response.json();
  dirty = false;

  photo.onload = () => {
    canvas.width = photo.naturalWidth;
    canvas.height = photo.naturalHeight;
    draw();
  };
  photo.src = `/image/${currentSplit()}/${encodeURIComponent(name)}`;
  status(`Imagen cargada: ${boxes.length} cajas.`);
}

function draw() {
  ctx.clearRect(0, 0, canvas.width, canvas.height);

  boxes.forEach((b, i) => {
    const x = (b.xc - b.w / 2) * canvas.width;
    const y = (b.yc - b.h / 2) * canvas.height;
    const w = b.w * canvas.width;
    const h = b.h * canvas.height;

    ctx.strokeStyle = ['#22c55e', '#38bdf8', '#facc15', '#f472b6'][b.class_id];
    ctx.lineWidth = Math.max(2, canvas.width / 400);
    ctx.strokeRect(x, y, w, h);

    ctx.font = `${Math.max(14, canvas.width / 65)}px sans-serif`;
    const label = classNames[b.class_id];
    ctx.fillStyle = ctx.strokeStyle;
    ctx.fillRect(x, Math.max(0, y - 26), ctx.measureText(label).width + 10, 25);
    ctx.fillStyle = '#111827';
    ctx.fillText(label, x + 5, Math.max(18, y - 7));
    ctx.fillStyle = '#ffffff';
    ctx.fillText(String(i + 1), x + 5, y + 20);
  });

  if (drawing && start && current) {
    ctx.strokeStyle = '#ffffff';
    ctx.lineWidth = Math.max(2, canvas.width / 400);
    ctx.setLineDash([8, 5]);
    ctx.strokeRect(
      Math.min(start.x, current.x),
      Math.min(start.y, current.y),
      Math.abs(current.x - start.x),
      Math.abs(current.y - start.y)
    );
    ctx.setLineDash([]);
  }
}

function point(event) {
  const rect = canvas.getBoundingClientRect();
  return {
    x: (event.clientX - rect.left) * canvas.width / rect.width,
    y: (event.clientY - rect.top) * canvas.height / rect.height
  };
}

canvas.addEventListener('pointerdown', event => {
  if (!photo.naturalWidth) return;
  drawing = true;
  start = point(event);
  current = start;
  canvas.setPointerCapture(event.pointerId);
  draw();
});

canvas.addEventListener('pointermove', event => {
  if (!drawing) return;
  current = point(event);
  draw();
});

canvas.addEventListener('pointerup', event => {
  if (!drawing) return;
  current = point(event);
  drawing = false;

  const x1 = Math.min(start.x, current.x);
  const y1 = Math.min(start.y, current.y);
  const x2 = Math.max(start.x, current.x);
  const y2 = Math.max(start.y, current.y);

  if (x2 - x1 >= 4 && y2 - y1 >= 4) {
    boxes.push({
      class_id: Number(classEl.value),
      xc: ((x1 + x2) / 2) / canvas.width,
      yc: ((y1 + y2) / 2) / canvas.height,
      w: (x2 - x1) / canvas.width,
      h: (y2 - y1) / canvas.height
    });
    dirty = true;
    status(`Caja añadida. Total: ${boxes.length}. Pulsa Guardar etiquetas.`);
  }
  start = current = null;
  draw();
});

document.getElementById('save').onclick = async () => {
  if (!currentName()) return;
  const response = await fetch(
    `/api/labels/${currentSplit()}/${encodeURIComponent(currentName())}`,
    {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({boxes})
    }
  );
  const result = await response.json();
  if (!response.ok) {
    status(`Error: ${result.error || 'No se pudo guardar'}`);
    return;
  }
  dirty = false;
  status(`Guardado correctamente: ${boxes.length} cajas.`);
};

async function changeImage(newIndex) {
  if (dirty && !confirm('Hay cambios sin guardar. ¿Descartarlos?')) return;
  if (newIndex < 0 || newIndex >= images.length) return;
  index = newIndex;
  await loadCurrent();
}

document.getElementById('prev').onclick = () => changeImage(index - 1);
document.getElementById('next').onclick = () => changeImage(index + 1);

document.getElementById('undo').onclick = () => {
  if (boxes.length) {
    boxes.pop();
    dirty = true;
    draw();
    status('Última caja eliminada. Guarda para confirmar.');
  }
};

document.getElementById('clear').onclick = () => {
  if (confirm('¿Eliminar todas las cajas de esta imagen?')) {
    boxes = [];
    dirty = true;
    draw();
    status('Cajas eliminadas de memoria. Pulsa Guardar etiquetas para confirmar.');
  }
};

splitEl.onchange = async () => {
  if (dirty && !confirm('Hay cambios sin guardar. ¿Descartarlos?')) {
    splitEl.value = splitEl.value === 'train' ? 'val' : 'train';
    return;
  }
  await loadImages();
};

document.addEventListener('keydown', event => {
  if (['SELECT', 'INPUT', 'TEXTAREA'].includes(document.activeElement.tagName)) return;
  if (event.ctrlKey && event.key.toLowerCase() === 's') {
    event.preventDefault();
    document.getElementById('save').click();
  } else if (event.key.toLowerCase() === 'a') {
    changeImage(index - 1);
  } else if (event.key.toLowerCase() === 'd') {
    changeImage(index + 1);
  } else if (event.key.toLowerCase() === 'z') {
    document.getElementById('undo').click();
  }
});

loadImages().catch(error => status(error.message));
</script>
</body>
</html>
"""


@app.get("/")
def index():
    return render_template_string(PAGE, classes=CLASSES)


@app.get("/api/images/<split>")
def api_images(split):
    return jsonify(image_files(split))


@app.get("/image/<split>/<path:filename>")
def serve_image(split, filename):
    if filename not in image_files(split):
        abort(404)
    return send_from_directory(DET / "images" / split, filename)


@app.get("/api/labels/<split>/<path:filename>")
def api_get_labels(split, filename):
    return jsonify(read_labels(split, filename))


@app.post("/api/labels/<split>/<path:filename>")
def api_save_labels(split, filename):
    path = get_label_path(split, filename)
    data = request.get_json(silent=True) or {}
    boxes = data.get("boxes")

    if not isinstance(boxes, list):
        return jsonify(error="La lista de cajas es inválida"), 400

    lines = []
    for box in boxes:
        try:
            cls = int(box["class_id"])
            xc = float(box["xc"])
            yc = float(box["yc"])
            w = float(box["w"])
            h = float(box["h"])

            if cls not in range(len(CLASSES)):
                raise ValueError("Clase inválida")
            if not all(map(lambda v: 0 <= v <= 1, (xc, yc, w, h))):
                raise ValueError("Coordenadas fuera de rango")
            if w <= 0 or h <= 0:
                raise ValueError("Ancho y alto deben ser positivos")
            if xc - w / 2 < 0 or yc - h / 2 < 0:
                raise ValueError("La caja sale de la imagen")
            if xc + w / 2 > 1 or yc + h / 2 > 1:
                raise ValueError("La caja sale de la imagen")

            lines.append(
                f"{cls} {xc:.6f} {yc:.6f} {w:.6f} {h:.6f}"
            )
        except (KeyError, TypeError, ValueError) as exc:
            return jsonify(error=f"Caja inválida: {exc}"), 400

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
    return jsonify(ok=True, count=len(lines))


if __name__ == "__main__":
    print(f"Dataset: {DET}")
    print("Abre el puerto 8000 en la pestaña Ports de Codespaces.")
    app.run(host="0.0.0.0", port=8000, debug=False)
