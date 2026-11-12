package com.signallens.app.data

import android.content.Context
import com.signallens.app.engine.Forecast
import com.signallens.app.engine.Profile
import com.signallens.app.engine.Station

/** 앱 설정 저장 (기기 안에만) */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("signal_lens", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()

    var profile: Profile
        get() = runCatching { Profile.valueOf(p.getString("profile", Profile.NOVICE.name)!!) }.getOrDefault(Profile.NOVICE)
        set(v) = p.edit().putString("profile", v.name).apply()

    var demoMode: Boolean
        get() = p.getBoolean("demo", true)
        set(v) = p.edit().putBoolean("demo", v).apply()

    var insurerName: String
        get() = p.getString("insurer_name", "") ?: ""
        set(v) = p.edit().putString("insurer_name", v).apply()

    var insurerPhone: String
        get() = p.getString("insurer_phone", "") ?: ""
        set(v) = p.edit().putString("insurer_phone", v).apply()
}

/**
 * 경로상 주유소. 실제로는 한국석유공사 오피넷 오픈API(키 필요) + 내비 경로로 구현.
 * 지금은 시연용 예시만 돌려준다. 가격은 지어내지 않는다.
 */
interface StationProvider {
    fun cheapestOnRoute(rangeKm: Float): Station?
}

class DemoStationProvider : StationProvider {
    override fun cheapestOnRoute(rangeKm: Float): Station? =
        Station(name = "경로상 최저가 주유소 [예시]", distanceKm = minOf(30f, rangeKm * 0.5f), pricePerLiter = null, isDemo = true)
}

/**
 * 내일 날씨. 실제로는 기상청 단기예보 API(공공데이터포털 키 필요)로 구현.
 * 지금은 시연 패널에서 켜고 끄는 예시 값.
 */
class DemoForecast {
    var rain = false
    var snow = false
    fun tomorrow(): Forecast? = when {
        snow -> Forecast(rainOrSnow = true, isSnow = true, whenText = "내일 아침", isDemo = true)
        rain -> Forecast(rainOrSnow = true, isSnow = false, whenText = "내일 오전", isDemo = true)
        else -> null
    }
}
