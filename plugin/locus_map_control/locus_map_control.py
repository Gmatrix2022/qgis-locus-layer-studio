import hashlib
import json
import os
import re
import shutil
import tempfile
import uuid
import zipfile
import xml.etree.ElementTree as ElementTree
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from qgis.PyQt.QtCore import QLocale, QProcess, QStandardPaths, QTimer, Qt, QUrl
from qgis.PyQt.QtGui import QAction, QDesktopServices, QPixmap
from qgis.PyQt.QtWidgets import (
    QCheckBox,
    QComboBox,
    QDialog,
    QDockWidget,
    QFileDialog,
    QFormLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPushButton,
    QInputDialog,
    QSlider,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)
from qgis.core import QgsDataProvider, QgsProject, QgsRasterLayer, QgsVectorTileLayer

from .layer_order import layer_tree_insert_index
from .tile_sources import (
    RASTER_PRESETS,
    VECTOR_STYLE_PRESETS,
    raster_xyz_uri,
    vector_mbtiles_uri,
    vector_xyz_uri,
)

DEFAULT_SERVER = "http://127.0.0.1:18766"
MAPSFORGE_DOWNLOADS = "https://download.mapsforge.org/maps/v5/"


def is_zh_locale():
    try:
        from qgis.core import QgsApplication
        loc = QgsApplication.locale()
        if loc and loc.lower().startswith("zh"):
            return True
    except Exception:
        pass
    try:
        return QLocale().name().lower().startswith("zh")
    except Exception:
        return False


def tr(en_text, zh_text):
    return zh_text if is_zh_locale() else en_text


THEMES = [
    ("LocusSat — Satellite Overlay", "LocusSat.xml",
     "Transparent roads, paths, water, labels, and source contours for imagery overlays."),
    ("Elevate — Outdoor Hiking & MTB (v4/v5)", "Elevate.xml",
     "Classic OpenAndroMaps multi-style outdoor hiking and cycling theme (Mapsforge v4/v5)."),
    ("Elements — Clean Minimal Outdoor", "Elements.xml",
     "High-contrast, uncluttered outdoor theme for Mapsforge v4/v5 maps."),
    ("Outdoor Full — Opaque Basemap", "OutdoorFull.xml",
     "A complete offline outdoor map with a warm background and land-cover fills."),
    ("Contour Only — Overlay", "ContourOnly.xml",
     "A transparent contour-only render for a separate, switchable contour layer."),
    ("Contour Sparse — Overlay", "ContourSparse.xml",
     "A transparent render that keeps the intermediate and major contour classes."),
]


