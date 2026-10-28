package com.salutehome.home

import com.salutehome.BuildConfig

import com.salutehome.R
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** One hour of the forecast: "3 pm", 24 degrees, "Морось", Phosphor's "cloud-rain". */
data class WeatherHour(val label: String, val temp: Int, val text: String, val icon: String)

/** The weather now and the next eight hours, as the prototype's `reading`. */
data class WeatherReading(
    val temp: Int,
    val text: String,
    val icon: String,
    val high: Int,
    val low: Int,
    val hours: List<WeatherHour>,
)

/** The square's sky, from the weather now (bento.css's data-sky). */
enum class Sky { NONE, SUN, PARTLY, CLOUD, RAIN, STORM, NIGHT }

/** What the weather does next: a glyph and words, the strong ones white ("Drizzle from 3 pm"). */
data class WeatherNext(val icon: String, val words: List<Pair<String, Boolean>>)

/**
 * Open-Meteo's forecast for the place set at build time, kept as the hourly series it sends, so the reading for any hour
 * it covers can be worked out again later: after a reboot the cached forecast still shows the right hour
 * at once, and between refreshes "what's next" moves on with the clock.
 */
class Forecast(
    private val currentHour: String,
    private val currentTemp: Double,
    private val currentCode: Int,
    private val currentDay: Boolean,
    private val times: List<String>,
    private val temps: DoubleArray,
    private val codes: IntArray,
    private val days: BooleanArray,
    private val dailyDates: List<String>,
    private val dailyMax: DoubleArray,
    private val dailyMin: DoubleArray,
    private val dailyCodes: IntArray = IntArray(0),
    /** Now, for the weather panel: feels-like temperature, relative humidity (%) and wind (km/h). */
    val feelsLike: Int? = null,
    val humidity: Int? = null,
    val wind: Int? = null,
) {
    /** The next [count] hours after [hour] ("2026-10-20T15"), for the panel's hourly strip. */
    fun hoursAfter(hour: String, count: Int): List<WeatherHour> {
        val at = times.indexOfFirst { it.startsWith(hour) }
        if (at < 0) return emptyList()
        return (at + 1 until minOf(at + 1 + count, times.size)).map { k ->
            val (t, i) = Weather.describe(codes[k], days[k])
            WeatherHour(Weather.hourLabel(times[k]), Weather.round(temps[k]), t, i)
        }
    }

    /** One day of the week ahead: its date ("2026-10-20"), high, low, words and glyph. */
    data class Day(val date: String, val high: Int, val low: Int, val text: String, val icon: String)

    fun week(): List<Day> = dailyDates.indices.map { d ->
        val (t, i) = Weather.describe(dailyCodes.getOrElse(d) { 3 }, true)
        Day(dailyDates[d], Weather.round(dailyMax.getOrElse(d) { 0.0 }), Weather.round(dailyMin.getOrElse(d) { 0.0 }), t, i)
    }

    /** The reading for [hour] ("2026-10-20T17", local time), or null if the forecast doesn't reach it. */
    fun readingAt(hour: String): WeatherReading? {
        val at = times.indexOfFirst { it.startsWith(hour) }
        if (at < 0) return null
        val live = hour == currentHour
        val temp = if (live) currentTemp else temps[at]
        val (text, icon) = Weather.describe(if (live) currentCode else codes[at], if (live) currentDay else days[at])
        val day = dailyDates.indexOf(hour.take(10)).coerceAtLeast(0)
        val next = (at + 1 until minOf(at + 9, times.size)).map { k ->
            val (t, i) = Weather.describe(codes[k], days[k])
            WeatherHour(Weather.hourLabel(times[k]), Weather.round(temps[k]), t, i)
        }
        return WeatherReading(
            temp = Weather.round(temp),
            text = text,
            icon = icon,
            high = Weather.round(dailyMax.getOrElse(day) { temp }),
            low = Weather.round(dailyMin.getOrElse(day) { temp }),
            hours = next,
        )
    }

    companion object {
        /** Open-Meteo's JSON, as the prototype asks for it; throws on anything else. */
        fun parse(json: String): Forecast {
            try {
                val root = JSONObject(json)
                val current = root.getJSONObject("current")
                val hourly = root.getJSONObject("hourly")
                val daily = root.getJSONObject("daily")
                val time = hourly.getJSONArray("time")
                val temp = hourly.getJSONArray("temperature_2m")
                val code = hourly.getJSONArray("weather_code")
                val isDay = hourly.getJSONArray("is_day")
                val n = time.length()
                val dates = daily.getJSONArray("time")
                val max = daily.getJSONArray("temperature_2m_max")
                val min = daily.getJSONArray("temperature_2m_min")
                return Forecast(
                    currentHour = current.getString("time").take(13),
                    currentTemp = current.getDouble("temperature_2m"),
                    currentCode = current.getInt("weather_code"),
                    currentDay = current.getInt("is_day") == 1,
                    times = List(n) { time.getString(it) },
                    temps = DoubleArray(n) { temp.optDouble(it, 0.0) },
                    codes = IntArray(n) { code.optInt(it, -1) },
                    days = BooleanArray(n) { isDay.optInt(it, 1) == 1 },
                    dailyDates = List(dates.length()) { dates.getString(it) },
                    dailyMax = DoubleArray(max.length()) { max.optDouble(it, 0.0) },
                    dailyMin = DoubleArray(min.length()) { min.optDouble(it, 0.0) },
                    dailyCodes = daily.optJSONArray("weather_code")?.let { a -> IntArray(a.length()) { a.optInt(it, 3) } } ?: IntArray(0),
                    feelsLike = current.optDouble("apparent_temperature", Double.NaN).takeIf { !it.isNaN() }?.let { Weather.round(it) },
                    humidity = current.optInt("relative_humidity_2m", -1).takeIf { it >= 0 },
                    wind = current.optDouble("wind_speed_10m", Double.NaN).takeIf { !it.isNaN() }?.let { Weather.round(it) },
                )
            } catch (e: JSONException) {
                throw IOException("Open-Meteo sent something unexpected", e)
            }
        }
    }
}

