
from pathlib import Path
import shutil
import yaml

ROOT = Path(__file__).resolve().parents[1]
DATASET = ROOT / "dataset"
DET = DATASET / "deteccion"

CLASSES = ["Caja", "bota", "boteAluminio", "papelHigienico"]
EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}

def main():
    for source_split, target_split in [
        ("entrenamiento", "train"),
        ("validacion", "val"),
    ]:
        for class_name in CLASSES:
            source = DATASET / source_split / class_name
            if not source.exists():
                print(f"[AVISO] No existe: {source}")
                continue

            images_out = DET / "images" / target_split
            labels_out = DET / "labels" / target_split
            images_out.mkdir(parents=True, exist_ok=True)
            labels_out.mkdir(parents=True, exist_ok=True)

            for image in sorted(source.iterdir()):
                if not image.is_file() or image.suffix.lower() not in EXTENSIONS:
                    continue

                name = f"{class_name}_{image.stem}{image.suffix.lower()}"
                destination = images_out / name
                label = labels_out / f"{Path(name).stem}.txt"

                if not destination.exists():
                    shutil.copy2(image, destination)

                if not label.exists():
                    label.write_text("", encoding="utf-8")

                print(f"[OK] {destination.name}")

    DET.mkdir(parents=True, exist_ok=True)
    config = {
        "path": str(DET.resolve()),
        "train": "images/train",
        "val": "images/val",
        "names": {i: name for i, name in enumerate(CLASSES)},
    }
    (DET / "data.yaml").write_text(
        yaml.safe_dump(config, sort_keys=False, allow_unicode=True),
        encoding="utf-8",
    )
    print("\nDataset preparado.")
    print("Siguiente paso: python src/anotar.py")

if __name__ == "__main__":
    main()
