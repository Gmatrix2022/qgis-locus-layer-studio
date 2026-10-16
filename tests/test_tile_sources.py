import sys
import unittest
from pathlib import Path


PLUGIN_ROOT = Path(__file__).resolve().parents[1] / "plugin"
sys.path.insert(0, str(PLUGIN_ROOT))

from locus_map_control.tile_sources import (
    VECTOR_STYLE_PRESETS,
    raster_xyz_uri,
    vector_mbtiles_uri,
    vector_xyz_uri,
)


class TileSourceUriTests(unittest.TestCase):
    def test_raster_xyz_uri_keeps_tile_placeholders_and_orders_esri_yx(self):
        uri = raster_xyz_uri(
            "https://example.invalid/MapServer/tile/{z}/{y}/{x}"
        )
        self.assertIn("{z}", uri)
        self.assertIn("{y}", uri)
        self.assertIn("{x}", uri)
        self.assertIn("zmax=19", uri)

    def test_vector_xyz_uri_can_attach_a_style_url(self):
        uri = vector_xyz_uri(
            "https://tiles.example.invalid/{z}/{x}/{y}.pbf",
            "https://styles.example.invalid/style.json",
        )
        self.assertTrue(uri.startswith("type=xyz&url="))
        self.assertIn("styleUrl=https://styles.example.invalid/style.json", uri)

    def test_openfreemap_presets_pair_pbf_tiles_with_style_json(self):
        for tile_url, style_url, attribution in VECTOR_STYLE_PRESETS.values():
            uri = vector_xyz_uri(tile_url, style_url)
            self.assertIn("/planet/{z}/{x}/{y}.pbf", uri)
            self.assertIn("styleUrl=https://tiles.openfreemap.org/styles/", uri)
            self.assertTrue(attribution)

    def test_mbtiles_uri_requires_a_real_local_file(self):
        with self.assertRaises(ValueError):
            vector_mbtiles_uri("missing.mbtiles")

    def test_invalid_xyz_template_is_rejected(self):
        with self.assertRaises(ValueError):
            raster_xyz_uri("https://example.invalid/{z}/{x}.png")
        with self.assertRaises(ValueError):
            vector_xyz_uri("https://example.invalid/{z}/{x}/{y}.png")


if __name__ == "__main__":
    unittest.main()
