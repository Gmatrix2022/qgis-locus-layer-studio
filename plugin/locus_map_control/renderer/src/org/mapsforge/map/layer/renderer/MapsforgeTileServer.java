package org.mapsforge.map.layer.renderer;

/* SPDX-FileCopyrightText: 2026 codex@yy23
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.mapsforge.core.graphics.GraphicFactory;
import org.mapsforge.core.graphics.TileBitmap;
import org.mapsforge.core.mapelements.MapElementContainer;
import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Rectangle;
import org.mapsforge.core.model.Tag;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.awt.graphics.AwtGraphicFactory;
import org.mapsforge.map.datastore.MapDataStore;
import org.mapsforge.map.datastore.MapReadResult;
import org.mapsforge.map.datastore.MultiMapDataStore;
import org.mapsforge.map.datastore.Way;
import org.mapsforge.map.model.DisplayModel;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.reader.header.MapFileInfo;
import org.mapsforge.map.rendertheme.RenderContext;
import org.mapsforge.map.rendertheme.XmlRenderTheme;
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback;
import org.mapsforge.map.rendertheme.XmlRenderThemeStyleLayer;
import org.mapsforge.map.rendertheme.XmlThemeResourceProvider;
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes;
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * MapsforgeTileServer —— 面向 ArcGIS Pro 3.7 插件的本地离线矢量地图渲染与多源合规瓦片微服务
 *
 * 刻意声明在 org.mapsforge.map.layer.renderer 包内，以直接访问 CanvasRasterer 的包级私有方法
 * （setCanvasBitmap / fill / fillOutsideAreas / drawMapElements），彻底规避 AWT 无头模式下
 * DatabaseRenderer.executeJob 因画布未绑定位图即填充背景引发的 NullPointerException。
 *
 * 核心能力：
 * 1. 支持单 .map 与多 .map（MultiMapDataStore.DataPolicy.RETURN_ALL）跨网格无缝拼接；
 * 2. 支持内存级 XML 主题 dp 单位剥离（适配移动端 Elevate / OutdoorFull 等主题）与 excludeCats 过滤；
 * 3. 支持 .map 文件头与中心瓦片自动探针（区分 BASEMAP_WITH_CONTOUR / BASEMAP_NO_CONTOUR / CONTOUR_ONLY）；
 * 4. 确定性 SHA-256 CacheKey 隔离磁盘缓存（切换主题或 excludeCats 毫秒级切换，零脏瓦片串扰）；
 * 5. 同时暴露 XYZ (/tiles/...)、OGC WMTS 1.0.0 (/wmts/...) 与 ArcGIS MapServer REST (/arcgis/rest/services/...)；
 * 6. 在线瓦片合规代理 (/proxy/...)：内置 GCJ-02 -> WGS-84 双线性重采样纠偏、国界触发制拦截、
 *    离线断网开关（OfflineMode）与黑黄报错瓦片（tile.openmaps.fr 等）像素级防投毒。
 */
public class MapsforgeTileServer {

    private static int serverPort = 18765;
    private static File cacheRootDir;
    private static File themesRootDir;
    private static volatile boolean offlineMode = false;
    private static volatile boolean hasNationalBorderInView = false;

