# Third-party notices

This plugin package includes the following unmodified runtime libraries alongside the Mapsforge renderer. Their upstream licenses remain in force.

| Component | Version | License | Upstream |
|---|---:|---|---|
| Mapsforge core, map, map-awt, map-reader, themes | 0.25.0 | LGPL-3.0 with Mapsforge's stated additional permission | [mapsforge/mapsforge](https://github.com/mapsforge/mapsforge) |
| kxml2 | 2.3.0 | MIT | [kobjects/kxml2](https://github.com/kobjects/kxml2) |
| SVG Salamander | 1.1.3 | BSD | [blackears/svgSalamander](https://github.com/blackears/svgSalamander) |

License texts are included in `licenses/`: `LGPL-3.0.txt`, `GPL-3.0.txt`, `MIT-kxml2.txt`, and `BSD-svgSalamander.txt`. The Mapsforge renderer source distributed here is in `src/org/mapsforge/map/layer/renderer/MapsforgeTileServer.java`.

The offline `.map` datasets and custom Mapsforge theme files selected by users are not bundled. Map data may have separate attribution and redistribution terms; preserve its required attribution.
