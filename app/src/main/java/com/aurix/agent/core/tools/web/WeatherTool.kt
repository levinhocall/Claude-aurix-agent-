package com.aurix.agent.core.tools.web

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.Tool
import com.aurix.agent.core.tools.ToolContext
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolResult
import com.aurix.agent.core.tools.device.requirePermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class WeatherCard(val place: String, val tempC: Int, val desc: String, val highC: Int, val lowC: Int, val humidity: Int, val windKmh: Int, val at: Long = System.currentTimeMillis())

data class RouteCard(val from: String, val to: String, val km: Double, val minutes: Int, val at: Long = System.currentTimeMillis())

/** The card the UI shows after a weather request. In-memory only. */
object CardStore {
    private val _weather = MutableStateFlow<WeatherCard?>(null)
    val weather: StateFlow<WeatherCard?> = _weather.asStateFlow()
    fun show(c: WeatherCard) { _weather.value = c }
    private val _route = MutableStateFlow<RouteCard?>(null)
    val route: StateFlow<RouteCard?> = _route.asStateFlow()
    fun showRoute(c: RouteCard) { _route.value = c }
    fun dismissRoute() { _route.value = null }
    fun dismissWeather() { _weather.value = null }
}

fun weatherCodeText(code: Int): String = when (code) {
    0 -> "clear sky"; 1 -> "mostly clear"; 2 -> "partly cloudy"; 3 -> "overcast"
    45, 48 -> "fog"; 51, 53, 55, 56, 57 -> "drizzle"; 61, 63, 65, 66, 67 -> "rain"
    71, 73, 75, 77 -> "snow"; 80, 81, 82 -> "rain showers"; 85, 86 -> "snow showers"
    95, 96, 99 -> "thunderstorm"; else -> "unknown"
}

/** Weather from Open-Meteo (free, no API key). Blank city = the phone's last known location. */
class WeatherTool(private val ctx: Context, private val http: OkHttpClient) : Tool {
    override val name = "WEATHER"
    override val description = "Current weather and today's high/low for a city (or here if no city). Shows a weather card."
    override val inputSchema = """{"city":"Delhi (optional)"}"""
    override val outputSchema = "weather summary"
    override val risk = RiskLevel.LOW
    override val timeoutMs = 20_000L
    override val retry = com.aurix.agent.core.tools.RetryPolicy(2, 1_500)

    private fun getJson(url: String): JSONObject {
        http.newCall(Request.Builder().url(url.toHttpUrl()).header("User-Agent", "AURIX").build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            return JSONObject(r.body?.string().orEmpty())
        }
    }

    @SuppressLint("MissingPermission")
    private fun here(): Triple<Double, Double, String>? {
        requirePermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION, "Location")
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val b = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } ?: return null
        return Triple(b.latitude, b.longitude, "Your location")
    }

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        try {
            val city = input.optString("city").trim()
            val (lat, lon, label) = if (city.isEmpty()) {
                here() ?: return@withContext ToolResult.fail(ToolErrorType.INVALID_INPUT, "Kaun sa sheher? (Location fix nahi mila, city ka naam batao)")
            } else {
                val g = getJson("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name=" + java.net.URLEncoder.encode(city, "UTF-8"))
                val hit = g.optJSONArray("results")?.optJSONObject(0)
                    ?: return@withContext ToolResult.fail(ToolErrorType.INVALID_INPUT, "\"$city\" naam ka sheher nahi mila")
                Triple(hit.getDouble("latitude"), hit.getDouble("longitude"), hit.optString("name", city) + (hit.optString("country").takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""))
            }
            val w = getJson("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1")
            val cur = w.getJSONObject("current")
            val daily = w.getJSONObject("daily")
            val card = WeatherCard(
                label, Math.round(cur.getDouble("temperature_2m")).toInt(), weatherCodeText(cur.optInt("weather_code", -1)),
                Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt(), Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
                cur.optInt("relative_humidity_2m"), Math.round(cur.optDouble("wind_speed_10m")).toInt(),
            )
            CardStore.show(card)
            ToolResult.ok("${card.place}: ${card.tempC}°C, ${card.desc}. High ${card.highC}°, low ${card.lowC}°. Humidity ${card.humidity}%, wind ${card.windKmh} km/h.")
        } catch (e: com.aurix.agent.core.tools.ToolException) { throw e
        } catch (e: java.io.IOException) { ToolResult.fail(ToolErrorType.NETWORK_ERROR, "Weather service tak nahi pahunch paya: ${e.message}")
        } catch (e: Exception) { ToolResult.fail(ToolErrorType.TOOL_ERROR, "Weather parse nahi hua: ${e.message}") }
    }
}

/** Distance and drive time to a place (OpenStreetMap search + OSRM, no API key). Shows a route card. */
class RouteInfoTool(private val ctx: Context, private val http: OkHttpClient) : Tool {
    override val name = "ROUTE_INFO"
    override val description = "Drive distance and time from here to a place; shows a route card."
    override val inputSchema = """{"destination":"Connaught Place Delhi"}"""
    override val outputSchema = "distance and time"
    override val required = listOf("destination")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 25_000L

    private fun get(url: String): String {
        http.newCall(Request.Builder().url(url.toHttpUrl()).header("User-Agent", "AURIX-Android/1.0").build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val dest = input.optString("destination").trim()
        if (dest.isEmpty()) return@withContext ToolResult.fail(ToolErrorType.INVALID_INPUT, "destination required")
        try {
            requirePermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION, "Location")
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val me = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
                ?: return@withContext ToolResult.fail(ToolErrorType.TOOL_ERROR, "Abhi location fix nahi hai")
            val g = org.json.JSONArray(get("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + java.net.URLEncoder.encode(dest, "UTF-8")))
            val hit = g.optJSONObject(0) ?: return@withContext ToolResult.fail(ToolErrorType.INVALID_INPUT, "\"$dest\" nahi mila")
            val dLat = hit.getString("lat"); val dLon = hit.getString("lon")
            val r = JSONObject(get("https://router.project-osrm.org/route/v1/driving/${me.longitude},${me.latitude};$dLon,$dLat?overview=false"))
            val route = r.getJSONArray("routes").getJSONObject(0)
            val km = Math.round(route.getDouble("distance") / 100.0) / 10.0
            val min = Math.max(1, Math.round(route.getDouble("duration") / 60.0).toInt())
            val label = hit.optString("display_name", dest).split(",").take(2).joinToString(",").trim()
            CardStore.showRoute(RouteCard("Your location", label, km, min))
            ToolResult.ok("Route to $label: $km km, about $min min by car.")
        } catch (e: com.aurix.agent.core.tools.ToolException) { throw e
        } catch (e: java.io.IOException) { ToolResult.fail(ToolErrorType.NETWORK_ERROR, "Route service tak nahi pahunch paya: ${e.message}")
        } catch (e: Exception) { ToolResult.fail(ToolErrorType.TOOL_ERROR, "Route nahi nikla: ${e.message}") }
    }
}