    private static final GraphicFactory GRAPHIC_FACTORY = AwtGraphicFactory.INSTANCE;
    private static final Map<String, RenderProfile> PROFILES = new ConcurrentHashMap<>();
    private static final Map<String, OnlineSource> ONLINE_SOURCES = new LinkedHashMap<>();
    private static final Map<String, BufferedImage> UPSTREAM_MEM_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, BufferedImage>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > 96;
                }
            });

    private static final Pattern DP_UNIT_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)dp\\b");

    public static class OnlineSource {
        public final String id;
        public final String title;
        public final String urlTemplate;
        public final boolean isGcj02;
        public final boolean isDomesticWhitelist;
        public final String attribution;

        public OnlineSource(String id, String title, String urlTemplate, boolean isGcj02, boolean isDomesticWhitelist, String attribution) {
            this.id = id;
            this.title = title;
            this.urlTemplate = urlTemplate;
            this.isGcj02 = isGcj02;
            this.isDomesticWhitelist = isDomesticWhitelist;
            this.attribution = attribution;
        }
    }

    public static class RenderProfile {
        public final String profileId;
        public final String title;
        public final List<File> mapFiles;
        public final String themeSpec;
        public final boolean transparent;
        public final boolean renderLabels;
        public final String excludeCats;
        public final int tileSize;
        public final String cacheKey;

        public final MapDataStore mapDataStore;
        public final DisplayModel displayModel;
        public final RenderThemeFuture renderThemeFuture;
        public final BoundingBox boundingBox;
        public final Object dataStoreLock = new Object();

        public RenderProfile(String profileId, String title, List<File> mapFiles, String themeSpec,
                             boolean transparent, boolean renderLabels, String excludeCats, int tileSize) throws Exception {
            this.profileId = profileId;
            this.title = title;
            this.mapFiles = new ArrayList<>(mapFiles);
            this.themeSpec = themeSpec;
            this.transparent = transparent;
            this.renderLabels = renderLabels;
            this.excludeCats = excludeCats == null ? "" : excludeCats.trim();
            this.tileSize = tileSize;
            this.cacheKey = computeCacheKey(this.mapFiles, this.themeSpec, this.excludeCats, this.transparent, this.tileSize);

            this.displayModel = new DisplayModel();
            if (this.mapFiles.size() == 1) {
                this.mapDataStore = new MapFile(this.mapFiles.get(0));
            } else {
                MultiMapDataStore multi = new MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL);
                for (int i = 0; i < this.mapFiles.size(); i++) {
                    MapFile mf = new MapFile(this.mapFiles.get(i));
                    multi.addMapDataStore(mf, i == 0, i == 0);
                }
                this.mapDataStore = multi;
            }
            this.boundingBox = this.mapDataStore.boundingBox();
            XmlRenderTheme xmlTheme = resolveThemeWithDpStrip(this.themeSpec, this.excludeCats);
            this.renderThemeFuture = new RenderThemeFuture(GRAPHIC_FACTORY, xmlTheme, this.displayModel);
            Thread t = new Thread(this.renderThemeFuture, "ThemeCompiler-" + profileId);
            t.setDaemon(true);
            t.start();
            this.renderThemeFuture.get();
        }

        public void close() {
            try {
                this.mapDataStore.close();
            } catch (Exception ignored) {
            }
        }
    }

    static {
        ONLINE_SOURCES.put("amap_sat", new OnlineSource(
                "amap_sat",
                "高德卫星影像 (GCJ-02 实时纠偏至 WGS-84)",
                "https://webst01.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}",
                true, true, "© 高德地图 AutoNavi (GCJ-02 -> WGS-84 实时纠偏)"));
        ONLINE_SOURCES.put("amap_road", new OnlineSource(
                "amap_road",
                "高德路网与注记 (GCJ-02 实时纠偏至 WGS-84)",
                "https://wprd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&style=7&x={x}&y={y}&z={z}",
                true, true, "© 高德地图 AutoNavi (GCJ-02 -> WGS-84 实时纠偏)"));
        ONLINE_SOURCES.put("amap_label_only", new OnlineSource(
                "amap_label_only",
                "高德纯路网透明叠置层 (style=8, GCJ-02 纠偏)",
                "https://wprd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&style=8&x={x}&y={y}&z={z}",
                true, true, "© 高德地图 AutoNavi (GCJ-02 -> WGS-84 实时纠偏)"));
        ONLINE_SOURCES.put("esri_imagery", new OnlineSource(
                "esri_imagery",
                "Esri World Imagery 全球卫星影像 (WGS-84, 纯影像无国界)",
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
                false, true, "Source: Esri, Maxar, Earthstar Geographics"));
        ONLINE_SOURCES.put("opentopomap", new OnlineSource(
                "opentopomap",
                "OpenTopoMap 官方地形图 (仅限局部无国界区域)",
                "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
                false, false, "© OpenTopoMap (CC-BY-SA) · © OpenStreetMap contributors"));
        ONLINE_SOURCES.put("osm_standard", new OnlineSource(
                "osm_standard",
                "OpenStreetMap 标准底图 (仅限局部无国界区域)",
                "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
                false, false, "© OpenStreetMap contributors (ODbL)"));
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isEmpty()) {
            localAppData = System.getProperty("user.home") + File.separator + "AppData" + File.separator + "Local";
        }
        cacheRootDir = new File(localAppData, "GeoAI\\ArcGISProBasemapAddin\\TileCache");
        themesRootDir = new File("themes");

        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                serverPort = Integer.parseInt(args[++i]);
            } else if ("--cache-dir".equals(args[i]) && i + 1 < args.length) {
                cacheRootDir = new File(args[++i]);
            } else if ("--themes-dir".equals(args[i]) && i + 1 < args.length) {
                themesRootDir = new File(args[++i]);
            } else if ("--offline".equals(args[i])) {
                offlineMode = true;
            }
        }

        if (!cacheRootDir.exists()) {
            cacheRootDir.mkdirs();
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", serverPort), 64);
        server.setExecutor(Executors.newFixedThreadPool(8));

        server.createContext("/health", new HealthHandler());
        server.createContext("/api/profiles", new ProfilesHandler());
        server.createContext("/api/probe", new ProbeHandler());
        server.createContext("/api/mode", new ModeHandler());
        server.createContext("/tiles/", new XyzTileHandler());
        server.createContext("/wmts/", new WmtsHandler());
        server.createContext("/arcgis/rest/services/", new ArcGisMapServerHandler());
        server.createContext("/proxy/", new ProxyTileHandler());

        server.start();
        System.out.printf(Locale.ROOT,
                "[MapsforgeTileServer] Listening on http://127.0.0.1:%d | Cache=%s | Themes=%s%n",
                serverPort, cacheRootDir.getAbsolutePath(), themesRootDir.getAbsolutePath());
    }

    // =========================================================================
    // 1. Health & Mode & Probe & Profile Management Handlers
    // =========================================================================

    private static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"status\":\"ok\",\"engine\":\"MapsforgeTileServer/0.25.0\",\"port\":").append(serverPort)
              .append(",\"offlineMode\":").append(offlineMode)
              .append(",\"hasNationalBorderInView\":").append(hasNationalBorderInView)
              .append(",\"cacheDir\":\"").append(jsonEscape(cacheRootDir.getAbsolutePath())).append("\"")
              .append(",\"profileCount\":").append(PROFILES.size())
              .append(",\"profiles\":[");
            boolean first = true;
            for (String pid : PROFILES.keySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(jsonEscape(pid)).append("\"");
            }
            sb.append("]}");
            sendJson(ex, 200, sb.toString());
        }
    }

    private static class ModeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            Map<String, String> params = parseParams(ex);
            if (params.containsKey("offlineMode")) {
                offlineMode = Boolean.parseBoolean(params.get("offlineMode"));
            }
            if (params.containsKey("hasNationalBorderInView")) {
                hasNationalBorderInView = Boolean.parseBoolean(params.get("hasNationalBorderInView"));
            }
            String json = String.format(Locale.ROOT,
                    "{\"status\":\"ok\",\"offlineMode\":%b,\"hasNationalBorderInView\":%b}",
                    offlineMode, hasNationalBorderInView);
            sendJson(ex, 200, json);
        }
    }

    private static class ProfilesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            if ("GET".equals(method)) {
                StringBuilder sb = new StringBuilder("{\"profiles\":[");
                boolean first = true;
                for (RenderProfile p : PROFILES.values()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append(profileToJson(p));
                }
                sb.append("],\"onlineSources\":[");
                first = true;
                for (OnlineSource s : ONLINE_SOURCES.values()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{\"id\":\"").append(jsonEscape(s.id)).append("\"")
                      .append(",\"title\":\"").append(jsonEscape(s.title)).append("\"")
                      .append(",\"isGcj02\":").append(s.isGcj02)
                      .append(",\"isDomesticWhitelist\":").append(s.isDomesticWhitelist)
                      .append(",\"attribution\":\"").append(jsonEscape(s.attribution)).append("\"")
                      .append(",\"xyzUrl\":\"http://127.0.0.1:").append(serverPort).append("/proxy/").append(s.id).append("/{level}/{col}/{row}.png\"")
                      .append(",\"mapServerUrl\":\"http://127.0.0.1:").append(serverPort).append("/arcgis/rest/services/proxy_").append(s.id).append("/MapServer\"")
                      .append("}");
                }
                sb.append("]}");
                sendJson(ex, 200, sb.toString());
                return;
            }

            if ("POST".equals(method)) {
                try {
                    Map<String, String> params = parseParams(ex);
                    String profileId = params.getOrDefault("profileId", "").trim();
                    String title = params.getOrDefault("title", profileId).trim();
                    String mapFilesRaw = params.getOrDefault("mapFiles", "").trim();
                    String themePath = params.getOrDefault("themePath", "builtin:OSMARENDER").trim();
                    boolean transparent = Boolean.parseBoolean(params.getOrDefault("transparent", "true"));
                    boolean renderLabels = Boolean.parseBoolean(params.getOrDefault("renderLabels", "true"));
                    String excludeCats = params.getOrDefault("excludeCats", "").trim();
                    int tileSize = Integer.parseInt(params.getOrDefault("tileSize", "256"));

                    if (profileId.isEmpty() || mapFilesRaw.isEmpty()) {
                        sendJson(ex, 400, "{\"error\":\"Missing profileId or mapFiles\"}");
                        return;
                    }

                    List<File> files = new ArrayList<>();
                    for (String part : mapFilesRaw.split("[;|]")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty()) {
                            File f = new File(trimmed);
                            if (!f.isFile()) {
                                sendJson(ex, 400, "{\"error\":\"Map file not found: " + jsonEscape(trimmed) + "\"}");
                                return;
                            }
                            files.add(f);
                        }
                    }
                    if (files.isEmpty()) {
                        sendJson(ex, 400, "{\"error\":\"No valid map files specified\"}");
                        return;
                    }

                    RenderProfile newProfile = new RenderProfile(
                            profileId, title, files, themePath, transparent, renderLabels, excludeCats, tileSize);
                    RenderProfile old = PROFILES.put(profileId, newProfile);
                    if (old != null) {
                        old.close();
                    }
                    sendJson(ex, 200, profileToJson(newProfile));
                } catch (Exception e) {
                    e.printStackTrace();
                    sendJson(ex, 500, "{\"error\":\"" + jsonEscape(e.toString()) + "\"}");
                }
                return;
            }
            sendJson(ex, 405, "{\"error\":\"Method not allowed\"}");
        }
    }

    private static String profileToJson(RenderProfile p) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"profileId\":\"").append(jsonEscape(p.profileId)).append("\"")
          .append(",\"title\":\"").append(jsonEscape(p.title)).append("\"")
          .append(",\"themeSpec\":\"").append(jsonEscape(p.themeSpec)).append("\"")
          .append(",\"transparent\":").append(p.transparent)
          .append(",\"renderLabels\":").append(p.renderLabels)
          .append(",\"excludeCats\":\"").append(jsonEscape(p.excludeCats)).append("\"")
          .append(",\"cacheKey\":\"").append(jsonEscape(p.cacheKey)).append("\"")
          .append(",\"bbox\":{")
          .append("\"minLat\":").append(p.boundingBox.minLatitude)
          .append(",\"minLon\":").append(p.boundingBox.minLongitude)
          .append(",\"maxLat\":").append(p.boundingBox.maxLatitude)
          .append(",\"maxLon\":").append(p.boundingBox.maxLongitude)
          .append("}")
          .append(",\"mapFiles\":[");
        for (int i = 0; i < p.mapFiles.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(jsonEscape(p.mapFiles.get(i).getAbsolutePath())).append("\"");
        }
        sb.append("]")
          .append(",\"xyzUrl\":\"http://127.0.0.1:").append(serverPort).append("/tiles/").append(p.profileId).append("/{level}/{col}/{row}.png\"")
          .append(",\"wmtsUrl\":\"http://127.0.0.1:").append(serverPort).append("/wmts/").append(p.profileId).append("/1.0.0/WMTSCapabilities.xml\"")
          .append(",\"mapServerUrl\":\"http://127.0.0.1:").append(serverPort).append("/arcgis/rest/services/").append(p.profileId).append("/MapServer\"")
          .append("}");
        return sb.toString();
    }

    /**
     * GET /api/probe?map=<path>
     * 读取 .map 文件头并在中心点 z=13 抽样读取瓦片要素，自动判定：
     * - CONTOUR_ONLY：含 contour_ext 但无 highway（如 N24E102.map 等 50m 纯等高线包）
     * - BASEMAP_WITH_CONTOUR：同时含 highway 与 contour_ext（如 Ch-Yunnan_oam.osm.map）
     * - BASEMAP_NO_CONTOUR：含 highway 但无 contour_ext（如官方 yunnan.map）
     */
    private static class ProbeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            Map<String, String> params = parseParams(ex);
            String mapPath = params.getOrDefault("map", "").trim();
            if (mapPath.isEmpty()) {
                sendJson(ex, 400, "{\"error\":\"Missing map parameter\"}");
                return;
            }
            File f = new File(mapPath);
            if (!f.isFile()) {
                sendJson(ex, 404, "{\"error\":\"File not found: " + jsonEscape(mapPath) + "\"}");
                return;
            }
            MapFile mf = null;
            try {
                mf = new MapFile(f);
                MapFileInfo info = mf.getMapFileInfo();
                BoundingBox bb = mf.boundingBox();
                LatLong center = bb.getCenterPoint();

                boolean headerHasHighway = false;
                boolean headerHasContour = false;
                if (info.wayTags != null) {
                    for (Tag t : info.wayTags) {
                        if ("highway".equals(t.key)) headerHasHighway = true;
                        if ("contour_ext".equals(t.key) || "ele".equals(t.key)) headerHasContour = true;
                    }
                }

                byte sampleZ = 13;
                int centerTileX = MercatorProjection.longitudeToTileX(center.longitude, sampleZ);
                int centerTileY = MercatorProjection.latitudeToTileY(center.latitude, sampleZ);
                int highwayCount = 0;
                int contourCount = 0;
                int waterCount = 0;
                int poiCount = 0;

                // 扫描中心 3x3 瓦片防中心恰巧为空白区
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        Tile sampleTile = new Tile(centerTileX + dx, centerTileY + dy, sampleZ, 256);
                        if (!mf.supportsTile(sampleTile)) continue;
                        MapReadResult res = mf.readMapData(sampleTile);
                        if (res == null) continue;
                        if (res.pois != null) poiCount += res.pois.size();
                        if (res.ways != null) {
                            for (Way w : res.ways) {
                                if (w.tags == null) continue;
                                for (Tag tag : w.tags) {
                                    if ("highway".equals(tag.key)) highwayCount++;
                                    else if ("contour_ext".equals(tag.key)) contourCount++;
                                    else if ("waterway".equals(tag.key) || ("natural".equals(tag.key) && "water".equals(tag.value))) waterCount++;
                                }
                            }
                        }
                    }
                }

                boolean hasHighway = highwayCount > 0 || headerHasHighway;
                boolean hasContour = contourCount > 0 || headerHasContour;
                String classification;
                String recommendedThemes;
                if (hasContour && !hasHighway) {
                    classification = "CONTOUR_ONLY";
                    recommendedThemes = "[\"ContourSparse.xml\",\"ContourOnly.xml\"]";
                } else if (hasHighway && hasContour) {
                    classification = "BASEMAP_WITH_CONTOUR";
                    recommendedThemes = "[\"LocusSat.xml\",\"OutdoorFull.xml\",\"ContourSparse.xml\",\"ContourOnly.xml\"]";
                } else {
                    classification = "BASEMAP_NO_CONTOUR";
                    recommendedThemes = "[\"LocusSat.xml\",\"OutdoorFull.xml\"]";
                }

                String json = String.format(Locale.ROOT,
                        "{\"status\":\"ok\",\"file\":\"%s\",\"fileSize\":%d,\"classification\":\"%s\"," +
                        "\"headerHasHighway\":%b,\"headerHasContour\":%b," +
                        "\"sampleZ\":%d,\"sampleCounts\":{\"highways\":%d,\"contours\":%d,\"water\":%d,\"pois\":%d}," +
                        "\"bbox\":{\"minLat\":%.6f,\"minLon\":%.6f,\"maxLat\":%.6f,\"maxLon\":%.6f}," +
                        "\"center\":{\"lat\":%.6f,\"lon\":%.6f}," +
                        "\"recommendedThemes\":%s}",
                        jsonEscape(f.getAbsolutePath()), f.length(), classification,
                        headerHasHighway, headerHasContour,
                        sampleZ, highwayCount, contourCount, waterCount, poiCount,
                        bb.minLatitude, bb.minLongitude, bb.maxLatitude, bb.maxLongitude,
                        center.latitude, center.longitude,
                        recommendedThemes);
                sendJson(ex, 200, json);
            } catch (Exception e) {
                sendJson(ex, 500, "{\"error\":\"" + jsonEscape(e.toString()) + "\"}");
            } finally {
                if (mf != null) {
                    try { mf.close(); } catch (Exception ignored) {}
                }
            }
        }
    }

    // =========================================================================
    // 2. XYZ Tile Handler (/tiles/{profileId}/{z}/{x}/{y}.png)
    // =========================================================================

    private static class XyzTileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            // Path: /tiles/{profileId}/{z}/{x}/{y}.png
            String path = ex.getRequestURI().getPath();
            String[] parts = path.split("/");
            if (parts.length < 6) {
                sendText(ex, 400, "Invalid XYZ tile path");
                return;
            }
            String profileId = parts[2];
            int z = parseIntSafe(parts[3], -1);
            int x = parseIntSafe(parts[4], -1);
            String yRaw = parts[5].replace(".png", "");
            int y = parseIntSafe(yRaw, -1);

            serveProfileTile(ex, profileId, z, x, y);
        }
    }

    // =========================================================================
    // 3. OGC WMTS 1.0.0 Handler (/wmts/{profileId}/1.0.0/WMTSCapabilities.xml)
    // =========================================================================

    private static class WmtsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String[] parts = path.split("/");
            if (parts.length < 4) {
                sendText(ex, 400, "Invalid WMTS path");
                return;
            }
            String profileId = parts[2];
            if (path.endsWith("WMTSCapabilities.xml") || ex.getRequestURI().getQuery() != null && ex.getRequestURI().getQuery().contains("GetCapabilities")) {
                String title = profileId;
                BoundingBox bb = new BoundingBox(-85.0511, -180.0, 85.0511, 180.0);
                RenderProfile p = PROFILES.get(profileId);
                if (p != null) {
                    title = p.title;
                    bb = p.boundingBox;
                }
                String xml = buildWmtsCapabilitiesXml(profileId, title, bb);
                byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }
            // /wmts/{profileId}/tile/{z}/{y}/{x}.png
            if (parts.length >= 7 && "tile".equals(parts[3])) {
                int z = parseIntSafe(parts[4], -1);
                int y = parseIntSafe(parts[5], -1);
                int x = parseIntSafe(parts[6].replace(".png", ""), -1);
                serveProfileTile(ex, profileId, z, x, y);
                return;
            }
            sendText(ex, 404, "WMTS resource not found");
        }
    }

    // =========================================================================
    // 4. ArcGIS REST MapServer Emulation Handler
    //    /arcgis/rest/services/{id}/MapServer
    //    /arcgis/rest/services/{id}/MapServer/tile/{z}/{y}/{x}
    // =========================================================================

    private static class ArcGisMapServerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            // /arcgis/rest/services/{serviceId}/MapServer...
            String prefix = "/arcgis/rest/services/";
            if (!path.startsWith(prefix)) {
                sendJson(ex, 404, "{\"error\":\"Invalid path\"}");
                return;
            }
            String rest = path.substring(prefix.length());
            String[] parts = rest.split("/");
            if (parts.length < 2 || !"MapServer".equalsIgnoreCase(parts[1])) {
                sendJson(ex, 404, "{\"error\":\"Not a MapServer endpoint\"}");
                return;
            }
            String serviceId = parts[0];

            // Check if this is a tile request: /arcgis/rest/services/{serviceId}/MapServer/tile/{z}/{y}/{x}
            if (parts.length >= 6 && "tile".equalsIgnoreCase(parts[2])) {
                int z = parseIntSafe(parts[3], -1);
                int y = parseIntSafe(parts[4], -1); // Esri MapServer order: row (y) first!
                int x = parseIntSafe(parts[5].replace(".png", "").replace(".jpg", ""), -1); // col (x) second!
                if (serviceId.startsWith("proxy_")) {
                    String onlineId = serviceId.substring("proxy_".length());
                    serveProxyTile(ex, onlineId, z, x, y, false);
                } else {
                    serveProfileTile(ex, serviceId, z, x, y);
                }
                return;
            }

            // Metadata request
            String title = serviceId;
            String copyright = "© OpenStreetMap contributors (ODbL) · GeoAI MapsforgeTileServer";
            BoundingBox bb = new BoundingBox(18.0, 96.0, 30.0, 108.0);
            if (serviceId.startsWith("proxy_")) {
                String onlineId = serviceId.substring("proxy_".length());
                OnlineSource os = ONLINE_SOURCES.get(onlineId);
                if (os == null) {
                    sendJson(ex, 404, "{\"error\":{\"code\":404,\"message\":\"Online source not found\"}}");
                    return;
                }
                title = os.title;
                copyright = os.attribution;
            } else {
                RenderProfile p = PROFILES.get(serviceId);
                if (p != null) {
                    title = p.title;
                    bb = p.boundingBox;
                }
            }
            String json = buildArcGisMapServerJson(serviceId, title, copyright, bb);
            sendJson(ex, 200, json);
        }
    }

    // =========================================================================
    // 5. Core Offline Tile Rendering & Disk Cache
    // =========================================================================

    private static void serveProfileTile(HttpExchange ex, String profileId, int z, int x, int y) throws IOException {
        RenderProfile profile = PROFILES.get(profileId);
        if (profile == null) {
            sendText(ex, 404, "Unknown profile: " + profileId);
            return;
        }
        if (z < 0 || z > 22 || x < 0 || y < 0) {
            sendText(ex, 400, "Invalid tile coordinates");
            return;
        }

        File cachedFile = new File(cacheRootDir,
                profile.cacheKey + File.separator + z + File.separator + x + File.separator + y + ".png");
        if (cachedFile.isFile() && cachedFile.length() > 0) {
            byte[] bytes = Files.readAllBytes(cachedFile.toPath());
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.getResponseHeaders().set("X-Tile-Cache", "HIT");
            ex.getResponseHeaders().set("X-Cache-Key", profile.cacheKey);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
            return;
        }

        byte[] pngBytes;
        try {
            pngBytes = renderSingleTileToPng(profile, (byte) z, x, y);
        } catch (Throwable t) {
            t.printStackTrace();
            pngBytes = createTransparentPng(profile.tileSize);
        }

        // Write atomically to disk cache
        try {
            File parent = cachedFile.getParentFile();
            if (!parent.isDirectory()) parent.mkdirs();
            File tmpFile = new File(parent, y + ".tmp." + Thread.currentThread().getId() + ".png");
            Files.write(tmpFile.toPath(), pngBytes);
            Files.move(tmpFile.toPath(), cachedFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }

        ex.getResponseHeaders().set("Content-Type", "image/png");
        ex.getResponseHeaders().set("X-Tile-Cache", "MISS");
        ex.getResponseHeaders().set("X-Cache-Key", profile.cacheKey);
        ex.sendResponseHeaders(200, pngBytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(pngBytes);
        }
    }

    private static byte[] renderSingleTileToPng(RenderProfile profile, byte zoom, int x, int y) throws Exception {
        Tile tile = new Tile(x, y, zoom, profile.tileSize);
        if (!profile.mapDataStore.supportsTile(tile)) {
            return createTransparentPng(profile.tileSize);
        }

        StandardRenderer standardRenderer = new StandardRenderer(
                profile.mapDataStore, GRAPHIC_FACTORY, profile.renderLabels, null);
        RendererJob job = new RendererJob(
                tile, profile.mapDataStore, profile.renderThemeFuture, profile.displayModel,
                1.0f, profile.transparent, profile.renderLabels);
        RenderContext renderContext = null;
        try {
            renderContext = new RenderContext(job, GRAPHIC_FACTORY);
            if (!standardRenderer.renderBitmap(renderContext)) {
                return createTransparentPng(profile.tileSize);
            }

            MapReadResult mapReadResult;
            synchronized (profile.dataStoreLock) {
                mapReadResult = profile.mapDataStore.readMapData(tile);
            }
            if (mapReadResult == null) {
                return createTransparentPng(profile.tileSize);
            }
            standardRenderer.processReadMapData(renderContext, mapReadResult);

            TileBitmap bitmap = GRAPHIC_FACTORY.createTileBitmap(tile.tileSize, profile.transparent);
            bitmap.setTimestamp(profile.mapDataStore.getDataTimestamp(tile));

            // 关键：先将画布绑定位图，再执行任何填充与绘制
            renderContext.canvasRasterer.setCanvasBitmap(bitmap);

            if (!profile.transparent) {
                int bg = profile.displayModel.getBackgroundColor();
                int themeBg = renderContext.renderTheme.getMapBackground();
                if (bg != themeBg) {
                    renderContext.canvasRasterer.fill(themeBg);
                }
            }

            renderContext.renderTheme.matchHillShadings(renderContext, null);
            renderContext.drawWays();

            if (profile.renderLabels) {
                List<MapElementContainer> labels = renderContext.getLabels();
                if (labels != null && !labels.isEmpty()) {
                    renderContext.canvasRasterer.drawMapElements(labels, tile);
                }
            }

            if (renderContext.renderTheme.hasMapBackgroundOutside() && profile.mapDataStore != null) {
                Rectangle rectangle = profile.mapDataStore.boundingBox().getPositionRelativeToTile(tile);
                if (profile.transparent) {
                    renderContext.canvasRasterer.fillOutsideAreas(org.mapsforge.core.graphics.Color.TRANSPARENT, rectangle);
                } else {
                    renderContext.canvasRasterer.fillOutsideAreas(renderContext.renderTheme.getMapBackgroundOutside(), rectangle);
                }
            }

            BufferedImage image = AwtGraphicFactory.getBitmap(bitmap);
            if (image == null) {
                return createTransparentPng(profile.tileSize);
            }
            // 若要求透明叠加，确保输出为 TYPE_INT_ARGB
            if (profile.transparent && image.getType() != BufferedImage.TYPE_INT_ARGB) {
                BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g2 = argb.createGraphics();
                g2.drawImage(image, 0, 0, null);
                g2.dispose();
                image = argb;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream(16384);
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } finally {
            if (renderContext != null) {
                renderContext.destroy();
            }
        }
    }

    private static byte[] createTransparentPng(int tileSize) throws IOException {
        BufferedImage img = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    // =========================================================================
    // 6. Online Tile Proxy + GCJ-02 -> WGS-84 Reprojection + Compliance Guard
    // =========================================================================

    private static class ProxyTileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            // /proxy/{sourceId}/{z}/{x}/{y}.png
            String path = ex.getRequestURI().getPath();
            String[] parts = path.split("/");
            if (parts.length < 6) {
                sendText(ex, 400, "Invalid proxy path");
                return;
            }
            String sourceId = parts[2];
            int z = parseIntSafe(parts[3], -1);
            int x = parseIntSafe(parts[4], -1);
            int y = parseIntSafe(parts[5].replace(".png", "").replace(".jpg", ""), -1);
            Map<String, String> q = parseParams(ex);
            boolean forceRaw = Boolean.parseBoolean(q.getOrDefault("raw", "false"));
            if (q.containsKey("hasBorder")) {
                hasNationalBorderInView = Boolean.parseBoolean(q.get("hasBorder"));
            }
            serveProxyTile(ex, sourceId, z, x, y, forceRaw);
        }
    }

    private static void serveProxyTile(HttpExchange ex, String sourceId, int z, int x, int y, boolean forceRaw) throws IOException {
        if (offlineMode) {
            ex.getResponseHeaders().set("X-Offline-Blocked", "true");
            sendJson(ex, 503, "{\"error\":\"OFFLINE_MODE_ACTIVE\",\"message\":\"当前处于纯离线竞赛/断网模式，已阻断所有外部网络瓦片请求\"}");
            return;
        }

        // 拦截已知污染镜像域名 tile.openmaps.fr
        if (sourceId.toLowerCase(Locale.ROOT).contains("openmaps.fr")) {
            sendJson(ex, 403, "{\"error\":\"POISONED_MIRROR_BLOCKED\",\"message\":\"已禁用 tile.openmaps.fr 镜像（返回黑黄条纹报错瓦片）\"}");
            return;
        }

        OnlineSource src = ONLINE_SOURCES.get(sourceId);
        if (src == null) {
            sendJson(ex, 404, "{\"error\":\"Unknown online source: " + jsonEscape(sourceId) + "\"}");
            return;
        }

        // 国界触发制合规守卫（docs/地图制作规范.md §三）
        if ((hasNationalBorderInView || z <= 6) && !src.isDomesticWhitelist) {
            ex.getResponseHeaders().set("X-Compliance-Blocked", "BORDER_TRIGGER_RULE");
            sendJson(ex, 451, "{\"error\":\"COMPLIANCE_BORDER_TRIGGER_BLOCKED\",\"message\":\"检测到含国界/宏观全图视野(z<=6或hasBorder=true)，依据《地图制作规范》第三节国界触发制，已自动拦截非白名单境外行政底图: " + jsonEscape(sourceId) + "\"}");
            return;
        }

        String cacheModeTag = (src.isGcj02 && !forceRaw) ? "wgs84_corrected" : "raw";
        File cacheFile = new File(cacheRootDir,
                "proxy_" + sourceId + "_" + cacheModeTag + File.separator + z + File.separator + x + File.separator + y + ".png");
        if (cacheFile.isFile() && cacheFile.length() > 0) {
            byte[] bytes = Files.readAllBytes(cacheFile.toPath());
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.getResponseHeaders().set("X-Tile-Cache", "HIT");
            ex.getResponseHeaders().set("X-GCJ02-Corrected", String.valueOf(src.isGcj02 && !forceRaw));
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
            return;
        }

        try {
            BufferedImage outImg;
            if (src.isGcj02 && !forceRaw && z >= 7) {
                outImg = fetchAndRectifyGcj02TileToWgs84(src, z, x, y);
            } else {
                outImg = fetchUpstreamTileImage(src, z, x, y);
            }
            if (outImg == null) {
                sendJson(ex, 502, "{\"error\":\"UPSTREAM_TILE_REJECTED_OR_UNAVAILABLE\"}");
                return;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream(24576);
            ImageIO.write(outImg, "png", baos);
            byte[] pngBytes = baos.toByteArray();

            File parent = cacheFile.getParentFile();
            if (!parent.isDirectory()) parent.mkdirs();
            Files.write(cacheFile.toPath(), pngBytes);

            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.getResponseHeaders().set("X-Tile-Cache", "MISS");
            ex.getResponseHeaders().set("X-GCJ02-Corrected", String.valueOf(src.isGcj02 && !forceRaw));
            ex.sendResponseHeaders(200, pngBytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(pngBytes);
            }
        } catch (Exception e) {
            sendJson(ex, 502, "{\"error\":\"" + jsonEscape(e.toString()) + "\"}");
        }
    }

    private static BufferedImage fetchUpstreamTileImage(OnlineSource src, int z, int x, int y) throws IOException {
        String memKey = src.id + "/" + z + "/" + x + "/" + y;
        BufferedImage cached = UPSTREAM_MEM_CACHE.get(memKey);
        if (cached != null) return cached;

        String urlStr = src.urlTemplate
                .replace("{z}", String.valueOf(z))
                .replace("{x}", String.valueOf(x))
                .replace("{y}", String.valueOf(y));
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "GeoAI-ArcGISProBasemapAddin/1.0 (+https://openstreetmap.org)");
        conn.setRequestProperty("Accept", "image/png,image/jpeg,image/*;q=0.8");
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("Upstream HTTP " + code + " for " + urlStr);
        }
        byte[] rawBytes;
        try (InputStream is = conn.getInputStream()) {
            rawBytes = is.readAllBytes();
        }
        if (rawBytes.length < 120) {
            throw new IOException("Upstream payload too small (" + rawBytes.length + " B)");
        }
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(rawBytes));
        if (img == null || img.getWidth() < 64 || img.getHeight() < 64) {
            throw new IOException("Invalid image decoded from upstream");
        }
        if (isDenyCardImage(img)) {
            throw new IOException("Blocked deny-card / policy warning tile from upstream");
        }
        UPSTREAM_MEM_CACHE.put(memKey, img);
        return img;
    }

    /**
     * 检测黑黄条纹报错瓦片（如 openmaps.fr 的 Access Denied 警告图）
     */
    public static boolean isDenyCardImage(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int yellowBlackCount = 0;
        int samples = 0;
        for (int y = 16; y < h - 16; y += 16) {
            for (int x = 16; x < w - 16; x += 16) {
                int argb = img.getRGB(x, y);
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                samples++;
                boolean isBrightYellow = (r > 220 && g > 190 && b < 40);
                boolean isPureBlack = (r < 20 && g < 20 && b < 20);
                if (isBrightYellow || isPureBlack) {
                    yellowBlackCount++;
                }
            }
        }
        return samples > 0 && (yellowBlackCount * 1.0 / samples) > 0.72;
    }

    // =========================================================================
    // 7. GCJ-02 <-> WGS-84 Coordinate & Pixel Reprojection Engine
    // =========================================================================

    private static final double GCJ_A = 6378245.0;
    private static final double GCJ_EE = 0.00669342162296594323;

    public static boolean outOfChina(double lat, double lon) {
        return (lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271);
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }

    /**
     * 给定 WGS-84 (lat, lon)，求对应的 GCJ-02 (lat, lon)。
     * 用于反向重采样：目标瓦片是 WGS-84 网格，对每个目标像素 (wgsLat, wgsLon) 求出其在 GCJ-02 瓦片上的采样坐标 (gcjLat, gcjLon)。
     */
    public static double[] wgs84ToGcj02(double wgsLat, double wgsLon) {
        if (outOfChina(wgsLat, wgsLon)) {
            return new double[]{wgsLat, wgsLon};
        }
        double dLat = transformLat(wgsLon - 105.0, wgsLat - 35.0);
        double dLon = transformLon(wgsLon - 105.0, wgsLat - 35.0);
        double radLat = wgsLat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - GCJ_EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic) * Math.PI);
        dLon = (dLon * 180.0) / (GCJ_A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{wgsLat + dLat, wgsLon + dLon};
    }

    private static BufferedImage fetchAndRectifyGcj02TileToWgs84(OnlineSource src, int z, int x, int y) throws IOException {
        int tileSize = 256;
        long mapSize = 256L << z;

        // 计算目标 WGS-84 瓦片四角对应的 GCJ-02 全局像素坐标（由于同一 256x256 瓦片内偏移量变化极平滑，四角双线性插值与逐像素解算误差 < 0.05 px）
        double wgsLatNW = MercatorProjection.pixelYToLatitude(y * 256.0, mapSize);
        double wgsLonNW = MercatorProjection.pixelXToLongitude(x * 256.0, mapSize);
        double wgsLatSE = MercatorProjection.pixelYToLatitude((y + 1) * 256.0, mapSize);
        double wgsLonSE = MercatorProjection.pixelXToLongitude((x + 1) * 256.0, mapSize);

        double[] gcjNW = wgs84ToGcj02(wgsLatNW, wgsLonNW);
        double[] gcjSE = wgs84ToGcj02(wgsLatSE, wgsLonSE);

        double srcPxX0 = MercatorProjection.longitudeToPixelX(gcjNW[1], mapSize);
        double srcPxY0 = MercatorProjection.latitudeToPixelY(gcjNW[0], mapSize);
        double srcPxX1 = MercatorProjection.longitudeToPixelX(gcjSE[1], mapSize);
        double srcPxY1 = MercatorProjection.latitudeToPixelY(gcjSE[0], mapSize);

        int minTileX = (int) Math.floor(srcPxX0 / 256.0);
        int maxTileX = (int) Math.floor(srcPxX1 / 256.0);
        int minTileY = (int) Math.floor(srcPxY0 / 256.0);
        int maxTileY = (int) Math.floor(srcPxY1 / 256.0);

        int canvasW = (maxTileX - minTileX + 1) * 256;
        int canvasH = (maxTileY - minTileY + 1) * 256;
        BufferedImage stitched = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g2 = stitched.createGraphics();
        for (int tx = minTileX; tx <= maxTileX; tx++) {
            for (int ty = minTileY; ty <= maxTileY; ty++) {
                try {
                    BufferedImage sub = fetchUpstreamTileImage(src, z, tx, ty);
                    if (sub != null) {
                        g2.drawImage(sub, (tx - minTileX) * 256, (ty - minTileY) * 256, null);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        g2.dispose();

        BufferedImage dst = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB);
        double originX = minTileX * 256.0;
        double originY = minTileY * 256.0;

        for (int py = 0; py < tileSize; py++) {
            double v = (py + 0.5) / tileSize;
            double sy = (srcPxY0 + v * (srcPxY1 - srcPxY0)) - originY - 0.5;
            for (int px = 0; px < tileSize; px++) {
                double u = (px + 0.5) / tileSize;
                double sx = (srcPxX0 + u * (srcPxX1 - srcPxX0)) - originX - 0.5;
                int argb = sampleBilinear(stitched, sx, sy);
                dst.setRGB(px, py, argb);
            }
        }
        return dst;
    }

    private static int sampleBilinear(BufferedImage img, double x, double y) {
        int w = img.getWidth();
        int h = img.getHeight();
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        double fx = x - x0;
        double fy = y - y0;

        x0 = Math.max(0, Math.min(w - 1, x0));
        x1 = Math.max(0, Math.min(w - 1, x1));
        y0 = Math.max(0, Math.min(h - 1, y0));
        y1 = Math.max(0, Math.min(h - 1, y1));

        int c00 = img.getRGB(x0, y0);
        int c10 = img.getRGB(x1, y0);
        int c01 = img.getRGB(x0, y1);
        int c11 = img.getRGB(x1, y1);

        int a = (int) Math.round(bilerp((c00 >>> 24) & 0xFF, (c10 >>> 24) & 0xFF, (c01 >>> 24) & 0xFF, (c11 >>> 24) & 0xFF, fx, fy));
        int r = (int) Math.round(bilerp((c00 >>> 16) & 0xFF, (c10 >>> 16) & 0xFF, (c01 >>> 16) & 0xFF, (c11 >>> 16) & 0xFF, fx, fy));
        int g = (int) Math.round(bilerp((c00 >>> 8) & 0xFF, (c10 >>> 8) & 0xFF, (c01 >>> 8) & 0xFF, (c11 >>> 8) & 0xFF, fx, fy));
        int b = (int) Math.round(bilerp(c00 & 0xFF, c10 & 0xFF, c01 & 0xFF, c11 & 0xFF, fx, fy));
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    private static double bilerp(int v00, int v10, int v01, int v11, double fx, double fy) {
        double top = v00 * (1.0 - fx) + v10 * fx;
        double bot = v01 * (1.0 - fx) + v11 * fx;
        return top * (1.0 - fy) + bot * fy;
    }

    // =========================================================================
    // 8. In-Memory XML Theme dp-Unit Stripper & Category Filter
    // =========================================================================

    private static XmlRenderTheme resolveThemeWithDpStrip(String themeSpec, String excludeCats) throws Exception {
        if (themeSpec.startsWith("builtin:")) {
            return MapsforgeThemes.valueOf(themeSpec.substring("builtin:".length()));
        }
        File themeFile = new File(themeSpec);
        if (!themeFile.isFile()) {
            File candidate = new File(themesRootDir, themeSpec);
            if (candidate.isFile()) {
                themeFile = candidate;
            } else {
                throw new IOException("Theme XML file not found: " + themeSpec);
            }
        }
        final File resolvedFile = themeFile.getCanonicalFile();
        final XmlRenderThemeMenuCallback callback = menu -> {
            Set<String> categories = new LinkedHashSet<>();
            XmlRenderThemeStyleLayer def = menu.getDefaultValue() == null ? null : menu.getLayer(menu.getDefaultValue());
            if (def != null) {
                collectCategories(def, categories);
            }
            for (XmlRenderThemeStyleLayer layer : menu.getLayers().values()) {
                if (layer.isEnabled()) {
                    collectCategories(layer, categories);
                }
            }
            if (excludeCats != null && !excludeCats.isEmpty()) {
                for (String c : excludeCats.split(",")) {
                    categories.remove(c.trim());
                }
            }
            return categories;
        };

        return new XmlRenderTheme() {
            private XmlRenderThemeMenuCallback menuCallback = callback;
            private XmlThemeResourceProvider resourceProvider = null;

            @Override
            public XmlRenderThemeMenuCallback getMenuCallback() {
                return this.menuCallback;
            }

            @Override
            public String getRelativePathPrefix() {
                File parent = resolvedFile.getParentFile();
                return parent != null ? parent.getAbsolutePath() + File.separator : "";
            }

            @Override
            public InputStream getRenderThemeAsStream() throws IOException {
                String rawXml = Files.readString(resolvedFile.toPath(), StandardCharsets.UTF_8);
                // 将 "1.2dp" / "10dp" 流式剥离为 "1.2" / "10"，兼容移动端主题
                String cleanedXml = DP_UNIT_PATTERN.matcher(rawXml).replaceAll("$1");
                return new ByteArrayInputStream(cleanedXml.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public XmlThemeResourceProvider getResourceProvider() {
                return this.resourceProvider;
            }

            @Override
            public void setMenuCallback(XmlRenderThemeMenuCallback menuCallback) {
                this.menuCallback = menuCallback;
            }

            @Override
            public void setResourceProvider(XmlThemeResourceProvider resourceProvider) {
                this.resourceProvider = resourceProvider;
            }
        };
    }

    private static void collectCategories(XmlRenderThemeStyleLayer layer, Set<String> out) {
        Set<String> cats = layer.getCategories();
        if (cats != null) out.addAll(cats);
        List<XmlRenderThemeStyleLayer> overlays = layer.getOverlays();
        if (overlays != null) {
            for (XmlRenderThemeStyleLayer ov : overlays) {
                collectCategories(ov, out);
            }
        }
    }

    private static String computeCacheKey(List<File> mapFiles, String themeSpec, String excludeCats, boolean transparent, int tileSize) {
        try {
            List<String> sortedPaths = new ArrayList<>();
            for (File f : mapFiles) {
                sortedPaths.add(f.getCanonicalPath() + "@" + f.lastModified() + "@" + f.length());
            }
            Collections.sort(sortedPaths);
            File tf = new File(themeSpec);
            if (!tf.isFile()) tf = new File(themesRootDir, themeSpec);
            String themeSig = tf.isFile() ? (tf.getCanonicalPath() + "@" + tf.lastModified()) : themeSpec;

            String raw = String.join(";", sortedPaths) + "|" + themeSig + "|" + excludeCats + "|" + transparent + "|" + tileSize;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "default_cache";
        }
    }

    // =========================================================================
    // 9. WMTS 1.0.0 XML & ArcGIS MapServer JSON Builders
    // =========================================================================

    private static String buildWmtsCapabilitiesXml(String profileId, String title, BoundingBox bb) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
          .append("<Capabilities xmlns=\"http://www.opengis.net/wmts/1.0\" ")
          .append("xmlns:ows=\"http://www.opengis.net/ows/1.1\" ")
          .append("xmlns:xlink=\"http://www.w3.org/1999/xlink\" ")
          .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" version=\"1.0.0\">\n")
          .append("  <ows:ServiceIdentification>\n")
          .append("    <ows:Title>").append(xmlEscape(title)).append("</ows:Title>\n")
          .append("    <ows:ServiceType>OGC WMTS</ows:ServiceType>\n")
          .append("    <ows:ServiceTypeVersion>1.0.0</ows:ServiceTypeVersion>\n")
          .append("  </ows:ServiceIdentification>\n")
          .append("  <Contents>\n")
          .append("    <Layer>\n")
          .append("      <ows:Title>").append(xmlEscape(title)).append("</ows:Title>\n")
          .append("      <ows:Identifier>").append(xmlEscape(profileId)).append("</ows:Identifier>\n")
          .append("      <ows:WGS84BoundingBox>\n")
          .append(String.format(Locale.ROOT, "        <ows:LowerCorner>%.6f %.6f</ows:LowerCorner>\n", bb.minLongitude, bb.minLatitude))
          .append(String.format(Locale.ROOT, "        <ows:UpperCorner>%.6f %.6f</ows:UpperCorner>\n", bb.maxLongitude, bb.maxLatitude))
          .append("      </ows:WGS84BoundingBox>\n")
          .append("      <Style isDefault=\"true\"><ows:Identifier>default</ows:Identifier></Style>\n")
          .append("      <Format>image/png</Format>\n")
          .append("      <TileMatrixSetLink><TileMatrixSet>GoogleMapsCompatible</TileMatrixSet></TileMatrixSetLink>\n")
          .append("      <ResourceURL format=\"image/png\" resourceType=\"tile\" ")
          .append("template=\"http://127.0.0.1:").append(serverPort).append("/wmts/").append(xmlEscape(profileId))
          .append("/tile/{TileMatrix}/{TileRow}/{TileCol}.png\"/>\n")
          .append("    </Layer>\n")
          .append("    <TileMatrixSet>\n")
          .append("      <ows:Identifier>GoogleMapsCompatible</ows:Identifier>\n")
          .append("      <ows:SupportedCRS>urn:ogc:def:crs:EPSG::3857</ows:SupportedCRS>\n");
        double baseScale = 559082264.0287178;
        for (int z = 0; z <= 18; z++) {
            int dim = 1 << z;
            double scale = baseScale / dim;
            sb.append("      <TileMatrix>\n")
              .append("        <ows:Identifier>").append(z).append("</ows:Identifier>\n")
              .append(String.format(Locale.ROOT, "        <ScaleDenominator>%.8f</ScaleDenominator>\n", scale))
              .append("        <TopLeftCorner>-20037508.3427892 20037508.3427892</TopLeftCorner>\n")
              .append("        <TileWidth>256</TileWidth><TileHeight>256</TileHeight>\n")
              .append("        <MatrixWidth>").append(dim).append("</MatrixWidth><MatrixHeight>").append(dim).append("</MatrixHeight>\n")
              .append("      </TileMatrix>\n");
        }
        sb.append("    </TileMatrixSet>\n")
          .append("  </Contents>\n")
          .append("</Capabilities>\n");
        return sb.toString();
    }

    private static double lonToWebMercatorX(double lon) {
        return lon * 20037508.342789244 / 180.0;
    }

    private static double latToWebMercatorY(double lat) {
        double clamped = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double rad = Math.toRadians(clamped);
        return Math.log(Math.tan(Math.PI / 4.0 + rad / 2.0)) * 6378137.0;
    }

    private static String buildArcGisMapServerJson(String serviceId, String title, String copyright, BoundingBox bb) {
        double xmin = lonToWebMercatorX(bb.minLongitude);
        double ymin = latToWebMercatorY(bb.minLatitude);
        double xmax = lonToWebMercatorX(bb.maxLongitude);
        double ymax = latToWebMercatorY(bb.maxLatitude);

        StringBuilder lods = new StringBuilder("[");
        double res0 = 156543.03392800014;
        double scale0 = 591657527.591555;
        for (int z = 0; z <= 18; z++) {
            if (z > 0) lods.append(",");
            double factor = Math.pow(2, z);
            lods.append(String.format(Locale.ROOT,
                    "{\"level\":%d,\"resolution\":%.9f,\"scale\":%.6f}",
                    z, res0 / factor, scale0 / factor));
        }
        lods.append("]");

        return String.format(Locale.ROOT,
                "{\"currentVersion\":11.2,\"serviceDescription\":\"%s\",\"mapName\":\"%s\"," +
                "\"description\":\"%s\",\"copyrightText\":\"%s\",\"supportsDynamicLayers\":false," +
                "\"layers\":[{\"id\":0,\"name\":\"%s\",\"parentLayerId\":-1,\"defaultVisibility\":true,\"subLayerIds\":null,\"minScale\":0,\"maxScale\":0}]," +
                "\"tables\":[],\"spatialReference\":{\"wkid\":102100,\"latestWkid\":3857}," +
                "\"singleFusedMapCache\":true," +
                "\"tileInfo\":{\"rows\":256,\"cols\":256,\"dpi\":96,\"format\":\"PNG32\",\"compressionQuality\":0," +
                "\"origin\":{\"x\":-20037508.342787,\"y\":20037508.342787}," +
                "\"spatialReference\":{\"wkid\":102100,\"latestWkid\":3857},\"lods\":%s}," +
                "\"initialExtent\":{\"xmin\":%.3f,\"ymin\":%.3f,\"xmax\":%.3f,\"ymax\":%.3f,\"spatialReference\":{\"wkid\":102100,\"latestWkid\":3857}}," +
                "\"fullExtent\":{\"xmin\":%.3f,\"ymin\":%.3f,\"xmax\":%.3f,\"ymax\":%.3f,\"spatialReference\":{\"wkid\":102100,\"latestWkid\":3857}}," +
                "\"minScale\":0,\"maxScale\":0,\"units\":\"esriMeters\",\"supportedImageFormatTypes\":\"PNG32,PNG24,PNG,JPG\"," +
                "\"capabilities\":\"Map,TilesOnly\"}",
                jsonEscape(title), jsonEscape(title), jsonEscape(title), jsonEscape(copyright), jsonEscape(title),
                lods.toString(), xmin, ymin, xmax, ymax, xmin, ymin, xmax, ymax);
    }

    // =========================================================================
    // 10. HTTP Utility Helpers
    // =========================================================================

    private static Map<String, String> parseParams(HttpExchange ex) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        URI uri = ex.getRequestURI();
        parseQueryString(uri.getRawQuery(), map);
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (body.startsWith("{") && body.endsWith("}")) {
                // Lightweight flat JSON key-value parser
                String inner = body.substring(1, body.length() - 1);
                for (String pair : inner.split(",(?=\\s*\")")) {
                    int colon = pair.indexOf(':');
                    if (colon > 0) {
                        String k = pair.substring(0, colon).trim().replaceAll("^\"|\"$", "");
                        String v = pair.substring(colon + 1).trim().replaceAll("^\"|\"$", "").replace("\\\\", "\\");
                        map.put(k, v);
                    }
                }
            } else {
                parseQueryString(body, map);
            }
        }
        return map;
    }

    private static void parseQueryString(String raw, Map<String, String> out) {
        if (raw == null || raw.isEmpty()) return;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                out.put(k, v);
            }
        }
    }

    private static int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendText(HttpExchange ex, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private static String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
