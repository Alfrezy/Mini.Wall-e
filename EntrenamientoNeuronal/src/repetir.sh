#!/bin/bash
cd "$(dirname "$0")/.."
source venv/bin/activate
mkdir -p resultados
: > resultados/repeticiones.txt
for aug in 0 1; do
  for seed in 1 2 3 4 5; do
    echo ">>> aug=$aug seed=$seed"
    SEED=$seed USAR_AUG=$aug python3 src/entrenar.py > resultados/rep_aug${aug}_s${seed}.txt 2>&1
    grep RESULTADO resultados/rep_aug${aug}_s${seed}.txt | tee -a resultados/repeticiones.txt
  done
done
echo
echo "================ RESUMEN ================"
python3 - <<'PY'
import re, statistics as st
from collections import defaultdict
datos = defaultdict(list)
for linea in open("resultados/repeticiones.txt"):
    m = re.search(r"aug=(\d) seed=(\d+) val_ultima=([\d.]+) train_inf_ultima=([\d.]+) val_mejor=([\d.]+)", linea)
    if m:
        datos[int(m.group(1))].append(tuple(float(m.group(k)) for k in (3, 4, 5)))
for aug in sorted(datos):
    f = datos[aug]
    print(f"aug={aug}  ({'con aug. suave' if aug else 'sin augmentation'})  n={len(f)}")
    for i, nombre in enumerate(("val_ultima", "train_inf_ultima", "val_mejor")):
        v = [x[i] * 100 for x in f]
        sd = st.stdev(v) if len(v) > 1 else float("nan")
        print(f"   {nombre:17s} media {st.mean(v):5.1f}%  sd {sd:4.1f}  min {min(v):5.1f}  max {max(v):5.1f}")
PY