class LocusMapControlPlugin:
    def __init__(self, iface):
        self.iface = iface
        self.action = None
        self.dock = None
        self.profile_data = {}
        self.layer_data = {}
        self.theme_categories = {}
        self.server_base = DEFAULT_SERVER
        self.renderer_start_attempted = False

    def initGui(self):
        self.action = QAction("Locus Map Control…", self.iface.mainWindow())
        self.action.triggered.connect(self.show_dock)
        self.iface.addPluginToMenu("Locus Maps", self.action)
        QTimer.singleShot(1000, self.show_dock)

    def unload(self):
        if self.action:
            self.iface.removePluginMenu("Locus Maps", self.action)
        if self.dock:
            self.iface.removeDockWidget(self.dock)
            self.dock.deleteLater()

    def show_dock(self):
        if self.dock is None:
            self.dock = self._build_dock()
        area = Qt.DockWidgetArea.RightDockWidgetArea
        self.iface.addDockWidget(area, self.dock)
        self.dock.setFloating(False)
        self.dock.show()
        self.dock.setFloating(False)
        QTimer.singleShot(0, self._ensure_docked)
        self.dock.raise_()
        self._refresh_layers()

    def _ensure_docked(self):
        if not self.dock:
            return
        main_window = self.iface.mainWindow()
        area = Qt.DockWidgetArea.RightDockWidgetArea
        if self.dock.isFloating() or main_window.dockWidgetArea(self.dock) == Qt.DockWidgetArea.NoDockWidgetArea:
            self.iface.addDockWidget(area, self.dock)
            self.dock.setFloating(False)
        main_window.resizeDocks([self.dock], [440], Qt.Orientation.Horizontal)

    def _build_dock(self):
        dock = QDockWidget("Locus Maps and Overlays", self.iface.mainWindow())
        dock.setObjectName("LocusMapControlDock_1_0_7")
        dock.setMinimumWidth(430)
        dock.setAllowedAreas(
            Qt.DockWidgetArea.LeftDockWidgetArea | Qt.DockWidgetArea.RightDockWidgetArea
        )
        root = QWidget(dock)
        root_layout = QVBoxLayout(root)
        self.tabs = QTabWidget(root)
        tabs = self.tabs
        root_layout.addWidget(tabs)

        self.local_maps = self._discover_local_maps()

        offline_page = QWidget(tabs)
        layout = QVBoxLayout(offline_page)

        intro = QLabel(
            "Render offline Mapsforge .map data with a selected theme. The result is an XYZ raster layer; "
            "the source map remains vector data, but the rendered QGIS layer is not editable."
        )
        intro.setWordWrap(True)
        layout.addWidget(intro)

        form = QFormLayout()
        self.layer_combo = QComboBox()
        self.layer_combo.currentIndexChanged.connect(self._load_selected_profile)
        form.addRow("Current Locus layer", self.layer_combo)

        self.title_edit = QLineEdit()
        self.title_edit.setPlaceholderText("New layer name")
        form.addRow("Layer name", self.title_edit)

        self.maps_combo = QComboBox()
        self.maps_combo.addItem("— Detected Local .map Library —", "")
        for label, path in self.local_maps:
            self.maps_combo.addItem(label, path)
        self.maps_combo.currentIndexChanged.connect(self._select_preset_map)
        form.addRow("Local .map library", self.maps_combo)

        map_row = QWidget()
        map_layout = QHBoxLayout(map_row)
        map_layout.setContentsMargins(0, 0, 0, 0)
        self.maps_edit = QLineEdit()
        self.maps_edit.setPlaceholderText("Select one or more .map files")
        browse = QPushButton("Browse…")
        browse.clicked.connect(self._browse_maps)
        map_layout.addWidget(self.maps_edit, 1)
        map_layout.addWidget(browse)
        form.addRow("Mapsforge data", map_row)

        self.theme_combo = QComboBox()
        for name, filename, _description in THEMES:
            self.theme_combo.addItem(name, "builtin:" + filename)
        self.theme_combo.currentIndexChanged.connect(self._show_theme_description)
        theme_row = QWidget()
        theme_layout = QHBoxLayout(theme_row)
        theme_layout.setContentsMargins(0, 0, 0, 0)
        theme_layout.addWidget(self.theme_combo, 1)
        browse_theme = QPushButton("Browse…")
        browse_theme.clicked.connect(self._browse_theme)
        theme_layout.addWidget(browse_theme)
        form.addRow("Map theme", theme_row)
        layout.addLayout(form)

        self.theme_description = QLabel()
        self.theme_description.setWordWrap(True)
        self.theme_description.setStyleSheet("color: #555; padding: 2px 0 4px 0;")
        layout.addWidget(self.theme_description)

        self.mode_combo = QComboBox()
        self.mode_combo.addItem("Transparent overlay", True)
        self.mode_combo.addItem("Opaque basemap", False)
        layout.addWidget(self._labeled_row("Display mode", self.mode_combo))

        self.labels_check = QCheckBox("Show place names and labels")
        self.water_check = QCheckBox("Show water features")
        self.contours_check = QCheckBox("Show contours and protected-area hatching")
        self.labels_check.setChecked(True)
        self.water_check.setChecked(True)
        self.contours_check.setChecked(True)
        layout.addWidget(self.labels_check)
        layout.addWidget(self.water_check)
        layout.addWidget(self.contours_check)

        opacity_row = QWidget()
        opacity_layout = QHBoxLayout(opacity_row)
        opacity_layout.setContentsMargins(0, 0, 0, 0)
        self.opacity_slider = QSlider(Qt.Orientation.Horizontal)
        self.opacity_slider.setRange(0, 100)
        self.opacity_slider.setValue(100)
        self.opacity_value = QLabel("100%")
        self.opacity_slider.valueChanged.connect(self._change_opacity)
        opacity_layout.addWidget(self.opacity_slider, 1)
        opacity_layout.addWidget(self.opacity_value)
        layout.addWidget(self._labeled_row("QGIS layer opacity", opacity_row))
        self.mode_combo.currentIndexChanged.connect(self._sync_mode_opacity_controls)

        apply_button = QPushButton("Apply to Current Layer")
        apply_button.clicked.connect(self._apply_to_selected)
        add_button = QPushButton("Add as New Layer")
        add_button.clicked.connect(self._add_layer)
        buttons = QHBoxLayout()
        buttons.addWidget(apply_button)
        buttons.addWidget(add_button)
        layout.addLayout(buttons)

        download_row = QHBoxLayout()
        download_button = QPushButton("Mapsforge map downloads…")
        download_button.clicked.connect(self._open_map_downloads)
        download_row.addWidget(download_button)
        download_row.addStretch(1)
        layout.addLayout(download_row)

        note = QLabel(
            "Contours appear only when the source .map contains contour features. The plugin does not generate contours.\n"
            "External XML/ZIP themes may be loaded when their license allows it."
        )
        note.setWordWrap(True)
        note.setStyleSheet("color: #76551d; padding-top: 6px;")
        layout.addWidget(note)
        layout.addStretch(1)
        tabs.addTab(offline_page, "Offline .map")

        vector_page = QWidget(tabs)
        vector_layout = QVBoxLayout(vector_page)
        vector_intro = QLabel(
            "Add an online Mapbox Vector Tile (MVT) style, a custom XYZ PBF source, or a local MBTiles file. "
            "QGIS renders these as native vector-tile layers."
        )
        vector_intro.setWordWrap(True)
        vector_layout.addWidget(vector_intro)
        vector_form = QFormLayout()
        self.vector_kind_combo = QComboBox()
        for name in VECTOR_STYLE_PRESETS:
            self.vector_kind_combo.addItem(name, "style")
        self.vector_kind_combo.addItem("Custom XYZ PBF", "xyz")
        self.vector_kind_combo.addItem("Local MBTiles", "mbtiles")
        self.vector_kind_combo.currentIndexChanged.connect(self._select_vector_source)
        vector_form.addRow("Vector source", self.vector_kind_combo)

        vector_source_row = QWidget()
        vector_source_layout = QHBoxLayout(vector_source_row)
        vector_source_layout.setContentsMargins(0, 0, 0, 0)
        self.vector_source_edit = QLineEdit(VECTOR_STYLE_PRESETS["OpenFreeMap — Liberty"][0])
        self.vector_source_edit.setPlaceholderText("PBF XYZ template or MBTiles path")
        vector_source_layout.addWidget(self.vector_source_edit, 1)
        self.vector_browse_button = QPushButton("Browse…")
        self.vector_browse_button.clicked.connect(self._browse_vector_mbtiles)
        vector_source_layout.addWidget(self.vector_browse_button)
        vector_form.addRow("PBF / data source", vector_source_row)

        self.vector_style_edit = QLineEdit(VECTOR_STYLE_PRESETS["OpenFreeMap — Liberty"][1])
        self.vector_style_edit.setPlaceholderText("Optional Mapbox GL JSON style URL")
        vector_form.addRow("Style URL", self.vector_style_edit)
        self.vector_name_edit = QLineEdit("OpenFreeMap — Liberty")
        vector_form.addRow("Layer name", self.vector_name_edit)
        self.vector_role_combo = QComboBox()
        self.vector_role_combo.addItem("Transparent overlay (top)", True)
        self.vector_role_combo.addItem("Basemap (bottom)", False)
        vector_form.addRow("Layer role", self.vector_role_combo)
        self.vector_opacity_slider = QSlider(Qt.Orientation.Horizontal)
        self.vector_opacity_slider.setRange(0, 100)
        self.vector_opacity_slider.setValue(100)
        vector_form.addRow("Layer opacity", self.vector_opacity_slider)
        self.vector_attribution_edit = QLineEdit(
            VECTOR_STYLE_PRESETS["OpenFreeMap — Liberty"][2]
        )
        vector_form.addRow("Attribution", self.vector_attribution_edit)
        vector_layout.addLayout(vector_form)
        vector_add_button = QPushButton("Add Vector Tile Layer")
        vector_add_button.clicked.connect(self._add_vector_layer)
        vector_layout.addWidget(vector_add_button)
        vector_note = QLabel(
            "Vector tiles contain clipped map features, not pre-rendered images. Use an approved source and keep its attribution. "
            "MBTiles is offline; XYZ PBF and style URLs require a network connection."
        )
        vector_note.setWordWrap(True)
        vector_note.setStyleSheet("color: #76551d; padding-top: 6px;")
        vector_layout.addWidget(vector_note)
        vector_layout.addStretch(1)
        tabs.addTab(vector_page, "Vector tiles")

        raster_page = QWidget(tabs)
        raster_layout = QVBoxLayout(raster_page)
        raster_intro = QLabel(
            "Add a common XYZ raster basemap or enter another tile URL. Raster imagery is separate from vector roads and contours."
        )
        raster_intro.setWordWrap(True)
        raster_layout.addWidget(raster_intro)
        raster_form = QFormLayout()
        self.raster_preset_combo = QComboBox()
        for name in list(RASTER_PRESETS) + ["Custom XYZ"]:
            self.raster_preset_combo.addItem(name)
        self.raster_preset_combo.currentTextChanged.connect(self._select_raster_preset)
        raster_form.addRow("Basemap preset", self.raster_preset_combo)
        self.raster_name_edit = QLineEdit("Esri World Imagery")
        raster_form.addRow("Layer name", self.raster_name_edit)
        self.raster_url_edit = QLineEdit(RASTER_PRESETS["Esri World Imagery"][0])
        raster_form.addRow("XYZ tile URL", self.raster_url_edit)
        self.raster_attribution_edit = QLineEdit(RASTER_PRESETS["Esri World Imagery"][1])
        raster_form.addRow("Attribution", self.raster_attribution_edit)
        self.raster_role_combo = QComboBox()
        self.raster_role_combo.addItem("Opaque basemap (bottom)", False)
        self.raster_role_combo.addItem("Transparent overlay (top)", True)
        raster_form.addRow("Layer role", self.raster_role_combo)
        self.raster_opacity_slider = QSlider(Qt.Orientation.Horizontal)
        self.raster_opacity_slider.setRange(0, 100)
        self.raster_opacity_slider.setValue(100)
        raster_form.addRow("Layer opacity", self.raster_opacity_slider)
        raster_layout.addLayout(raster_form)
        raster_add_button = QPushButton("Add Raster Basemap")
        raster_add_button.clicked.connect(self._add_raster_layer)
        raster_layout.addWidget(raster_add_button)
        raster_note = QLabel(
            "The built-in services are online tile sources. Their availability and use limits are set by each provider; "
            "do not bulk-download or redistribute their tiles."
        )
        raster_note.setWordWrap(True)
        raster_note.setStyleSheet("color: #76551d; padding-top: 6px;")
        raster_layout.addWidget(raster_note)
        raster_layout.addStretch(1)
        tabs.addTab(raster_page, "Raster basemaps")

        golden_page = QWidget(tabs)
        golden_layout = QVBoxLayout(golden_page)
        golden_intro = QLabel(
            "<b>Golden Three-Layer Stack (ArcGIS Pro / QGIS Unified Model)</b><br>"
            "Load the coordinated 3-layer outdoor stack with one click:<br>"
            "• <b>Bottom:</b> High-resolution Satellite Basemap (Esri World Imagery)<br>"
            "• <b>Middle:</b> Topographic Contours & Relief (OpenTopoMap, 60% opacity)<br>"
            "• <b>Top:</b> Offline Mapsforge Vector Trails & POIs (LocusSat transparent overlay)"
        )
        golden_intro.setWordWrap(True)
        golden_layout.addWidget(golden_intro)

        golden_form = QFormLayout()

        self.golden_map_combo = QComboBox()
        for label, path in self.local_maps:
            self.golden_map_combo.addItem(label, path)
        golden_form.addRow(tr("Offline .map Data", "离线 .map 数据源"), self.golden_map_combo)

        self.golden_style_combo = QComboBox()
        self.golden_style_combo.addItem(tr("LocusSat.xml (Transparent Overlay — Roads, Trails & POIs)", "LocusSat.xml (纯透明叠置 — 剥离面多边形色块，透光路网与注记)"), "LocusSat.xml")
        self.golden_style_combo.addItem(tr("Elevate.xml (Outdoor Hiking & MTB — Full/OAM)", "Elevate.xml (经典户外徒步/骑行全要素主题 — OpenAndroMaps)"), "Elevate.xml")
        self.golden_style_combo.addItem(tr("Elements.xml (Minimal Outdoor — High Contrast)", "Elements.xml (极简高对比度户外主题 — 适合强光环境)"), "Elements.xml")
        self.golden_style_combo.addItem(tr("ContourOnly.xml (Transparent Overlay — Contours Only)", "ContourOnly.xml (纯等高线切片 — 50m高程透明叠加)"), "ContourOnly.xml")
        self.golden_style_combo.addItem(tr("ContourSparse.xml (Transparent Overlay — Sparse 50m Contours)", "ContourSparse.xml (稀疏等高线切片)"), "ContourSparse.xml")
        self.golden_style_combo.addItem(tr("OutdoorFull.xml (Opaque Basemap — Full Landcover & Roads)", "OutdoorFull.xml (全要素不透明底图)"), "OutdoorFull.xml")
        golden_form.addRow(tr("Vector Overlay Style", "矢量叠置主题风格"), self.golden_style_combo)

        golden_vector_op_row = QWidget()
        golden_vector_op_layout = QHBoxLayout(golden_vector_op_row)
        golden_vector_op_layout.setContentsMargins(0, 0, 0, 0)
        self.golden_vector_opacity_slider = QSlider(Qt.Orientation.Horizontal)
        self.golden_vector_opacity_slider.setRange(0, 100)
        self.golden_vector_opacity_slider.setValue(100)
        self.golden_vector_opacity_label = QLabel("100%")
        self.golden_vector_opacity_slider.valueChanged.connect(self._on_golden_vector_opacity_changed)
        golden_vector_op_layout.addWidget(self.golden_vector_opacity_slider, 1)
        golden_vector_op_layout.addWidget(self.golden_vector_opacity_label)
        golden_form.addRow(tr("Road / Trail Opacity", "路网/步道透明度"), golden_vector_op_row)

        self.golden_topo_check = QCheckBox(tr("Include Topo Relief & Contours (OpenTopoMap)", "包含地形立体晕渲与等高线 (OpenTopoMap)"))
        self.golden_topo_check.setChecked(True)
        golden_form.addRow(tr("Middle Topo Layer", "中间地形层"), self.golden_topo_check)

        golden_topo_op_row = QWidget()
        golden_topo_op_layout = QHBoxLayout(golden_topo_op_row)
        golden_topo_op_layout.setContentsMargins(0, 0, 0, 0)
        self.golden_topo_opacity_slider = QSlider(Qt.Orientation.Horizontal)
        self.golden_topo_opacity_slider.setRange(0, 100)
        self.golden_topo_opacity_slider.setValue(60)
        self.golden_topo_opacity_label = QLabel("60%")
        self.golden_topo_opacity_slider.valueChanged.connect(self._on_golden_topo_opacity_changed)
        golden_topo_op_layout.addWidget(self.golden_topo_opacity_slider, 1)
        golden_topo_op_layout.addWidget(self.golden_topo_opacity_label)
        golden_form.addRow(tr("Topo Relief Opacity", "地形晕渲透明度"), golden_topo_op_row)

        self.golden_sat_combo = QComboBox()
        self.golden_sat_combo.addItem("Google Satellite (Locus Custom Proxy)")
        self.golden_sat_combo.addItem("Google Satellite (Standard XYZ)")
        self.golden_sat_combo.addItem("Google Hybrid (Satellite + Roads/Labels)")
        self.golden_sat_combo.addItem("Esri World Imagery")
        self.golden_sat_combo.addItem("OpenStreetMap Standard")
        golden_form.addRow(tr("Satellite Basemap", "遥感影像底图"), self.golden_sat_combo)

        self.golden_mapsforge_check = QCheckBox(tr("Include Mapsforge Vector Trails (Top Overlay)", "包含 Mapsforge 矢量路网 (顶层透明叠置)"))
        self.golden_mapsforge_check.setChecked(True)
        golden_form.addRow(tr("Top Vector Layer", "顶层矢量路网"), self.golden_mapsforge_check)
        golden_layout.addLayout(golden_form)

        load_golden_btn = QPushButton(tr("⭐ Load Golden 3-Layer Stack", "⭐ 一键装配黄金三层流"))
        load_golden_btn.setStyleSheet("font-weight: bold; padding: 7px; background-color: #e6f3ff; font-size: 13px;")
        load_golden_btn.clicked.connect(self._load_golden_stack)
        golden_layout.addWidget(load_golden_btn)

        golden_note = QLabel(
            tr(
                "The 3 layers are inserted in the exact rendering hierarchy required for clear outdoor visualization, "
                "preventing dense contours from obscuring trails or washing out satellite imagery.",
                "黄金三层按照制图级严格垂直层序装配（底层卫星遥感 + 中层60%地形阴影 + 顶层纯透明路网标注），"
                "彻底杜绝等高线压盖小径或面要素遮挡遥感图。"
            )
        )
        golden_note.setWordWrap(True)
        golden_note.setStyleSheet("color: #76551d; padding-top: 6px;")
        golden_layout.addWidget(golden_note)
        golden_layout.addStretch(1)
        tabs.addTab(golden_page, tr("⭐ Golden Stack", "⭐ 黄金三层栈"))

        footer_row = QWidget()
        footer_layout = QHBoxLayout(footer_row)
        footer_layout.setContentsMargins(0, 4, 0, 0)
        self.status_label = QLabel(tr("Connecting to the local Mapsforge service…", "正在连接本地 Mapsforge 切片中台服务…"))
        self.status_label.setWordWrap(True)
        footer_layout.addWidget(self.status_label, 1)

        about_btn = QPushButton(tr("ℹ️ About / Sponsor", "ℹ️ 关于与打赏"))
        about_btn.setStyleSheet("padding: 3px 8px; font-size: 11px; background-color: #f1f5f9; border-radius: 4px;")
        about_btn.clicked.connect(self._show_about_dialog)
        footer_layout.addWidget(about_btn)

        root_layout.addWidget(footer_row)
        dock.setWidget(root)
        self._show_theme_description()
        self._select_raster_preset(self.raster_preset_combo.currentText())
        return dock

    def _show_about_dialog(self):
        dialog = QDialog(self.dock)
        dialog.setWindowTitle(tr("About & Sponsor — Locus Map Control", "关于与赞赏支持 — Locus Map Control"))
        dialog.setMinimumWidth(460)
        vbox = QVBoxLayout(dialog)

        title_lbl = QLabel("<h3>Locus Map Control for QGIS (v1.1.0)</h3>")
        title_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        vbox.addWidget(title_lbl)

        desc = QLabel(
            tr(
                "Bridging mobile Mapsforge (.map) vector maps, online vector tiles (PBF/MVT), "
                "and satellite basemaps with Golden Three-Layer Stack.<br><br>"
                "<b>Author:</b> Lao Yang & GeoAI Agent Team<br>"
                "<b>GitHub:</b> <a href='https://github.com/Gmatrix2022/qgis-locus-layer-studio'>https://github.com/Gmatrix2022/qgis-locus-layer-studio</a><br>"
                "<b>Gitee:</b> <a href='https://gitee.com/nousyang/qgis-locus-layer-studio'>https://gitee.com/nousyang/qgis-locus-layer-studio</a><br><br>"
                "If this tool helps your GIS workflows and outdoor trail planning, "
                "welcome to support Lao Yang with a cup of coffee! ☕",
                "打破移动端 Mapsforge (.map) 离线矢量地图、在线矢量瓦片 (PBF/MVT) "
                "与遥感卫星底图壁垒，独创户外黄金三层流一键成图。<br><br>"
                "<b>开发团队：</b> 老杨 与 GeoAI 智能体协同中枢<br>"
                "<b>GitHub：</b> <a href='https://github.com/Gmatrix2022/qgis-locus-layer-studio'>https://github.com/Gmatrix2022/qgis-locus-layer-studio</a><br>"
                "<b>Gitee 码云：</b> <a href='https://gitee.com/nousyang/qgis-locus-layer-studio'>https://gitee.com/nousyang/qgis-locus-layer-studio</a><br><br>"
                "如果您觉得本插件对您的 GIS 制图与户外轨迹规划有帮助，欢迎请老杨喝杯咖啡支持持续迭代！☕"
            )
        )
        desc.setOpenExternalLinks(True)
        desc.setWordWrap(True)
        vbox.addWidget(desc)

        qr_row = QWidget()
        qr_layout = QHBoxLayout(qr_row)
        base_dir = os.path.dirname(os.path.abspath(__file__))
        wx_qr = os.path.join(base_dir, "assets", "wechat_sponsor_qr.png")
        ali_qr = os.path.join(base_dir, "assets", "alipay_sponsor_qr.jpg")

        if os.path.isfile(wx_qr):
            wx_col = QVBoxLayout()
            wx_lbl = QLabel()
            pix = QPixmap(wx_qr).scaled(170, 170, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
            wx_lbl.setPixmap(pix)
            wx_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
            wx_col.addWidget(wx_lbl)
            wx_text = QLabel(tr("<b>WeChat Pay</b>", "<b>微信赞赏码</b>"))
            wx_text.setAlignment(Qt.AlignmentFlag.AlignCenter)
            wx_col.addWidget(wx_text)
            qr_layout.addLayout(wx_col)

        if os.path.isfile(ali_qr):
            ali_col = QVBoxLayout()
            ali_lbl = QLabel()
            pix2 = QPixmap(ali_qr).scaled(170, 170, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
            ali_lbl.setPixmap(pix2)
            ali_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
            ali_col.addWidget(ali_lbl)
            ali_text = QLabel(tr("<b>Alipay</b>", "<b>支付宝打赏码</b>"))
            ali_text.setAlignment(Qt.AlignmentFlag.AlignCenter)
            ali_col.addWidget(ali_text)
            qr_layout.addLayout(ali_col)

        vbox.addWidget(qr_row)

        close_btn = QPushButton(tr("Close", "关闭"))
        close_btn.clicked.connect(dialog.accept)
        vbox.addWidget(close_btn)

        dialog.exec()

    def _open_map_downloads(self):
        QDesktopServices.openUrl(QUrl(MAPSFORGE_DOWNLOADS))

    def _select_vector_source(self, *_args):
        kind = self.vector_kind_combo.currentData()
        is_mbtiles = kind == "mbtiles"
        self.vector_browse_button.setEnabled(is_mbtiles)
        self.vector_style_edit.setEnabled(kind != "style")
        if kind == "style":
            name = self.vector_kind_combo.currentText()
            if name not in VECTOR_STYLE_PRESETS:
                name = "OpenFreeMap — Liberty"
            self.vector_source_edit.setText(VECTOR_STYLE_PRESETS[name][0])
            self.vector_name_edit.setText(name)
            self.vector_style_edit.setText(VECTOR_STYLE_PRESETS[name][1])
            self.vector_attribution_edit.setText(VECTOR_STYLE_PRESETS[name][2])
        elif kind == "xyz":
            self.vector_source_edit.setText("https://tiles.example.org/{z}/{x}/{y}.pbf")
            self.vector_name_edit.setText("Custom Vector Tiles")
            self.vector_style_edit.clear()
            self.vector_attribution_edit.clear()
        else:
            self.vector_source_edit.clear()
            self.vector_name_edit.setText("Local Vector Tiles")
            self.vector_style_edit.clear()
            self.vector_attribution_edit.clear()

    def _browse_vector_mbtiles(self):
        path, _ = QFileDialog.getOpenFileName(
            self.dock, "Select Vector MBTiles", os.path.expanduser("~"),
            "MBTiles (*.mbtiles *.mbtile);;All files (*.*)",
        )
        if path:
            self.vector_source_edit.setText(path)
            if not self.vector_name_edit.text().strip() or self.vector_name_edit.text() == "Local Vector Tiles":
                self.vector_name_edit.setText(os.path.basename(path))

    def _select_raster_preset(self, name):
        if name in RASTER_PRESETS:
            url, attribution = RASTER_PRESETS[name]
            self.raster_url_edit.setText(url)
            self.raster_attribution_edit.setText(attribution)
            self.raster_name_edit.setText(name)
            self.raster_url_edit.setReadOnly(True)
            self.raster_attribution_edit.setReadOnly(True)
        else:
            self.raster_url_edit.clear()
            self.raster_attribution_edit.clear()
            self.raster_name_edit.setText("Custom XYZ Basemap")
            self.raster_url_edit.setReadOnly(False)
            self.raster_attribution_edit.setReadOnly(False)

    def _sync_mode_opacity_controls(self, *_args):
        is_overlay = bool(self.mode_combo.currentData())
        self.opacity_slider.setEnabled(is_overlay)
        if not is_overlay:
            self.opacity_slider.setValue(100)

    @staticmethod
    def _labeled_row(label, widget):
        row = QWidget()
        layout = QHBoxLayout(row)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(QLabel(label))
        layout.addWidget(widget, 1)
        return row

    def _request_json(self, url, fields=None):
        if fields is None:
            request = Request(url, headers={"Accept": "application/json"})
        else:
            payload = urlencode(fields).encode("utf-8")
            request = Request(url, data=payload, headers={
                "Accept": "application/json",
                "Content-Type": "application/x-www-form-urlencoded; charset=utf-8",
            })
        with urlopen(request, timeout=8) as response:
            return json.loads(response.read().decode("utf-8"))

    def _refresh_layers(self, preferred_layer_id=None):
        if not hasattr(self, "layer_combo"):
            return
        prior_id = preferred_layer_id
        if prior_id is None and self.layer_combo.currentIndex() >= 0:
            prior_id = self.layer_combo.currentData()
        project = QgsProject.instance()
        raster_layers = [
            layer for layer in project.mapLayers().values()
            if isinstance(layer, QgsRasterLayer) and "/tiles/" in layer.source()
        ]
        self.layer_combo.blockSignals(True)
        self.layer_combo.clear()
        self.layer_data = {}
        for layer in raster_layers:
            match = re.search(r"/tiles/([^/]+)/", layer.source())
            if not match:
                continue
            profile_id = match.group(1)
            self.layer_data[layer.id()] = layer
            if "://" in layer.source():
                server_match = re.search(r"(https?://[^/]+)", layer.source())
                if server_match:
                    self.server_base = server_match.group(1)
            self.layer_combo.addItem("{}  [{}]".format(layer.name(), profile_id), layer.id())
        self.layer_combo.blockSignals(False)
        if prior_id is not None:
            idx = self.layer_combo.findData(prior_id)
            if idx >= 0:
                self.layer_combo.setCurrentIndex(idx)
        self._load_profiles()
        self._load_selected_profile()

    def _probe_server(self):
        for port in (18765, 18766):
            base = "http://127.0.0.1:{}".format(port)
            try:
                response = self._request_json(base + "/api/profiles")
                if "profiles" in response:
                    return base, response
            except Exception:
                continue
        return None, None

    def _load_profiles(self):
        detected_base, response = self._probe_server()
        if detected_base and response:
            self.server_base = detected_base
            self.profile_data = {
                item.get("profileId"): item for item in response.get("profiles", [])
            }
            self.status_label.setText(
                "Connected to Mapsforge service ({}): {} render profile(s).".format(
                    self.server_base, len(self.profile_data)
                )
            )
            return

        if not self.renderer_start_attempted:
            self.renderer_start_attempted = True
            if self._start_bundled_renderer():
                self.status_label.setText("Starting the bundled Mapsforge renderer…")
                QTimer.singleShot(1500, self._load_profiles)
                return

        self.profile_data = {}
        self.status_label.setText(
            "Could not connect to Mapsforge renderer on port 18765/18766. Check Java 17 and server status."
        )

    def _start_bundled_renderer(self):
        plugin_root = os.path.dirname(__file__)
        renderer_root = os.path.join(plugin_root, "renderer")
        server_jar = os.path.join(renderer_root, "MapsforgeTileServer.jar")
        libraries = sorted(
            os.path.join(renderer_root, "lib", name)
            for name in os.listdir(os.path.join(renderer_root, "lib"))
            if name.lower().endswith(".jar")
        ) if os.path.isdir(os.path.join(renderer_root, "lib")) else []
        java = shutil.which("java")
        if not java or not os.path.isfile(server_jar):
            return False
        classpath = os.pathsep.join([server_jar] + libraries)
        cache_root = QStandardPaths.writableLocation(QStandardPaths.StandardLocation.CacheLocation)
        if not cache_root:
            cache_root = os.path.join(tempfile.gettempdir(), "qgis_locus_map_control")
        cache_dir = os.path.join(cache_root, "mapsforge_tiles")
        os.makedirs(cache_dir, exist_ok=True)
        args = [
            "-Dfile.encoding=UTF-8",
            "-Djava.awt.headless=true",
            "-Xmx1024m",
            "-cp", classpath,
            "org.mapsforge.map.layer.renderer.MapsforgeTileServer",
            "--port", "18766",
            "--cache-dir", cache_dir,
            "--themes-dir", os.path.join(plugin_root, "styles"),
        ]
        started = QProcess.startDetached(java, args, renderer_root)
        if isinstance(started, tuple):
            return bool(started[0])
        return bool(started)

    def _load_selected_profile(self, *_args):
        if not hasattr(self, "layer_combo"):
            return
        layer = self.layer_data.get(self.layer_combo.currentData())
        match = re.search(r"/tiles/([^/]+)/", layer.source()) if layer else None
        profile = self.profile_data.get(match.group(1)) if match else None
        if profile:
            maps = profile.get("mapFiles", [])
            self.maps_edit.setText(";".join(maps))
            self.title_edit.setText(layer.name())
            is_transparent = bool(profile.get("transparent", True))
            self.mode_combo.setCurrentIndex(0 if is_transparent else 1)
            self.labels_check.setChecked(bool(profile.get("renderLabels", True)))
            excluded = {item.strip() for item in profile.get("excludeCats", "").split(",") if item.strip()}
            self.water_check.setChecked("water" not in excluded)
            self.contours_check.setChecked("contour" not in excluded and "hatch" not in excluded)
            opacity = layer.opacity() if is_transparent and hasattr(layer, "opacity") else 1.0
            if not is_transparent:
                layer.setOpacity(1.0)
            self.opacity_slider.blockSignals(True)
            self.opacity_slider.setValue(max(0, min(100, round(opacity * 100))))
            self.opacity_slider.blockSignals(False)

    def _show_theme_description(self, *_args):
        index = self.theme_combo.currentIndex()
        data = self.theme_combo.currentData()
        if isinstance(data, str) and data.startswith("external:"):
            self.theme_description.setText(
                "External Mapsforge theme. Confirm its license before redistribution."
            )
        elif 0 <= index < len(THEMES):
            self.theme_description.setText(THEMES[index][2])

    def _browse_maps(self):
        paths, _ = QFileDialog.getOpenFileNames(
            self.dock,
            "Select Mapsforge Offline Map(s)",
            os.path.expanduser("~"),
            "Mapsforge map (*.map);;All files (*.*)",
        )
        if paths:
            self.maps_edit.setText(";".join(paths))
            if not self.title_edit.text().strip():
                self.title_edit.setText(os.path.basename(paths[0]))

    def _browse_theme(self):
        path, _ = QFileDialog.getOpenFileName(
            self.dock,
            "Select a Mapsforge Theme",
            os.path.expanduser("~"),
            "Mapsforge themes (*.xml *.zip);;XML themes (*.xml);;ZIP archives (*.zip)",
        )
        if not path:
            return
        try:
            theme_path = path
            if path.lower().endswith(".zip"):
                with zipfile.ZipFile(path, "r") as archive:
                    theme_names = [
                        name for name in archive.namelist()
                        if name.lower().endswith(".xml") and not name.startswith("/")
                    ]
                if not theme_names:
                    raise ValueError("The selected archive contains no XML theme.")
                if len(theme_names) == 1:
                    theme_name = theme_names[0]
                else:
                    theme_name, accepted = QInputDialog.getItem(
                        self.dock, "Choose a Theme XML", "Theme file:", theme_names, 0, False
                    )
                    if not accepted:
                        return
                digest = hashlib.sha256(
                    (os.path.realpath(path) + str(os.path.getmtime(path))).encode("utf-8")
                ).hexdigest()[:16]
                extract_root = os.path.join(
                    tempfile.gettempdir(), "qgis_locus_map_control", "themes", digest
                )
                self._safe_extract_theme(path, extract_root)
                theme_path = os.path.join(extract_root, *theme_name.replace("\\", "/").split("/"))
            ElementTree.parse(theme_path)
            data = "external:" + os.path.realpath(theme_path)
            index = self.theme_combo.findData(data)
            if index < 0:
                self.theme_combo.addItem(os.path.basename(theme_path), data)
                index = self.theme_combo.count() - 1
            self.theme_combo.setCurrentIndex(index)
        except Exception as exc:
            QMessageBox.critical(self.dock, "Could Not Load Map Theme", str(exc))

    def _resolve_theme(self):
        index = self.theme_combo.currentIndex()
        data = self.theme_combo.currentData()
        if index < 0 or not isinstance(data, str):
            raise ValueError("Select a map theme.")
        if data.startswith("builtin:"):
            xml_path = os.path.join(os.path.dirname(__file__), "styles", data[len("builtin:"):])
        elif data.startswith("external:"):
            xml_path = data[len("external:"):]
        else:
            raise ValueError("The selected map theme is not a valid theme entry.")
        if not os.path.isfile(xml_path):
            raise FileNotFoundError("Theme XML not found: " + xml_path)
        ElementTree.parse(xml_path)
        return xml_path

    @staticmethod
    def _safe_extract_theme(archive_path, extract_root):
        root = os.path.realpath(extract_root)
        os.makedirs(root, exist_ok=True)
        root_prefix = root + os.sep
        with zipfile.ZipFile(archive_path, "r") as archive:
            for info in archive.infolist():
                name = info.filename.replace("\\", "/")
                parts = name.split("/")
                if name.startswith("/") or any(":" in part or part in (".", "..") for part in parts):
                    raise ValueError("The theme archive contains an unsafe path: " + info.filename)
                mode = (info.external_attr >> 16) & 0xFFFF
                if mode and (mode & 0o170000) == 0o120000:
                    raise ValueError("The theme archive contains a symbolic link: " + info.filename)
                destination = os.path.realpath(os.path.join(root, *parts))
                if destination != root and not destination.startswith(root_prefix):
                    raise ValueError("The theme archive path escapes the extraction directory: " + info.filename)
                if info.is_dir():
                    os.makedirs(destination, exist_ok=True)
                    continue
                os.makedirs(os.path.dirname(destination), exist_ok=True)
                with archive.open(info, "r") as source, open(destination, "wb") as target:
                    while True:
                        chunk = source.read(1024 * 1024)
                        if not chunk:
                            break
                        target.write(chunk)

    def _map_files(self):
        paths = [item.strip().strip('"') for item in self.maps_edit.text().split(";") if item.strip()]
        if not paths:
            raise ValueError("Select at least one .map file.")
        missing = [path for path in paths if not os.path.isfile(path) or not path.lower().endswith(".map")]
        if missing:
            raise FileNotFoundError("One or more .map files are missing or have an invalid extension:\n" + "\n".join(missing))
        return paths

    def _profile_fields(self, profile_id, theme_path, maps, title):
        excluded = set()
        categories = self._categories_for_theme(theme_path)
        if self.mode_combo.currentData():
            excluded.update(categories["areas"] - categories["water"])
        if not self.water_check.isChecked():
            excluded.update(categories["water"])
        if not self.contours_check.isChecked():
            excluded.update(categories["contours"])
            excluded.update(categories["hatch"])
        return {
            "profileId": profile_id,
            "title": title,
            "mapFiles": ";".join(maps),
            "themePath": theme_path,
            "transparent": "true" if self.mode_combo.currentData() else "false",
            "renderLabels": "true" if self.labels_check.isChecked() else "false",
            "excludeCats": ",".join(sorted(excluded)),
            "tileSize": "256",
        }

    def _categories_for_theme(self, theme_path):
        cached = self.theme_categories.get(theme_path)
        if cached is not None:
            return cached
        categories = {"areas": set(), "water": set(), "contours": set(), "hatch": set()}
        document = ElementTree.parse(theme_path)

        def visit(node, active_category=None, active_keys=()):
            tag = node.tag.rsplit("}", 1)[-1]
            category = node.attrib.get("cat", active_category) if tag == "rule" else active_category
            keys = set(active_keys)
            if tag == "rule" and node.attrib.get("k"):
                keys.update(part.strip().lower() for part in node.attrib["k"].split("|") if part.strip())
            if category:
                normalized = category.lower()
                if tag == "area":
                    categories["areas"].add(category)
                if (any(term in normalized for term in ("water", "river", "lake", "sea", "ocean", "stream"))
                        or keys.intersection({"water", "waterway", "natural", "landuse", "wetland"})
                        and any(term in " ".join(keys) for term in ("water", "river", "lake", "sea", "ocean", "stream", "wetland"))):
                    categories["water"].add(category)
                if "contour" in normalized or normalized == "cw_ext":
                    categories["contours"].add(category)
                if any(term in normalized for term in ("hatch", "reserve", "baohu")):
                    categories["hatch"].add(category)
            for child in node:
                visit(child, category, keys)

        visit(document.getroot())
        self.theme_categories[theme_path] = categories
        return categories

    def _selected_layer_profile(self):
        layer = self.layer_data.get(self.layer_combo.currentData())
        match = re.search(r"/tiles/([^/]+)/", layer.source()) if layer else None
        if not layer or not match:
            return None, None
        return layer, match.group(1)

    def _post_profile(self, fields):
        response = self._request_json(self.server_base + "/api/profiles", fields)
        if response.get("error"):
            raise RuntimeError(response["error"])
        return response

    def _tile_uri(self, profile_id, cache_key):
        tile_url = "{}/tiles/{}/{}/{}/{}.png?rev={}".format(
            self.server_base, profile_id, "{z}", "{x}", "{y}", cache_key
        )
        return "type=xyz&url={}&zmin=0&zmax=18".format(tile_url)

    @staticmethod
    def _add_layer_at_role(layer, overlay):
        project = QgsProject.instance()
        project.addMapLayer(layer, False)
        root = project.layerTreeRoot()
        index = layer_tree_insert_index(len(root.children()), overlay)
        root.insertLayer(index, layer)

    def _add_vector_layer(self):
        try:
            kind = self.vector_kind_combo.currentData()
            source = self.vector_source_edit.text().strip()
            style_url = self.vector_style_edit.text().strip()
            title = self.vector_name_edit.text().strip() or "Vector Tiles"
            if kind == "style":
                uri = vector_xyz_uri(source, style_url)
            elif kind == "xyz":
                uri = vector_xyz_uri(source, style_url)
            else:
                uri = vector_mbtiles_uri(source, style_url)
            layer = QgsVectorTileLayer(uri, title)
            if not layer.isValid():
                raise RuntimeError("QGIS could not open this vector-tile source. Check the URL, style, and network access.")
            attribution = self.vector_attribution_edit.text().strip()
            if attribution:
                layer.setAbstract("Source attribution: " + attribution)
            overlay = bool(self.vector_role_combo.currentData())
            layer.setOpacity(self.vector_opacity_slider.value() / 100.0 if overlay else 1.0)
            self._add_layer_at_role(layer, overlay)
            layer.triggerRepaint()
            self.iface.mapCanvas().refresh()
        except Exception as exc:
            QMessageBox.critical(self.dock, "Could Not Add Vector Tiles", str(exc))

    def _add_raster_layer(self):
        try:
            url = self.raster_url_edit.text().strip()
            uri = raster_xyz_uri(url)
            title = self.raster_name_edit.text().strip() or "XYZ Basemap"
            layer = QgsRasterLayer(uri, title, "wms")
            if not layer.isValid():
                raise RuntimeError("QGIS could not create this XYZ layer. Check the URL and network access.")
            attribution = self.raster_attribution_edit.text().strip()
            if attribution:
                layer.setAbstract("Source attribution: " + attribution)
            overlay = bool(self.raster_role_combo.currentData())
            layer.setOpacity(self.raster_opacity_slider.value() / 100.0 if overlay else 1.0)
            self._add_layer_at_role(layer, overlay)
            layer.triggerRepaint()
            self.iface.mapCanvas().refresh()
        except Exception as exc:
            QMessageBox.critical(self.dock, "Could Not Add Raster Basemap", str(exc))

    def _apply_to_selected(self):
        layer, profile_id = self._selected_layer_profile()
        if not layer:
            QMessageBox.information(self.dock, "Locus Map Control", "The current project has no local Mapsforge layers to manage. Select a .map file and add it as a new layer first.")
            return
        try:
            maps = self._map_files()
            theme_path = self._resolve_theme()
            title = self.title_edit.text().strip() or layer.name()
            profile = self._post_profile(self._profile_fields(profile_id, theme_path, maps, title))
            layer.setDataSource(
                self._tile_uri(profile_id, profile.get("cacheKey", uuid.uuid4().hex[:12])),
                title,
                "wms",
                QgsDataProvider.ProviderOptions(),
            )
            is_overlay = bool(self.mode_combo.currentData())
            layer.setOpacity(self.opacity_slider.value() / 100.0 if is_overlay else 1.0)
            self._place_layer(layer, is_overlay)
            layer.triggerRepaint()
            self.iface.mapCanvas().refresh()
            self._refresh_layers(layer.id())
        except Exception as exc:
            QMessageBox.critical(self.dock, "Could Not Apply Map Settings", str(exc))

    def _add_layer(self):
        try:
            maps = self._map_files()
            theme_path = self._resolve_theme()
            title = self.title_edit.text().strip() or os.path.basename(maps[0])
            slug = re.sub(r"[^a-z0-9]+", "-", os.path.splitext(os.path.basename(maps[0]))[0].lower()).strip("-")
            profile_id = "locus-{}-{}".format(slug or "map", uuid.uuid4().hex[:8])
            profile = self._post_profile(self._profile_fields(profile_id, theme_path, maps, title))
            layer = QgsRasterLayer(
                self._tile_uri(profile_id, profile.get("cacheKey", uuid.uuid4().hex[:12])),
                title,
                "wms",
            )
            if not layer.isValid():
                raise RuntimeError("QGIS could not create the XYZ layer. The local service profile was registered, but no layer was added to the project.")
            is_overlay = bool(self.mode_combo.currentData())
            layer.setOpacity(self.opacity_slider.value() / 100.0 if is_overlay else 1.0)
            project = QgsProject.instance()
            project.addMapLayer(layer, False)
            root = project.layerTreeRoot()
            insert_at = layer_tree_insert_index(len(root.children()), is_overlay)
            root.insertLayer(insert_at, layer)
            layer.triggerRepaint()
            self.iface.mapCanvas().refresh()
            self._refresh_layers(layer.id())
        except Exception as exc:
            QMessageBox.critical(self.dock, "Could Not Add Offline Map", str(exc))

    def _place_layer(self, layer, overlay):
        root = QgsProject.instance().layerTreeRoot()
        node = root.findLayer(layer.id())
        if node is None:
            return
        parent = node.parent()
        clone = node.clone()
        parent.removeChildNode(node)
        if parent == root:
            index = layer_tree_insert_index(len(root.children()), overlay)
        else:
            index = layer_tree_insert_index(len(parent.children()), overlay)
        parent.insertChildNode(index, clone)

    @staticmethod
    def _discover_local_maps():
        candidate_roots = [
            r"D:\AIAssistant\GeoAI地理空间地图创作智能体\data\_inbox\locus\mapsVector",
            r"D:\AIAssistant\GeoAI地理空间地图创作智能体\data\_inbox",
            r"D:\手机相册\手机S25\Locus\mapsVector",
            r"D:\手机\手机S25\Locus\mapsVector",
        ]
        found = {}
        for d in candidate_roots:
            if os.path.exists(d):
                for root_dir, _dirs, files in os.walk(d):
                    for f in files:
                        if f.lower().endswith(".map"):
                            full = os.path.normpath(os.path.join(root_dir, f))
                            if f not in found and os.path.isfile(full):
                                found[f] = full

        curated = [
            ("Ch-Yunnan_oam.osm.map", "⭐ Yunnan (OpenAndroMaps, 1.1 GB — Contours+Roads)"),
            ("yunnan.osm.map", "⭐ Yunnan Official Vector (566 MB — Full Roads+POIs)"),
            ("China-South_oam.osm.map", "⭐ South / Southwest China (2.0 GB — Contours+Roads)"),
            ("zhushou-net-cn221029.map", "⭐ China Outdoor Trails (1.4 GB — SixFeet/Zhushou)"),
            ("01-cn+tw+np240911a.s.osm.map", "⭐ China+Taiwan National (1.8 GB)"),
            ("yunnan-latest.osm.map", "⭐ Yunnan Latest OSM (73 MB)"),
            ("N25E102.map", "⭐ Kunming Dianchi Tile N25E102 (143 MB)"),
            ("N26E100.map", "⭐ Lijiang / Dali Tile N26E100 (128 MB)"),
            ("N24E102.map", "⭐ Yuxi / South Dianchi Tile N24E102 (132 MB)"),
        ]

        result = []
        added_files = set()
        for filename, label in curated:
            if filename in found:
                result.append((label, found[filename]))
                added_files.add(filename)

        for filename in sorted(found.keys()):
            if filename not in added_files:
                size_mb = os.path.getsize(found[filename]) / (1024 * 1024)
                label = "{} ({:.0f} MB)".format(filename, size_mb)
                result.append((label, found[filename]))

        return result

    def _select_preset_map(self):
        path = self.maps_combo.currentData()
        if path:
            self.maps_edit.setText(path)
            if not self.title_edit.text().strip() or self.title_edit.text().startswith("Mapsforge -"):
                base_name = os.path.splitext(os.path.basename(path))[0]
                self.title_edit.setText("Mapsforge - " + base_name)

    def _on_golden_vector_opacity_changed(self, value):
        self.golden_vector_opacity_label.setText("{}%".format(value))
        project = QgsProject.instance()
        for layer in project.mapLayers().values():
            if "Mapsforge Vector" in layer.name():
                layer.setOpacity(value / 100.0)
                layer.triggerRepaint()
        self.iface.mapCanvas().refresh()

    def _on_golden_topo_opacity_changed(self, value):
        self.golden_topo_opacity_label.setText("{}%".format(value))
        project = QgsProject.instance()
        for layer in project.mapLayers().values():
            if "Topo Contours & Relief" in layer.name() or "OpenTopoMap" in layer.name():
                layer.setOpacity(value / 100.0)
                layer.triggerRepaint()
        self.iface.mapCanvas().refresh()

    def _change_opacity(self, value):
        self.opacity_value.setText("{}%".format(value))
        layer, _profile_id = self._selected_layer_profile() if hasattr(self, "layer_combo") else (None, None)
        if layer:
            layer.setOpacity(value / 100.0)
            layer.triggerRepaint()
            self.iface.mapCanvas().refresh()

    def _load_golden_stack(self):
        try:
            project = QgsProject.instance()
            root = project.layerTreeRoot()

            # Remove existing golden stack layers to avoid duplicate stacking
            for lid, lyr in list(project.mapLayers().items()):
                if any(k in lyr.name() for k in ["1. Satellite Basemap", "2. Topo Contours & Relief", "3. Mapsforge Vector Trails"]):
                    project.removeMapLayer(lid)

            # 1. Base Layer: Satellite Imagery (inserted at bottom)
            sat_preset = self.golden_sat_combo.currentText()
            sat_url, sat_attr = RASTER_PRESETS.get(sat_preset, RASTER_PRESETS["Esri World Imagery"])
            sat_layer = QgsRasterLayer(raster_xyz_uri(sat_url), "1. Satellite Basemap", "wms")
            if sat_layer.isValid():
                project.addMapLayer(sat_layer, False)
                root.insertLayer(len(root.children()), sat_layer)

            # 2. Middle Layer: Topo Relief & Contours (OpenTopoMap)
            if self.golden_topo_check.isChecked():
                topo_url, topo_attr = RASTER_PRESETS["OpenTopoMap"]
                topo_layer = QgsRasterLayer(raster_xyz_uri(topo_url), "2. Topo Contours & Relief (OpenTopoMap)", "wms")
                if topo_layer.isValid():
                    topo_opacity = self.golden_topo_opacity_slider.value() / 100.0
                    topo_layer.setOpacity(topo_opacity)
                    project.addMapLayer(topo_layer, False)
                    mid_index = max(0, len(root.children()) - 1)
                    root.insertLayer(mid_index, topo_layer)

            # 3. Top Layer: Offline Mapsforge Vector Trails (Transparent Overlay or selected style)
            if self.golden_mapsforge_check.isChecked():
                chosen_map = self.golden_map_combo.currentData()
                maps = [chosen_map] if chosen_map and os.path.isfile(chosen_map) else []
                if not maps:
                    try:
                        maps = self._map_files()
                    except Exception:
                        pass

                style_file = self.golden_style_combo.currentData() or "LocusSat.xml"
                theme_path = os.path.join(os.path.dirname(__file__), "styles", style_file)
                if not os.path.isfile(theme_path):
                    theme_path = os.path.join(os.path.dirname(__file__), "styles", "LocusSat.xml")

                is_transparent = (style_file != "OutdoorFull.xml")
                style_title = os.path.splitext(style_file)[0]
                map_name = os.path.splitext(os.path.basename(maps[0]))[0] if maps else "Service"
                title = "3. Mapsforge Vector Trails [{} - {}]".format(map_name, style_title)

                mf_layer = None
                if maps:
                    slug = re.sub(r"[^a-z0-9]+", "-", map_name.lower()).strip("-")
                    profile_id = "locus-golden-{}-{}".format(slug or "map", uuid.uuid4().hex[:6])

                    excluded = set()
                    try:
                        categories = self._categories_for_theme(theme_path)
                        if is_transparent:
                            excluded.update(categories.get("areas", set()) - categories.get("water", set()))
                    except Exception:
                        pass

                    fields = {
                        "profileId": profile_id,
                        "title": title,
                        "mapFiles": ";".join(maps),
                        "themePath": theme_path,
                        "transparent": "true" if is_transparent else "false",
                        "renderLabels": "true",
                        "excludeCats": ",".join(sorted(excluded)),
                        "tileSize": "256",
                    }
                    profile = self._post_profile(fields)
                    mf_layer = QgsRasterLayer(
                        self._tile_uri(profile_id, profile.get("cacheKey", uuid.uuid4().hex[:12])),
                        title,
                        "wms",
                    )
                elif self.profile_data:
                    pid = list(self.profile_data.keys())[0]
                    prof = self.profile_data[pid]
                    title = "3. Mapsforge Vector Trails [{}]".format(prof.get("title", pid))
                    mf_layer = QgsRasterLayer(
                        self._tile_uri(pid, prof.get("cacheKey", "v1")),
                        title,
                        "wms"
                    )

                if mf_layer and mf_layer.isValid():
                    vector_opacity = self.golden_vector_opacity_slider.value() / 100.0
                    mf_layer.setOpacity(vector_opacity)
                    project.addMapLayer(mf_layer, False)
                    root.insertLayer(0, mf_layer)

            self.iface.mapCanvas().refresh()
            self._refresh_layers()
            QMessageBox.information(
                self.dock,
                "Golden Stack Loaded",
                "Successfully loaded the Golden Three-Layer Stack into QGIS:\n\n"
                "• [Bottom] Satellite Basemap (Esri World Imagery)\n"
                "• [Middle] Topo Contours & Relief ({:.0f}% opacity)\n"
                "• [Top] Mapsforge Vector Trails [{} - {}] ({:.0f}% opacity)\n\n"
                "You can freely adjust layer opacity in real time using the sliders on the Golden Stack panel.".format(
                    self.golden_topo_opacity_slider.value(),
                    map_name,
                    style_title,
                    self.golden_vector_opacity_slider.value()
                )
            )
        except Exception as exc:
            QMessageBox.critical(self.dock, "Could Not Load Golden Stack", str(exc))

