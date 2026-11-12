package com.signallens.app.data

import android.util.Log
import com.signallens.app.engine.Forecast
import com.signallens.app.engine.Station
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin

private const val TAG = "SignalLens"

private fun httpGet(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 6000
    c.readTimeout = 6000
    return try {
        c.inputStream.bufferedReader().use { it.readText() }
    } finally {
        c.disconnect()
    }
}

/** 실제 주유소 한 곳 (경유지에 넣을 위경도 포함) */
data class LiveStation(val station: Station, val lat: Double, val lon: Double)

/**
 * 한국석유공사 오피넷 오픈API: 현재 위치 반경 5km 안에서 가장 싼 주유소.
 * 키는 local.properties의 opinet.key. 키가 없으면 동작하지 않는다(예시 데이터로 대체).
 * 네트워크는 백그라운드에서 돌고, 결과는 [latest]에 보관한다.
 */
class OpinetStations(private val key: String, private val log: (String) -> Unit) {
    val enabled: Boolean get() = key.isNotBlank()

    @Volatile var latest: LiveStation? = null
        private set
    @Volatile var status: String = if (key.isBlank()) "오피넷 키 없음 → 예시 데이터" else "오피넷 대기 중"
        private set
    @Volatile private var busy = false
    private var lastAt = 0L
    private var lastPos: Pair<Double, Double>? = null

    /** 위치가 2km 이상 바뀌었거나 10분이 지나면 다시 조회 */
    fun refresh(lat: Double, lon: Double, now: Long) {
        if (!enabled || busy) return
        val here = Geo.wgs84ToKatec(lat, lon)
        val moved = lastPos?.let { Geo.katecKm(it, here) > 2f } ?: true
        if (!moved && now - lastAt < 600_000) return
        busy = true
        lastAt = now
        lastPos = here
        thread(name = "opinet") {
            try {
                // prodcd B027 = 보통휘발유, sort 1 = 가격순
                val url = "https://www.opinet.co.kr/api/aroundAll.do?code=${URLEncoder.encode(key, "UTF-8")}" +
                    "&x=${here.first}&y=${here.second}&radius=5000&sort=1&prodcd=B027&out=json"
                val oil = JSONObject(httpGet(url)).optJSONObject("RESULT")?.optJSONArray("OIL")
                val first = oil?.optJSONObject(0)
                if (first == null) {
                    latest = null
                    status = "오피넷: 반경 5km 안에 주유소 없음"
                } else {
                    val sx = first.optDouble("GIS_X_COOR")
                    val sy = first.optDouble("GIS_Y_COOR")
                    val km = (first.optDouble("DISTANCE", 0.0) / 1000.0).toFloat()
                    // KATEC 차이(m) → 위경도. 격자가 진북에서 살짝 돌아가 있어 그만큼 되돌린다.
                    val g = Math.toRadians(lon - 128.0) * sin(Math.toRadians(lat))
                    val de = sx - here.first
                    val dn = sy - here.second
                    val east = de * cos(g) - dn * sin(g)
                    val north = de * sin(g) + dn * cos(g)
                    val sLat = lat + north / 111_320.0
                    val sLon = lon + east / (111_320.0 * cos(Math.toRadians(lat)))
                    val st = Station(
                        name = first.optString("OS_NM", "주유소"),
                        distanceKm = km,
                        pricePerLiter = first.optInt("PRICE").takeIf { it > 0 },
                        isDemo = false,
                    )
                    latest = LiveStation(st, sLat, sLon)
                    status = "오피넷: ${st.name} ${st.pricePerLiter ?: "-"}원/L · ${"%.1f".format(km)}km"
                }
                log(status)
            } catch (e: Throwable) {
                status = "오피넷 조회 실패: ${e.message}"
                Log.w(TAG, status, e)
                log(status)
            } finally {
                busy = false
            }
        }
    }
}

/**
 * 기상청 단기예보(공공데이터포털): 내일 비 · 눈 예보.
 * 키는 local.properties의 kma.key (일반 인증키, 인코딩/디코딩 어느 쪽이든).
 */
class KmaForecast(private val key: String, private val log: (String) -> Unit) {
    val enabled: Boolean get() = key.isNotBlank()

    @Volatile var latest: Forecast? = null
        private set
    @Volatile var loaded = false
        private set
    @Volatile var status: String = if (key.isBlank()) "기상청 키 없음 → 예시 데이터" else "기상청 대기 중"
        private set
    @Volatile private var busy = false
    private var lastAt = 0L

    /** 1시간에 한 번 조회 */
    fun refresh(lat: Double, lon: Double, now: Long) {
        if (!enabled || busy) return
        if (loaded && now - lastAt < 3_600_000) return
        busy = true
        lastAt = now
        thread(name = "kma") {
            try {
                val (nx, ny) = Geo.kmaGrid(lat, lon)
                // 발표 시각: 02, 05, 08, 11, 14, 17, 20, 23시 (10분 뒤부터 제공)
                val cal = Calendar.getInstance()
                cal.add(Calendar.MINUTE, -15)
                val hours = listOf(23, 20, 17, 14, 11, 8, 5, 2)
                val h = cal.get(Calendar.HOUR_OF_DAY)
                val base = hours.firstOrNull { it <= h }
                if (base == null) {
                    cal.add(Calendar.DAY_OF_MONTH, -1)
                }
                val baseDate = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(cal.time)
                val baseTime = "%02d00".format(base ?: 23)
                val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
                val tDate = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(tomorrow.time)
                val k = if (key.contains('%')) key else URLEncoder.encode(key, "UTF-8")
                val url = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst" +
                    "?serviceKey=$k&pageNo=1&numOfRows=1000&dataType=JSON" +
                    "&base_date=$baseDate&base_time=$baseTime&nx=$nx&ny=$ny"
                val items = JSONObject(httpGet(url)).getJSONObject("response").getJSONObject("body")
                    .getJSONObject("items").getJSONArray("item")
                // PTY: 0 없음, 1 비, 2 비/눈, 3 눈, 4 소나기
                var firstTime: Int? = null
                var snow = false
                for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    if (it.optString("category") != "PTY" || it.optString("fcstDate") != tDate) continue
                    val v = it.optString("fcstValue").toIntOrNull() ?: 0
                    val t = it.optString("fcstTime").take(2).toIntOrNull() ?: continue
                    if (v != 0 && t in 5..22) {
                        if (firstTime == null || t < firstTime) firstTime = t
                        if (v == 2 || v == 3) snow = true
                    }
                }
                latest = firstTime?.let { t ->
                    val part = if (t < 12) "오전" else if (t < 18) "오후" else "저녁"
                    Forecast(rainOrSnow = true, isSnow = snow, whenText = "내일 $part", isDemo = false)
                }
                loaded = true
                status = "기상청: " + (latest?.let { "${it.whenText} ${if (it.isSnow) "눈" else "비"} 예보" } ?: "내일 비 · 눈 예보 없음")
                log(status)
            } catch (e: Throwable) {
                status = "기상청 조회 실패: ${e.message}"
                Log.w(TAG, status, e)
                log(status)
            } finally {
                busy = false
            }
        }
    }
}