/** The weather words and pictures, ported from the prototype's describe(), bento.js and app.js. */
object Weather {
    /** The place, from the build (Moscow unless local.properties sets your own, see app/build.gradle.kts). */
    const val LATITUDE = BuildConfig.WEATHER_LAT
    const val LONGITUDE = BuildConfig.WEATHER_LON
    const val PLACE = BuildConfig.WEATHER_PLACE
    val ZONE: TimeZone = TimeZone.getDefault()

    /** Every 30 minutes, as the prototype. */
    const val EVERY_MS = 30 * 60 * 1000L

    val URL = "https://api.open-meteo.com/v1/forecast?latitude=$LATITUDE&longitude=$LONGITUDE" +
        "&current=temperature_2m,weather_code,is_day,apparent_temperature,relative_humidity_2m,wind_speed_10m" +
        "&hourly=temperature_2m,weather_code,is_day" +
        "&daily=temperature_2m_max,temperature_2m_min,weather_code,sunrise,sunset&forecast_days=7&timezone=" +
        java.net.URLEncoder.encode(ZONE.id, "UTF-8")

    /** JavaScript's Math.round, which the prototype rounds temperatures with (halves go up). */
    fun round(x: Double): Int = Math.floor(x + 0.5).toInt()

    /** WMO weather codes, as Open-Meteo reports them, to words and a Phosphor glyph name. */
    fun describe(code: Int, isDay: Boolean): Pair<String, String> {
        fun dn(day: String, night: String) = if (isDay) day else night
        return when {
            code == 0 -> "Ясно" to dn("sun", "moon")
            code == 1 -> "Малооблачно" to dn("sun", "moon")
            code == 2 -> "Переменная облачность" to dn("cloud-sun", "cloud-moon")
            code == 3 -> "Облачно" to "cloud"
            code == 45 || code == 48 -> "Туман" to "cloud-fog"
            code in 51..57 -> "Морось" to "cloud-rain"
            code in 71..77 || code == 85 || code == 86 -> "Снег" to "cloud-snow"
            code >= 95 -> "Гроза" to "cloud-lightning"
            else -> when (code) {
                61 -> "Небольшой дождь"
                63 -> "Дождь"
                65 -> "Сильный дождь"
                66, 67 -> "Ледяной дождь"
                80 -> "Небольшой ливень"
                81 -> "Ливень"
                82 -> "Сильный ливень"
                else -> null
            }?.let { it to "cloud-rain" } ?: ("Погода" to "cloud")
        }
    }

