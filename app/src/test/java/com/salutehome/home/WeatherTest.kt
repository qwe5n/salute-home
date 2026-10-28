package com.salutehome.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** Open-Meteo's forecast as the bento reads it; the fixture is a Moscow forecast, saved at 4 am on 28 October. */
class WeatherTest {
    private val forecast = Forecast.parse(javaClass.getResource("/home/open-meteo.json")!!.readText())

    private fun words(next: WeatherNext) = next.words.joinToString(" ") { (text, strong) -> if (strong) "[$text]" else text }

    @Test
    fun readsNowFromTheFixture() {
        val r = forecast.readingAt("2026-10-28T04")!!
        assertEquals(6, r.temp) // 5.6, from "current"
        assertEquals("Облачно", r.text) // code 3
        assertEquals("cloud", r.icon)
        assertEquals(12, r.high) // 11.5 rounds up, as JavaScript's Math.round
        assertEquals(4, r.low) // 4.1
        assertEquals(8, r.hours.size)
        assertEquals(WeatherHour("05:00", 5, "Облачно", "cloud"), r.hours.first())
        assertEquals(Sky.CLOUD, Weather.sky(r))
    }

    @Test
    fun readsALaterHourFromTheHourlyRows() {
        val r = forecast.readingAt("2026-10-28T20")!!
        assertEquals(7, r.temp) // 6.8
        assertEquals("Ясно", r.text) // code 0 after dark
        assertEquals("moon", r.icon)
        assertEquals(WeatherHour("21:00", 6, "Ясно", "moon"), r.hours.first())
        assertEquals(Sky.NIGHT, Weather.sky(r))
    }

    @Test
    fun saysWhatComesNext() {
        // Nothing wet in the next three hours: the glyph of now (a wet hour's glyph: sentencesForEveryTurn).
        assertEquals("moon", Weather.next(forecast.readingAt("2026-10-28T20")!!).icon)
    }

    private fun reading(icon: String, text: String, vararg hours: Pair<String, String>) = WeatherReading(
        temp = 24, text = text, icon = icon, high = 28, low = 20,
        hours = hours.mapIndexed { i, (h, t) -> WeatherHour("${i + 16}:00", 24, t, h) },
    )

    @Test
    fun sentencesForEveryTurn() {
        // The next three hours, times plain and temperatures strong.
        val clear = Weather.next(reading("sun", "Ясно", "sun" to "Ясно", "cloud-rain" to "Морось", "cloud" to "Облачно"))
        assertEquals("16:00 [24°] 17:00 [24°] 18:00 [24°]", words(clear))
        // The first wet hour's glyph among them, else the glyph of now.
        assertEquals("cloud-rain", clear.icon)
        assertEquals("sun", Weather.next(reading("sun", "Ясно", "sun" to "Ясно", "cloud-sun" to "Малооблачно")).icon)
        assertEquals("cloud-lightning", Weather.next(reading("cloud", "Облачно", "cloud-lightning" to "Гроза")).icon)
    }

    @Test
    fun describesWmoCodes() {
        assertEquals("Ясно" to "sun", Weather.describe(0, true))
        assertEquals("Ясно" to "moon", Weather.describe(0, false))
        assertEquals("Переменная облачность" to "cloud-sun", Weather.describe(2, true))
        assertEquals("Туман" to "cloud-fog", Weather.describe(48, true))
        assertEquals("Морось" to "cloud-rain", Weather.describe(53, false))
        assertEquals("Небольшой дождь" to "cloud-rain", Weather.describe(61, true))
        assertEquals("Снег" to "cloud-snow", Weather.describe(85, true))
        assertEquals("Гроза" to "cloud-lightning", Weather.describe(96, true))
        assertEquals("Погода" to "cloud", Weather.describe(7, true))
    }

    @Test
    fun labelsHours() {
        assertEquals("00:00", Weather.hourLabel("2026-10-21T00:00"))
        assertEquals("12:00", Weather.hourLabel("2026-10-21T12:00"))
        assertEquals("09:00", Weather.hourLabel("2026-10-21T09:00"))
    }

    @Test
    fun skies() {
        assertEquals(Sky.NONE, Weather.sky(null))
        assertEquals(Sky.SUN, Weather.sky(reading("sun", "Clear")))
        assertEquals(Sky.PARTLY, Weather.sky(reading("cloud-sun", "Partly cloudy")))
        assertEquals(Sky.CLOUD, Weather.sky(reading("cloud-fog", "Foggy")))
        assertEquals(Sky.RAIN, Weather.sky(reading("cloud-rain", "Rain")))
        assertEquals(Sky.STORM, Weather.sky(reading("cloud-lightning", "Thunderstorms")))
    }

    @Test
    fun anHourOutsideTheForecastHasNoReading() {
        assertNull(forecast.readingAt("2026-10-25T10"))
    }

    @Test
    fun hourKeyIsLocalTime() {
        // In the TV's own time zone, as Open-Meteo labels hours.
        val expected = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH", java.util.Locale.US).format(1_792_500_300_000L)
        assertEquals(expected, Weather.hourKey(1_792_500_300_000L))
    }

    @Test(expected = IOException::class)
    fun refusesSomethingElse() {
        Forecast.parse("{\"error\":true,\"reason\":\"Parameter 'latitude' is out of range\"}")
    }
}
