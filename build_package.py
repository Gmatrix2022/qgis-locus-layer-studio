from pathlib import Path
import zipfile


ROOT = Path(__file__).resolve().parent
PLUGIN = ROOT / "plugin" / "locus_map_control"
OUTPUT = ROOT / "qgis_locus_map_control.zip"


with zipfile.ZipFile(OUTPUT, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(PLUGIN.rglob("*")):
        if not path.is_file() or "__pycache__" in path.parts or path.suffix == ".pyc":
            continue
        archive.write(path, Path("locus_map_control") / path.relative_to(PLUGIN))

print(f"Package built: {OUTPUT}")
