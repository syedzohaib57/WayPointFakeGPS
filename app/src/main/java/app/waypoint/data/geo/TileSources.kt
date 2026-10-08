package app.waypoint.data.geo

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import app.waypoint.data.prefs.MapStyle
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex

/**
 * Keyless raster sources. Primary: Carto basemaps (the design's own style names —
 * light / dark / dark-no-labels / voyager). Failover: Esri Canvas/Street rasters.
 * Never tile.openstreetmap.org (usage policy).
 */
object TileSources {

    const val ATTRIBUTION_CARTO = "© OpenStreetMap contributors © CARTO"
    const val ATTRIBUTION_ESRI = "© Esri · HERE · Garmin · OSM contributors"

    private fun carto(style: MapStyle): OnlineTileSourceBase {
        val path = when (style) {
            MapStyle.Light -> "light_all"
            MapStyle.Dark -> "dark_all"
            MapStyle.DarkNoLabels -> "dark_nolabels"
            MapStyle.Voyager -> "rastertiles/voyager"
        }
        val subs = arrayOf(
            "https://a.basemaps.cartocdn.com/$path/",
            "https://b.basemaps.cartocdn.com/$path/",
            "https://c.basemaps.cartocdn.com/$path/",
            "https://d.basemaps.cartocdn.com/$path/"
        )
        return object : XYTileSource(
            "carto_${style.key}", 2, 20, 256, ".png", subs, ATTRIBUTION_CARTO
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                return "$baseUrl$z/$x/$y.png"
            }
        }
    }

    private fun esri(style: MapStyle): OnlineTileSourceBase {
        val path = when (style) {
            MapStyle.Light -> "Canvas/World_Light_Gray_Base"
            MapStyle.Dark, MapStyle.DarkNoLabels -> "Canvas/World_Dark_Gray_Base"
            MapStyle.Voyager -> "World_Street_Map"
        }
        return object : XYTileSource(
            "esri_${style.key}", 2, 19, 256, "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/$path/MapServer/tile/"),
            ATTRIBUTION_ESRI
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                return "$baseUrl$z/$y/$x"
            }
        }
    }

    /**
     * Primary = Esri keyless rasters (the design's own tiles — Carto dropped
     * keyless access and watermarks tiles with "API KEY REQUIRED").
     * Carto remains available behind the flag for users who add a key later.
     */
    fun source(style: MapStyle, useCarto: Boolean = false): OnlineTileSourceBase =
        if (useCarto) carto(style) else esri(style)

    fun attribution(useCarto: Boolean = false): String =
        if (useCarto) ATTRIBUTION_CARTO else ATTRIBUTION_ESRI

    fun isDark(style: MapStyle): Boolean =
        style == MapStyle.Dark || style == MapStyle.DarkNoLabels

    /**
     * Esri tiles get a light approximation of the PRD §7.2 filters;
     * Carto (if keyed) needs none — the design was emulating its palettes.
     */
    fun tint(style: MapStyle, useCarto: Boolean = false): ColorMatrixColorFilter? {
        if (useCarto) return null
        val m = ColorMatrix()
        when (style) {
            MapStyle.Light -> {
                m.setSaturation(0.9f)
                m.postConcat(brightness(1.02f))
            }
            MapStyle.Dark -> {
                m.setSaturation(1.3f)
                m.postConcat(brightness(0.85f))
            }
            MapStyle.DarkNoLabels -> {
                m.setSaturation(1.1f)
                m.postConcat(brightness(0.62f))
            }
            MapStyle.Voyager -> m.setSaturation(0.85f)
        }
        return ColorMatrixColorFilter(m)
    }

    private fun brightness(scale: Float) = ColorMatrix(
        floatArrayOf(
            scale, 0f, 0f, 0f, 0f,
            0f, scale, 0f, 0f, 0f,
            0f, 0f, scale, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
}