    /** [describe]'s words cut to fit the bento's sky tile (about ten letters), with no ellipsis. */
    fun short(text: String): String = when (text) {
        "Переменная облачность" -> "Перем. обл."
        "Малооблачно" -> "Почти ясно"
        "Небольшой дождь" -> "Сл. дождь"
        "Сильный дождь" -> "Сил. дождь"
        "Ледяной дождь" -> "Лед. дождь"
        "Небольшой ливень" -> "Сл. ливень"
        "Сильный ливень" -> "Ливень"
        else -> text
    }

    /** "2026-10-20T15:00" to "15:00". */
    fun hourLabel(iso: String): String = iso.substring(11, 16)

    /** The hour [millis] falls in, local time, as Open-Meteo labels it ("2026-10-20T17"). */
    fun hourKey(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US).apply { timeZone = ZONE }.format(millis)

    fun sky(reading: WeatherReading?): Sky = when (reading?.icon) {
        null -> Sky.NONE
        "sun" -> Sky.SUN
        "moon", "cloud-moon" -> Sky.NIGHT
        "cloud-sun" -> Sky.PARTLY
        "cloud-rain" -> Sky.RAIN
        "cloud-lightning" -> Sky.STORM
        else -> Sky.CLOUD
    }

    private enum class Kind { FAIR, CLOUD, RAIN, STORM, SNOW }

    private fun kindOf(icon: String): Kind = when {
        "lightning" in icon -> Kind.STORM
        "rain" in icon -> Kind.RAIN
        "snow" in icon -> Kind.SNOW
        icon == "cloud" || icon == "cloud-fog" -> Kind.CLOUD
        else -> Kind.FAIR
    }

    private fun wet(k: Kind) = k == Kind.RAIN || k == Kind.STORM || k == Kind.SNOW

    /**
     * The next three hours as the line shows them: "13:00 8°  14:00 7°  15:00 6°", the times plain and
     * the temperatures strong, with the glyph of the first wet hour among them (or of now).
     */
    fun next(r: WeatherReading): WeatherNext {
        val hours = r.hours.take(3)
        val icon = hours.firstOrNull { wet(kindOf(it.icon)) }?.icon ?: r.icon
        val words = ArrayList<Pair<String, Boolean>>()
        for (h in hours) {
            words += h.label to false
            words += "${h.temp}°" to true
        }
        return WeatherNext(icon, words)
    }

    /** Phosphor's regular glyph for a weather icon name. */
    fun glyph(icon: String?): Int = when (icon) {
        "sun" -> R.drawable.ph_sun
        "moon" -> R.drawable.ph_moon
        "cloud-sun" -> R.drawable.ph_cloud_sun
        "cloud-moon" -> R.drawable.ph_cloud_moon
        "cloud-fog" -> R.drawable.ph_cloud_fog
        "cloud-snow" -> R.drawable.ph_cloud_snow
        "cloud-rain" -> R.drawable.ph_cloud_rain
        "cloud-lightning" -> R.drawable.ph_cloud_lightning
        else -> R.drawable.ph_cloud
    }
}

/**
 * The forecast on disk and from the network. Main-thread free: every call here does I/O, so callers run
 * it on a background thread. The file is Open-Meteo's own JSON; its modified time is when it came.
 */
class WeatherSource(cacheDir: File, private val http: Http) {
    private val file = File(cacheDir, "home-weather-7d.json")

    /** The last forecast fetched, if there is one and it can be read. */
    fun cached(): Forecast? = try {
        if (file.isFile) Forecast.parse(file.readText()) else null
    } catch (e: IOException) {
        null
    }

    /** How long ago the cached forecast came (a negative age, after the clock moved, counts as old). */
    fun age(now: Long): Long = if (file.isFile) (now - file.lastModified()).let { if (it < 0) Long.MAX_VALUE else it } else Long.MAX_VALUE

    /** A fresh forecast, saved for next time; throws IOException offline or on a bad answer. */
    fun fetch(): Forecast {
        val json = http.getString(Weather.URL)
        val forecast = Forecast.parse(json)
        writeAtomically(file, json)
        return forecast
    }
}

/** Writes [text] beside [file] then renames it in, so a power cut never leaves half a file. */
internal fun writeAtomically(file: File, text: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
        file.delete()
        if (!tmp.renameTo(file)) throw IOException("Couldn't save ${file.name}")
    }
}
