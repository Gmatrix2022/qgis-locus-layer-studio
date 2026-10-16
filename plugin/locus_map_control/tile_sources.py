from urllib.parse import quote, urlsplit


RASTER_PRESETS = {
    "Google Satellite (Locus Custom Proxy)": (
        "https://khms0.googleapis.com/kh?v=1000&hl=zh-CN&x={x}&y={y}&z={z}",
        "Google, Maxar Technologies",
    ),
    "Google Satellite (Standard XYZ)": (
        "https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
        "Google Maps",
    ),
    "Google Hybrid (Satellite + Roads/Labels)": (
        "https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}",
        "Google Maps",
    ),
    "Esri World Imagery": (
        "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        "Esri, Maxar, Earthstar Geographics, and the GIS User Community",
    ),
    "OpenTopoMap": (
        "https://tile.opentopomap.org/{z}/{x}/{y}.png",
        "OpenTopoMap; map data © OpenStreetMap contributors; SRTM",
    ),
    "OpenStreetMap Standard": (
        "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        "© OpenStreetMap contributors",
    ),
}

VECTOR_STYLE_PRESETS = {
    "OpenFreeMap — Liberty": (
        "https://tiles.openfreemap.org/planet/{z}/{x}/{y}.pbf",
        "https://tiles.openfreemap.org/styles/liberty",
        "© OpenMapTiles; data from © OpenStreetMap contributors",
    ),
    "OpenFreeMap — Bright": (
        "https://tiles.openfreemap.org/planet/{z}/{x}/{y}.pbf",
        "https://tiles.openfreemap.org/styles/bright",
        "© OpenMapTiles; data from © OpenStreetMap contributors",
    ),
    "OpenFreeMap — Positron": (
        "https://tiles.openfreemap.org/planet/{z}/{x}/{y}.pbf",
        "https://tiles.openfreemap.org/styles/positron",
        "© OpenMapTiles; data from © OpenStreetMap contributors",
    ),
}


def _uri_value(value):
    return quote(str(value), safe=":/{}?=%")


def raster_xyz_uri(url, minimum_zoom=0, maximum_zoom=19):
    if not url.startswith(("http://", "https://")):
        raise ValueError("An XYZ tile URL must start with http:// or https://.")
    if not all("{" + key + "}" in url for key in ("z", "x", "y")):
        raise ValueError("The XYZ URL must contain {z}, {x}, and {y} placeholders.")
    return "type=xyz&url={}&zmin={}&zmax={}".format(
        _uri_value(url), int(minimum_zoom), int(maximum_zoom)
    )


def vector_xyz_uri(url, style_url=""):
    if not url.startswith(("http://", "https://", "file://")):
        raise ValueError("A vector-tile URL must be HTTP(S) or file based.")
    if not all("{" + key + "}" in url for key in ("z", "x", "y")):
        raise ValueError("The vector-tile URL must contain {z}, {x}, and {y} placeholders.")
    suffix = urlsplit(url).path.lower()
    if suffix and not suffix.endswith((".pbf", ".mvt")):
        raise ValueError("A vector-tile XYZ URL must return PBF or MVT tiles.")
    uri = "type=xyz&url=" + _uri_value(url)
    if style_url.strip():
        uri += "&styleUrl=" + _uri_value(style_url.strip())
    return uri


def vector_mbtiles_uri(path, style_url=""):
    import os
    from pathlib import Path

    if not os.path.isfile(path) or not path.lower().endswith((".mbtiles", ".mbtile")):
        raise ValueError("Select an existing .mbtiles file.")
    uri = "type=mbtiles&url=" + _uri_value(Path(path).resolve().as_uri())
    if style_url.strip():
        uri += "&styleUrl=" + _uri_value(style_url.strip())
    return uri
