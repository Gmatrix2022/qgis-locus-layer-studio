import unittest
import xml.etree.ElementTree as ElementTree
from pathlib import Path


THEMES_ROOT = (
    Path(__file__).resolve().parents[1]
    / "plugin"
    / "locus_map_control"
    / "styles"
)


class BuiltinThemesTests(unittest.TestCase):
    def test_all_builtin_themes_are_packaged_and_valid_xml(self):
        expected = ("LocusSat.xml", "OutdoorFull.xml", "ContourOnly.xml", "ContourSparse.xml")
        for name in expected:
            with self.subTest(theme=name):
                theme_path = THEMES_ROOT / name
                self.assertTrue(theme_path.is_file(), "{} must ship with the plugin".format(name))
                ElementTree.parse(theme_path)


if __name__ == "__main__":
    unittest.main()
