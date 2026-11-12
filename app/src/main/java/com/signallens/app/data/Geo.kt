package com.signallens.app.data

import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** 좌표 변환 (순수 계산, 안드로이드 의존 없음) */
object Geo {

    /**
     * 위경도(WGS84) → KATEC. 오피넷 API가 KATEC 좌표를 받는다.
     * KATEC = 횡축 메르카토르, 원점 38°N 128°E, 축척 0.9999, 가산 (400000, 600000), 베셀 타원체.
     */
    fun wgs84ToKatec(lat: Double, lon: Double): Pair<Double, Double> {
        // 1) WGS84 위경도 → 지심 직교좌표
        val aW = 6378137.0
        val fW = 1 / 298.257223563
        val e2W = 2 * fW - fW * fW
        val phi = Math.toRadians(lat)
        val lam = Math.toRadians(lon)
        val nW = aW / sqrt(1 - e2W * sin(phi).pow(2))
        val x = nW * cos(phi) * cos(lam)
        val y = nW * cos(phi) * sin(lam)
        val z = nW * (1 - e2W) * sin(phi)

        // 2) WGS84 → 베셀 (7 매개변수 역변환). towgs84 = -115.80, 474.99, 674.11, 1.16, -2.31, -1.63, 6.43
        val sec = Math.PI / 180 / 3600
        val rx = 1.16 * sec
        val ry = -2.31 * sec
        val rz = -1.63 * sec
        val s = 1 + 6.43e-6
        val dx = x - (-115.80)
        val dy = y - 474.99
        val dz = z - 674.11
        val xb = (dx + rz * dy - ry * dz) / s
        val yb = (-rz * dx + dy + rx * dz) / s
        val zb = (ry * dx - rx * dy + dz) / s

        // 3) 베셀 직교좌표 → 위경도
        val a = 6377397.155
        val f = 1 / 299.1528128
        val e2 = 2 * f - f * f
        val p = sqrt(xb * xb + yb * yb)
        var phiB = atan2(zb, p * (1 - e2))
        repeat(6) {
            val n = a / sqrt(1 - e2 * sin(phiB).pow(2))
            phiB = atan2(zb + e2 * n * sin(phiB), p)
        }
        val lamB = atan2(yb, xb)

        // 4) 횡축 메르카토르
        val k0 = 0.9999
        val phi0 = Math.toRadians(38.0)
        val lam0 = Math.toRadians(128.0)
        val ep2 = e2 / (1 - e2)
        fun m(ph: Double): Double {
            val e4 = e2 * e2
            val e6 = e4 * e2
            return a * ((1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * ph -
                (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * ph) +
                (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * ph) -
                (35 * e6 / 3072) * sin(6 * ph))
        }
        val n = a / sqrt(1 - e2 * sin(phiB).pow(2))
        val t = tan(phiB).pow(2)
        val c = ep2 * cos(phiB).pow(2)
        val aa = (lamB - lam0) * cos(phiB)
        val east = 400000.0 + k0 * n * (aa + (1 - t + c) * aa.pow(3) / 6 +
            (5 - 18 * t + t * t + 72 * c - 58 * ep2) * aa.pow(5) / 120)
        val north = 600000.0 + k0 * (m(phiB) - m(phi0) + n * tan(phiB) * (aa * aa / 2 +
            (5 - t + 9 * c + 4 * c * c) * aa.pow(4) / 24 +
            (61 - 58 * t + t * t + 600 * c - 330 * ep2) * aa.pow(6) / 720))
        return east to north
    }

    /** 위경도 → 기상청 단기예보 격자 (nx, ny). 기상청 공식 람베르트 정각원추 변환. */
    fun kmaGrid(lat: Double, lon: Double): Pair<Int, Int> {
        val re = 6371.00877 / 5.0 // 지구 반경 / 격자 간격(5km)
        val slat1 = Math.toRadians(30.0)
        val slat2 = Math.toRadians(60.0)
        val olon = Math.toRadians(126.0)
        val olat = Math.toRadians(38.0)
        val sn = ln(cos(slat1) / cos(slat2)) / ln(tan(Math.PI / 4 + slat2 / 2) / tan(Math.PI / 4 + slat1 / 2))
        val sf = tan(Math.PI / 4 + slat1 / 2).pow(sn) * cos(slat1) / sn
        val ro = re * sf / tan(Math.PI / 4 + olat / 2).pow(sn)
        val ra = re * sf / tan(Math.PI / 4 + Math.toRadians(lat) / 2).pow(sn)
        var theta = Math.toRadians(lon) - olon
        if (theta > Math.PI) theta -= 2 * Math.PI
        if (theta < -Math.PI) theta += 2 * Math.PI
        theta *= sn
        val nx = floor(ra * sin(theta) + 43 + 0.5).toInt()
        val ny = floor(ro - ra * cos(theta) + 136 + 0.5).toInt()
        return nx to ny
    }

    /** 두 KATEC 좌표 사이 직선 거리(km) */
    fun katecKm(a: Pair<Double, Double>, b: Pair<Double, Double>): Float =
        (sqrt((a.first - b.first).pow(2) + (a.second - b.second).pow(2)) / 1000.0).toFloat()

    @Suppress("unused")
    private fun unusedAtan() = atan(0.0)
}
