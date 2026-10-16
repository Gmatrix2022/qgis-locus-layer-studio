import sys
import unittest
from pathlib import Path


PLUGIN_ROOT = Path(__file__).resolve().parents[1] / "plugin"
sys.path.insert(0, str(PLUGIN_ROOT))

from locus_map_control.layer_order import layer_tree_insert_index


class LayerTreeInsertIndexTests(unittest.TestCase):
    def test_opaque_basemap_is_inserted_below_all_existing_layers(self):
        self.assertEqual(layer_tree_insert_index(child_count=3, overlay=False), 3)

    def test_transparent_overlay_is_inserted_above_existing_layers(self):
        self.assertEqual(layer_tree_insert_index(child_count=3, overlay=True), 0)


if __name__ == "__main__":
    unittest.main()
