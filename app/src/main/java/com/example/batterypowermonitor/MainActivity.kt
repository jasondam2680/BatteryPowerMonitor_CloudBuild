package com.example.batterypowermonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// ============================================================================
// 1. MÔ HÌNH DỮ LIỆU ĐO LƯỜNG, PHIÊN SẠC & CƠ SỞ DỮ LIỆU CỦ SẠC
// ============================================================================

data class BatteryTelemetry(
    val currentMa: Float = 0f,
    val currentAmp: Float = 0f,
    val voltageVolt: Float = 0f,
    val powerWatt: Float = 0f,
    val temperatureC: Float = 0f,
    val levelPercent: Int = 0,
    val isCharging: Boolean = false,
    val plugType: String = "Chưa cắm sạc",
    val healthStatus: String = "Tốt",
    val sysAdapterFastCharge: String = "",
    val sysUsbType: String = "",
    val vbusVolt: Float = 0f
)

data class LogSample(
    val timestampMs: Long,
    val timeFormatted: String,
    val voltageV: Float,
    val currentA: Float,
    val powerW: Float,
    val tempC: Float,
    val levelPct: Int,
    val protocol: String
)

data class ChargingSessionRecord(
    val sessionId: String,
    val startTime: String,
    var endTime: String,
    val startLevel: Int,
    var endLevel: Int,
    var peakPowerW: Float,
    var avgPowerW: Float,
    var maxTempC: Float,
    var accumulatedMah: Float,
    var detectedProtocol: String,
    var chargerModelCode: String,
    val samplesCount: Int,
    val csvFilePath: String
)

data class ChargerSpecProfile(
    val brand: String,
    val productName: String,
    val modelCode: String,
    val maxPowerW: Int,
    val portsConfig: String,
    val primaryControllerIc: String,
    val protocolChipIc: String,
    val ganPowerFet: String,
    val supportedProtocols: List<String>,
    val pdoProfiles: String,
    val ppsOrAvsRange: String,
    val dimensionsWeight: String,
    val bodyColorHex: Long,
    val accentColorHex: Long,
    val formFactor: String // "COMPACT_CUBE", "MACBOOK_BRICK", "GAN_TOWER", "PUDDING_CYLINDER"
)

data class ProtocolDetectionResult(
    val primaryStandard: String,       // VD: "USB PD 3.0 + PPS", "PD 3.1 EPR", "QC 3.0", "PD 3.2 AVS"
    val subProtocolDetail: String,     // Giải thích kỹ thuật chi tiết
    val negotiatedStep: String,        // Cấu hình PDO / APDO đang thương lượng
    val confidenceBadge: String        // Mức độ khớp tín hiệu
)

// ============================================================================
// 2. CƠ SỞ DỮ LIỆU KỸ THUẬT CỦ SẠC (APPLE, BASEUS, BELKIN, XIAOMI)
// ============================================================================

object ChargerCatalogRepository {
    val chargers = listOf(
        // --- APPLE ---
        ChargerSpecProfile(
            brand = "Apple",
            productName = "Apple 20W USB-C Power Adapter",
            modelCode = "A2305 / A2940",
            maxPowerW = 20,
            portsConfig = "1x USB-C",
            primaryControllerIc = "Power Integrations InnoSwitch3-Pro (Custom)",
            protocolChipIc = "Cypress CYPD3135 (USB-IF PD 3.0 Controller)",
            ganPowerFet = "Silicon MOSFET (Non-GaN Planar)",
            supportedProtocols = listOf("USB PD 3.0", "Apple 2.4A", "USB DCP 5V/1.5A"),
            pdoProfiles = "5V/3A (15W), 9V/2.22A (20W)",
            ppsOrAvsRange = "Không hỗ trợ PPS (Fixed PDO Only)",
            dimensionsWeight = "42 × 42 × 27 mm • 60g",
            bodyColorHex = 0xFFF8FAFC,
            accentColorHex = 0xFF64748B,
            formFactor = "COMPACT_CUBE"
        ),
        ChargerSpecProfile(
            brand = "Apple",
            productName = "Apple 35W Dual USB-C Port Power Adapter",
            modelCode = "A2676 / A2571",
            maxPowerW = 35,
            portsConfig = "2x USB-C (Tự động chia dòng thông minh)",
            primaryControllerIc = "PI InnoSwitch4-CZ + ClampZero",
            protocolChipIc = "Infineon EZ-PD CCG3PA (Dual-Port PD 3.0)",
            ganPowerFet = "PowiGaN Integrated Switch",
            supportedProtocols = listOf("USB PD 3.0", "Apple 2.4A", "Samsung 5V/2A", "DCP"),
            pdoProfiles = "5V/3A, 9V/3A, 15V/2.33A, 20V/1.75A (35W Max)",
            ppsOrAvsRange = "Không hỗ trợ PPS • Chia tải 17.5W + 17.5W hoặc 27.5W + 7.5W",
            dimensionsWeight = "56 × 56 × 28 mm • 115g",
            bodyColorHex = 0xFFF8FAFC,
            accentColorHex = 0xFF475569,
            formFactor = "COMPACT_CUBE"
        ),
        ChargerSpecProfile(
            brand = "Apple",
            productName = "Apple 40W Dynamic Power Adapter (60W Max)",
            modelCode = "A3351",
            maxPowerW = 60,
            portsConfig = "1x USB-C (Boost 60W Peak)",
            primaryControllerIc = "NXP Custom Quasi-Resonant Controller",
            protocolChipIc = "Infineon Custom USB-PD 3.1 Controller",
            ganPowerFet = "Infineon CoolGaN Transistor",
            supportedProtocols = listOf("USB PD 3.1", "USB PD 3.0", "Apple 2.4A", "DCP"),
            pdoProfiles = "5V/3A, 9V/3A, 15V/2.67A (40W) • Peak 20V/3A (60W)",
            ppsOrAvsRange = "Hỗ trợ điều áp động cho iPhone 17 Pro / MacBook Air",
            dimensionsWeight = "50 × 50 × 28 mm • 98g",
            bodyColorHex = 0xFFF1F5F9,
            accentColorHex = 0xFF0284C7,
            formFactor = "COMPACT_CUBE"
        ),
        ChargerSpecProfile(
            brand = "Apple",
            productName = "Apple 140W USB-C GaN Power Adapter",
            modelCode = "A3607 / A2452",
            maxPowerW = 140,
            portsConfig = "1x USB-C (PD 3.1 EPR)",
            primaryControllerIc = "NXP PFC + STMicro / Onsemi LLC Controller",
            protocolChipIc = "Infineon Custom PD 3.1 EPR Controller",
            ganPowerFet = "Infineon Custom CoolGaN + Synchronous Buck",
            supportedProtocols = listOf("USB PD 3.1 EPR", "USB PD 3.0", "AVS 15-28V", "DCP"),
            pdoProfiles = "5V/3A, 9V/3A, 15V/3A, 20V/4.7A, 28V/5A (140W)",
            ppsOrAvsRange = "AVS (Adjustable Voltage Supply): 15V–28V @ 140W Max",
            dimensionsWeight = "95.7 × 74.4 × 28.5 mm • 285g",
            bodyColorHex = 0xFFF8FAFC,
            accentColorHex = 0xFF0F172A,
            formFactor = "MACBOOK_BRICK"
        ),

        // --- BASEUS ---
        ChargerSpecProfile(
            brand = "Baseus",
            productName = "Baseus GaN2 Quick Charger Dual-C 45W",
            modelCode = "CCGAN-M02",
            maxPowerW = 45,
            portsConfig = "2x USB-C (30W + 18W Dual)",
            primaryControllerIc = "ON Semiconductor NCP1342 QR PWM",
            protocolChipIc = "iSmartWare SW3516H Dual-Port Buck & Protocol",
            ganPowerFet = "Navitas GaNFast NV6115",
            supportedProtocols = listOf("USB PD 3.0", "PPS", "QC 3.0", "QC 2.0", "SCP", "FCP", "AFC"),
            pdoProfiles = "5V/3A, 9V/3A, 12V/3A, 15V/3A, 20V/2.25A (45W)",
            ppsOrAvsRange = "PPS: 3.3V–11V @ 3A (33W Max)",
            dimensionsWeight = "62 × 32 × 30 mm • 85g",
            bodyColorHex = 0xFF1E293B,
            accentColorHex = 0xFF38BDF8,
            formFactor = "GAN_TOWER"
        ),
        ChargerSpecProfile(
            brand = "Baseus",
            productName = "Baseus GaN5 Pro Fast Charger 65W (2C1A)",
            modelCode = "CCGAN65C5 / CCGAN65S5",
            maxPowerW = 65,
            portsConfig = "2x USB-C + 1x USB-A",
            primaryControllerIc = "iSmartWare SW1125P Integrated GaN Master IC",
            protocolChipIc = "2x iSmartWare SW3516P Buck & Multi-Protocol IC (SR: SW1608)",
            ganPowerFet = "iSmartWare SW1125P Monolithic GaN FET",
            supportedProtocols = listOf("USB PD 3.0", "PPS", "QC 4+", "QC 3.0", "QC 2.0", "SCP", "FCP", "AFC"),
            pdoProfiles = "5V/3A, 9V/3A, 12V/3A, 15V/3A, 20V/3.25A (65W)",
            ppsOrAvsRange = "PPS: 3.3V–11V @ 5A (55W) / 3.3V–21V @ 3A",
            dimensionsWeight = "61 × 36 × 32 mm • 120g",
            bodyColorHex = 0xFF0F172A,
            accentColorHex = 0xFF00E5FF,
            formFactor = "GAN_TOWER"
        ),
        ChargerSpecProfile(
            brand = "Baseus",
            productName = "Baseus GaN2 Pro 100W / 120W Fast Charger",
            modelCode = "CCGAN100US / CCGAN-J01",
            maxPowerW = 120,
            portsConfig = "2x USB-C + 1x USB-A",
            primaryControllerIc = "NXP TEA2016AAT PFC+LLC Digital Controller",
            protocolChipIc = "Injoinic IP2726S (Qualcomm QC5 Certified)",
            ganPowerFet = "Navitas NV6125 + NV6127 GaNFast",
            supportedProtocols = listOf("USB PD 3.0", "PPS", "QC 5", "QC 4+", "QC 3.0", "SCP", "FCP", "AFC"),
            pdoProfiles = "5V/3A, 9V/3A, 12V/3A, 15V/3A, 20V/5A (100W–120W)",
            ppsOrAvsRange = "PPS: 3.3V–21V @ 5A (105W Max)",
            dimensionsWeight = "94 × 54 × 30 mm • 220g",
            bodyColorHex = 0xFF1E293B,
            accentColorHex = 0xFF0EA5E9,
            formFactor = "MACBOOK_BRICK"
        ),
        ChargerSpecProfile(
            brand = "Baseus",
            productName = "Baseus GaN5 Pro 140W PD 3.1 Fast Charger",
            modelCode = "CCGAN140C5",
            maxPowerW = 140,
            portsConfig = "2x USB-C + 1x USB-A",
            primaryControllerIc = "ON Semiconductor NCP1623 PFC + NCP1342",
            protocolChipIc = "Weltrend WT6676F PD 3.1 + iSmartWare SW3516H",
            ganPowerFet = "Navitas NV6134A GaNFast IC",
            supportedProtocols = listOf("USB PD 3.1 EPR", "USB PD 3.0", "PPS", "AVS", "QC 4+", "QC 3.0", "SCP", "FCP"),
            pdoProfiles = "5V/3A, 9V/3A, 12V/3A, 15V/3A, 20V/5A, 28V/5A (140W)",
            ppsOrAvsRange = "PPS: 3.3V–21V @ 5A • AVS: 15V–28V @ 140W",
            dimensionsWeight = "90 × 59 × 30 mm • 304g",
            bodyColorHex = 0xFF090D16,
            accentColorHex = 0xFFF59E0B,
            formFactor = "MACBOOK_BRICK"
        ),

        // --- BELKIN ---
        ChargerSpecProfile(
            brand = "Belkin",
            productName = "Belkin BoostCharge 45W GaN PD 3.2 Wall Charger",
            modelCode = "WCA013yz",
            maxPowerW = 45,
            portsConfig = "1x USB-C (PD 3.2 Certified)",
            primaryControllerIc = "Integrated QR GaN Controller + Allpower APG077N01G SR",
            protocolChipIc = "Injoinic IP2723T (USB-IF TID: 3135 Multi-Protocol)",
            ganPowerFet = "Allpower APNE70R200K GaN Power Device",
            supportedProtocols = listOf("USB PD 3.2", "USB PD 3.0", "PPS", "QC 4+", "QC 3.0", "FCP", "SCP", "AFC"),
            pdoProfiles = "5V/3A, 9V/3A, 12V/3A, 15V/3A, 20V/2.25A (45W)",
            ppsOrAvsRange = "PPS: 5.0V–16.0V @ 3A (Tối ưu cho iPhone 17 Pro / iPad)",
            dimensionsWeight = "39.5 × 34.9 × 34.8 mm • 57g",
            bodyColorHex = 0xFF7F1D1D,
            accentColorHex = 0xFFEF4444,
            formFactor = "COMPACT_CUBE"
        ),
        ChargerSpecProfile(
            brand = "Belkin",
            productName = "Belkin BoostCharge Pro Dual USB-C 65W / 67W GaN",
            modelCode = "WCH013 / WCH020yz",
            maxPowerW = 67,
            portsConfig = "2x USB-C (65W/67W Single hoặc 45W + 20W Dual)",
            primaryControllerIc = "Power Integrations InnoSwitch3-Pro / Navitas Controller",
            protocolChipIc = "Weltrend WT6633P / Canyon CY2333 PD 3.0 + PPS",
            ganPowerFet = "Navitas GaNFast NV6117 / Innoscience GaN",
            supportedProtocols = listOf("USB PD 3.0", "PPS", "QC 4+", "Apple 2.4A", "DCP"),
            pdoProfiles = "5V/3A, 9V/3A, 15V/3A, 20V/3.25A (65W) – 20V/3.35A (67W)",
            ppsOrAvsRange = "PPS: 3.3V–21.0V @ 3.25A",
            dimensionsWeight = "58 × 42 × 30 mm • 118g",
            bodyColorHex = 0xFFF1F5F9,
            accentColorHex = 0xFF10B981,
            formFactor = "COMPACT_CUBE"
        ),
        ChargerSpecProfile(
            brand = "Belkin",
            productName = "Belkin BoostCharge Pro 140W 4-Port GaN Charger",
            modelCode = "WCH014",
            maxPowerW = 140,
            portsConfig = "3x USB-C + 1x USB-A (C1: 140W, C2/C3: 65W, A: 12W)",
            primaryControllerIc = "Onsemi NCP1622 PFC + Joulwatt JW1550A (140W) + SouthChip SC3023 (65W)",
            protocolChipIc = "Weltrend WT6676 (C1 PD 3.1) + 2x Canyon CY2333 (C2/C3 QC5/PD3.0)",
            ganPowerFet = "Navitas NV6136C + Innoscience INN650DA260A GaN + BASiC SiC Diode",
            supportedProtocols = listOf("USB PD 3.1 EPR", "USB PD 3.0", "PPS", "QC 5", "QC 3.0", "Apple 2.4A"),
            pdoProfiles = "C1: 5V/3A, 9V/3A, 15V/3A, 20V/5A, 28V/5A (140W) • C2/C3: 20V/3.25A",
            ppsOrAvsRange = "C1 PPS: 5V–21V @ 5A • C2/C3 PPS: 3.3V–21V @ 3.25A",
            dimensionsWeight = "96.0 × 77.3 × 30.9 mm • 327g",
            bodyColorHex = 0xFFE2E8F0,
            accentColorHex = 0xFF2563EB,
            formFactor = "MACBOOK_BRICK"
        ),

        // --- XIAOMI ---
        ChargerSpecProfile(
            brand = "Xiaomi",
            productName = "Xiaomi 33W Fast Charger (Type-A / Dual)",
            modelCode = "MDY-11-EZ / MDY-16-EF",
            maxPowerW = 33,
            portsConfig = "1x USB-A (Chân cam đặc trưng Xiaomi Supporting PD & QC)",
            primaryControllerIc = "Power Integrations InnoSwitch3-CP",
            protocolChipIc = "Weltrend WT6615F / Fitipower FP6606C",
            ganPowerFet = "Silicon SuperJunction MOSFET",
            supportedProtocols = listOf("Xiaomi Mi Turbo Charge 33W", "USB PD 3.0", "PPS", "QC 3.0", "QC 2.0"),
            pdoProfiles = "5V/3A, 9V/3A, 12V/2.25A, 20V/1.35A",
            ppsOrAvsRange = "Mi PPS: 3.3V–11V @ 3A (33W Max)",
            dimensionsWeight = "46.8 × 28.0 × 55.4 mm • 82g",
            bodyColorHex = 0xFFF8FAFC,
            accentColorHex = 0xFFFF6900,
            formFactor = "COMPACT_CUBE"
        ),
        ChargerSpecProfile(
            brand = "Xiaomi",
            productName = "Xiaomi 67W GaN 3-Port Charger (Surge Turbo)",
            modelCode = "MDY-19-EA / MDY-12-EF",
            maxPowerW = 67,
            portsConfig = "2x USB-C + 1x USB-A (67W Surge / 65W PD)",
            primaryControllerIc = "HYASiC HYC3657 + HYC906M Integrated GaN Solution",
            protocolChipIc = "2x iSmartWare SW356M Buck & Protocol IC + Vergiga VS3620GEMC",
            ganPowerFet = "HYASiC Integrated GaN FET",
            supportedProtocols = listOf("Xiaomi 67W Surge Turbo", "USB PD 3.0", "PPS", "QC 5", "QC 3.0", "DCP"),
            pdoProfiles = "5V/3A, 9V/3A, 15V/3A, 20V/3.25A (65W PD) • 11V/6.1A (67W Surge)",
            ppsOrAvsRange = "PPS 1: 3.6V–11V @ 6.1A (67W) • PPS 2: 3.6V–20V @ 3.25A",
            dimensionsWeight = "50.0 × 50.1 × 30.1 mm • 104g",
            bodyColorHex = 0xFFF8FAFC,
            accentColorHex = 0xFFFF6900,
            formFactor = "GAN_TOWER"
        ),
        ChargerSpecProfile(
            brand = "Xiaomi",
            productName = "Xiaomi 90W / 100W GaN Charger (Xiaomi 17 Series)",
            modelCode = "MDY-18-EW / MDY-18-EU",
            maxPowerW = 100,
            portsConfig = "1x USB-C (Zero Standby Power Architecture)",
            primaryControllerIc = "SouthChip SC3005Z Primary Controller + SC3501 SR",
            protocolChipIc = "Custom SouthChip Protocol IC 'C4' (Mi HyperCharge + PD)",
            ganPowerFet = "Navitas NV6144C GaNFast + Silan Synchronous Rectifier",
            supportedProtocols = listOf("Xiaomi 100W HyperCharge", "USB PD 3.0", "PPS", "QC 4+", "QC 3.0"),
            pdoProfiles = "5V/3A, 9V/3A, 15V/3A, 20V/3.25A (65W PD) • 20V/5A (100W HyperCharge)",
            ppsOrAvsRange = "Proprietary PPS: 3.6V–20V @ 5A (100W Max)",
            dimensionsWeight = "55 × 49 × 28 mm • 125g",
            bodyColorHex = 0xFFF1F5F9,
            accentColorHex = 0xFFFF6900,
            formFactor = "PUDDING_CYLINDER"
        ),
        ChargerSpecProfile(
            brand = "Xiaomi",
            productName = "Xiaomi 120W HyperCharge GaN Combo Charger",
            modelCode = "MDY-14-EE / MDY-13-ET",
            maxPowerW = 120,
            portsConfig = "1x USB-C / USB-A (6A E-Marker Cable)",
            primaryControllerIc = "ON Semiconductor NCP1342 + Custom PFC",
            protocolChipIc = "Weltrend WT6633P (Xiaomi Custom Encrypted Handshake)",
            ganPowerFet = "Navitas GaNFast NV6127 / Innoscience INN650D",
            supportedProtocols = listOf("Xiaomi 120W HyperCharge", "USB PD 3.0 (65W)", "PPS", "QC 3.0", "QC 2.0"),
            pdoProfiles = "5V/3A, 9V/3A, 11V/6A, 20V/6A (120W Max) • PD: 20V/3.25A (65W)",
            ppsOrAvsRange = "Mi PPS: 3.6V–11V @ 6A / 3.6V–20V @ 6A (120W)",
            dimensionsWeight = "56.0 × 60.0 × 28.8 mm • 150g",
            bodyColorHex = 0xFFF8FAFC,
            accentColorHex = 0xFFFF5500,
            formFactor = "MACBOOK_BRICK"
        )
    )

    /**
     * Thuật toán nhận diện cơ chế sạc nhanh thời gian thực (Protocol Detection Engine)
     * Kết hợp thông số phần cứng Kernel (/sys/class/power_supply) + hành vi dòng/áp (I, U, P) + Hồ sơ củ sạc
     */
    fun detectFastChargeProtocol(
        telemetry: BatteryTelemetry,
        selectedCharger: ChargerSpecProfile?
    ): ProtocolDetectionResult {
        if (!telemetry.isCharging) {
            return ProtocolDetectionResult(
                primaryStandard = "Chưa cắm sạc (Discharging)",
                subProtocolDetail = "Thiết bị đang sử dụng năng lượng trực tiếp từ cell pin Li-ion/Li-Po",
                negotiatedStep = "Vbat = ${String.format("%.2fV", telemetry.voltageVolt)} • I = ${String.format("%.2fA", telemetry.currentAmp)}",
                confidenceBadge = "Trạng thái chờ"
            )
        }

        val p = telemetry.powerWatt
        val i = telemetry.currentAmp
        val vbus = if (telemetry.vbusVolt > 3.5f) telemetry.vbusVolt else 0f
        val sysHint = (telemetry.sysUsbType + " " + telemetry.sysAdapterFastCharge).uppercase()
        val chargerProtos = selectedCharger?.supportedProtocols?.joinToString(" ") ?: ""

        // 1. Kiểm tra nếu Kernel xuất trực tiếp cờ USB PD / PPS / QC
        if (sysHint.contains("PD_PPS") || sysHint.contains("APDO")) {
            return ProtocolDetectionResult(
                primaryStandard = "USB PD 3.0 / 3.1 (PPS - Programmable Power Supply)",
                subProtocolDetail = "Kernel xác nhận chế độ APDO (PPS) điều chỉnh điện áp bước nhảy 20mV/bước, giảm nhiệt IC sạc trong máy.",
                negotiatedStep = "APDO 3.3V–11V/21V • Dòng nạp: ${String.format("%.2fA", i)} (${String.format("%.1fW", p)})",
                confidenceBadge = "Xác thực từ Kernel + Telemetry"
            )
        }

        // 2. Phân tích theo dải công suất, dòng điện và hồ sơ củ sạc đang kết nối
        return when {
            // Công suất cực cao (> 65W) -> PD 3.1 EPR hoặc Xiaomi HyperCharge 90W/100W/120W
            p >= 65f -> {
                if (selectedCharger?.brand == "Xiaomi") {
                    ProtocolDetectionResult(
                        primaryStandard = "Xiaomi HyperCharge (Proprietary PPS + PD 3.0)",
                        subProtocolDetail = "Cơ chế bơm điện tích kép (Dual Charge Pump) qua cáp 6A E-Marker với giao thức mã hóa riêng của Xiaomi.",
                        negotiatedStep = "20V ⎓ ${String.format("%.2fA", p / 20f)} (PPS 3.6–20V @ 5A/6A)",
                        confidenceBadge = "Khớp đặc tuyến công suất cao (>65W)"
                    )
                } else {
                    ProtocolDetectionResult(
                        primaryStandard = "USB PD 3.1 EPR / AVS (Extended Power Range)",
                        subProtocolDetail = "Sạc nhanh công suất cao chuẩn USB-IF PD 3.1 hỗ trợ dải điện áp mở rộng lên tới 28V và điều áp AVS.",
                        negotiatedStep = "PDO 20V–28V ⎓ ${String.format("%.2fA", i)} (${String.format("%.1fW", p)})",
                        confidenceBadge = "Khớp đặc tuyến PD 3.1 (>65W)"
                    )
                }
            }

            // Công suất 28W - 65W -> PD 3.0 / PD 3.2 / PPS / Xiaomi Surge Turbo
            p >= 28f -> {
                when {
                    selectedCharger?.modelCode?.contains("WCA013") == true -> ProtocolDetectionResult(
                        primaryStandard = "USB PD 3.2 + PPS (Programmable Power Supply)",
                        subProtocolDetail = "Chuẩn USB Power Delivery 3.2 thế hệ mới kèm dải PPS 5–16V/3A điều khiển bởi chip Injoinic IP2723T.",
                        negotiatedStep = "PPS 5–16V / PDO 15V ⎓ ${String.format("%.2fA", i)} (${String.format("%.1fW", p)})",
                        confidenceBadge = "Khớp hồ sơ PD 3.2 (28W–45W)"
                    )
                    selectedCharger?.brand == "Xiaomi" -> ProtocolDetectionResult(
                        primaryStandard = "Xiaomi Surge Turbo / USB PD 3.0 (PPS)",
                        subProtocolDetail = "Sử dụng biến áp GaN kết hợp giao thức PPS 3.6–11V dòng cao (tới 6.1A) hoặc PDO cố định 15V/20V.",
                        negotiatedStep = "PPS 3.6–11V ⎓ ${String.format("%.2fA", i)} (${String.format("%.1fW", p)})",
                        confidenceBadge = "Khớp đặc tuyến PPS dòng cao"
                    )
                    selectedCharger?.brand == "Apple" -> ProtocolDetectionResult(
                        primaryStandard = "USB PD 3.0 / PD 3.1 (Fixed PDO)",
                        subProtocolDetail = "Apple sử dụng chuẩn USB Power Delivery thuần túy với các mức điện áp cố định (Fixed PDO 15V hoặc 20V), không dùng PPS.",
                        negotiatedStep = "Fixed PDO 15V/20V • Công suất: ${String.format("%.1fW", p)}",
                        confidenceBadge = "Chuẩn Apple PD Fixed PDO"
                    )
                    else -> ProtocolDetectionResult(
                        primaryStandard = "USB PD 3.0 + PPS (APDO Mode)",
                        subProtocolDetail = "Sạc nhanh thông qua giao tiếp chân CC (Configuration Channel) với dải điện áp biến thiên PPS 3.3V–11V/21V.",
                        negotiatedStep = "PPS APDO • I_bat = ${String.format("%.2fA", i)} (${String.format("%.1fW", p)})",
                        confidenceBadge = "Khớp đặc tuyến PD 3.0 + PPS"
                    )
                }
            }

            // Công suất 14W - 28W -> PD 3.0 (9V/2.22A - 9V/3A), PPS hoặc QC 3.0
            p >= 14f -> {
                when {
                    selectedCharger?.brand == "Apple" -> ProtocolDetectionResult(
                        primaryStandard = "USB PD 3.0 (Fixed PDO 9V)",
                        subProtocolDetail = "Thương lượng mức áp cố định 9V ⎓ 2.22A (20W) hoặc 9V ⎓ 3A (27W) qua giao thức USB Power Delivery 3.0.",
                        negotiatedStep = "PDO 2: 9.0V ⎓ ${String.format("%.2fA", p / 9f)} (${String.format("%.1fW", p)})",
                        confidenceBadge = "Khớp PDO 9V chuẩn PD 3.0"
                    )
                    chargerProtos.contains("PD 3.2") -> ProtocolDetectionResult(
                        primaryStandard = "USB PD 3.2 / PPS (9V–12V Range)",
                        subProtocolDetail = "Đang hoạt động ở dải trung áp của chuẩn PD 3.2 / PPS giúp tối ưu hóa hiệu suất chuyển đổi nhiệt.",
                        negotiatedStep = "PPS 9V–11V • I_bat = ${String.format("%.2fA", i)} (${String.format("%.1fW", p)})",
                        confidenceBadge = "Khớp dải trung áp PD 3.2"
                    )
                    vbus in 8.5f..9.5f && !chargerProtos.contains("PPS") -> ProtocolDetectionResult(
                        primaryStandard = "Qualcomm Quick Charge 2.0 / PD 3.0 (9V Step)",
                        subProtocolDetail = "Giao thức nâng áp cố định lên mức 9V qua chân D+/D- (QC 2.0) hoặc chân CC (USB PD).",
                        negotiatedStep = "9.0V Fixed Step • ${String.format("%.1fW", p)}",
                        confidenceBadge = "Khớp mức áp 9V"
                    )
                    else -> ProtocolDetectionResult(
                        primaryStandard = "USB PD 3.0 (PPS) / Qualcomm QC 3.0",
                        subProtocolDetail = "Điều chỉnh điện áp thông minh (PPS 20mV/step trên cổng Type-C hoặc QC 3.0 INOV 200mV/step trên cổng USB-A).",
                        negotiatedStep = "Dynamic 9V–11V • I_bat = ${String.format("%.2fA", i)} (${String.format("%.1fW", p)})",
                        confidenceBadge = "Đang sạc nhanh (Fast Charge)"
                    )
                }
            }

            // Công suất 9.5W - 14W -> QC 2.0 / QC 3.0 / Apple 2.4A / USB Type-C 5V/3A
            p >= 9.5f -> {
                ProtocolDetectionResult(
                    primaryStandard = "Qualcomm QC 2.0 / USB-C PD 5V⎓3A / Apple 2.4A",
                    subProtocolDetail = "Mức sạc nhanh cơ bản hoặc giai đoạn sạc bão hòa (CV Stage khi pin >75%): duy trì công suất 10W–14W để bảo vệ tuổi thọ pin.",
                    negotiatedStep = "5V⎓2.4A–3A hoặc 9V⎓1.5A (${String.format("%.1fW", p)})",
                    confidenceBadge = "Sạc tiêu chuẩn nâng cao / CV Stage"
                )
            }

            // Công suất < 9.5W -> USB BC 1.2 / DCP 5V / Sạc nhỏ giọt (Trickle/CV)
            else -> {
                val stageNote = if (telemetry.levelPercent >= 80) {
                    "Pin đã đạt ${telemetry.levelPercent}% (Giai đoạn Constant Voltage - Sạc nhỏ giọt giảm dòng từ củ sạc PD/QC)."
                } else {
                    "Đang chạy ở chuẩn cơ bản 5V (USB BC 1.2 / DCP 5V⎓1.5A) hoặc cổng USB máy tính."
                }
                ProtocolDetectionResult(
                    primaryStandard = if (telemetry.levelPercent >= 80) "PD / QC (Chế độ giảm dòng CV Stage >80%)" else "USB BC 1.2 / Standard DCP (5V)",
                    subProtocolDetail = stageNote,
                    negotiatedStep = "5.0V ⎓ ${String.format("%.2fA", i)} (${String.format("%.1fW", p)})",
                    confidenceBadge = if (telemetry.levelPercent >= 80) "Bảo vệ pin cuối chu kỳ" else "Chuẩn 5V cơ bản"
                )
            }
        }
    }

    /**
     * Tự động gợi ý củ sạc phù hợp nhất trong danh mục nếu người dùng để chế độ "Tự động gợi ý"
     */
    fun suggestChargerByPower(powerW: Float, brandFilter: String): ChargerSpecProfile {
        val candidates = if (brandFilter == "Tất cả") chargers else chargers.filter { it.brand == brandFilter }
        return when {
            powerW >= 95f -> candidates.firstOrNull { it.maxPowerW >= 120 } ?: candidates.last()
            powerW >= 50f -> candidates.firstOrNull { it.maxPowerW in 65..100 } ?: candidates.last()
            powerW >= 25f -> candidates.firstOrNull { it.maxPowerW in 35..67 } ?: candidates.first()
            else -> candidates.first()
        }
    }
}

// ============================================================================
// 3. QUẢN LÝ LƯU TRỮ LỊCH SỬ PHIÊN SẠC (JSON + XUẤT FILE CSV)
// ============================================================================

object SessionStorageManager {
    private const val PREFS_NAME = "battery_sessions_prefs"
    private const val KEY_SESSIONS = "saved_sessions_json"

    fun saveSession(context: Context, session: ChargingSessionRecord, samples: List<LogSample>): String {
        val sdfFile = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val fileName = "ChargingLog_${sdfFile.format(Date())}.csv"
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val csvFile = File(dir, fileName)

        // Ghi toàn bộ mẫu đo ra file CSV
        runCatching {
            csvFile.bufferedWriter().use { writer ->
                writer.write("Thoi_Gian,Dien_Ap_V,Dong_Dien_A,Cong_Suat_W,Nhiet_Do_C,Muc_Pin_Pct,Chuan_Sac\n")
                samples.forEach { s ->
                    writer.write(
                        "${s.timeFormatted},${String.format(Locale.US, "%.3f", s.voltageV)}," +
                                "${String.format(Locale.US, "%.3f", s.currentA)}," +
                                "${String.format(Locale.US, "%.2f", s.powerW)}," +
                                "${String.format(Locale.US, "%.1f", s.tempC)}," +
                                "${s.levelPct},\"${s.protocol}\"\n"
                    )
                }
            }
        }

        val updatedSession = session.copy(
            samplesCount = samples.size,
            csvFilePath = csvFile.absolutePath
        )

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingJson = prefs.getString(KEY_SESSIONS, "[]") ?: "[]"
        val arr = runCatching { JSONArray(existingJson) }.getOrDefault(JSONArray())

        val obj = JSONObject().apply {
            put("sessionId", updatedSession.sessionId)
            put("startTime", updatedSession.startTime)
            put("endTime", updatedSession.endTime)
            put("startLevel", updatedSession.startLevel)
            put("endLevel", updatedSession.endLevel)
            put("peakPowerW", updatedSession.peakPowerW.toDouble())
            put("avgPowerW", updatedSession.avgPowerW.toDouble())
            put("maxTempC", updatedSession.maxTempC.toDouble())
            put("accumulatedMah", updatedSession.accumulatedMah.toDouble())
            put("detectedProtocol", updatedSession.detectedProtocol)
            put("chargerModelCode", updatedSession.chargerModelCode)
            put("samplesCount", updatedSession.samplesCount)
            put("csvFilePath", updatedSession.csvFilePath)
        }

        // Chèn phiên mới nhất lên đầu
        val newArr = JSONArray()
        newArr.put(obj)
        for (idx in 0 until minOf(arr.length(), 49)) {
            newArr.put(arr.getJSONObject(idx))
        }

        prefs.edit().putString(KEY_SESSIONS, newArr.toString()).apply()
        return csvFile.absolutePath
    }

    fun loadSessions(context: Context): List<ChargingSessionRecord> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SESSIONS, "[]") ?: "[]"
        val list = mutableListOf<ChargingSessionRecord>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    ChargingSessionRecord(
                        sessionId = o.optString("sessionId"),
                        startTime = o.optString("startTime"),
                        endTime = o.optString("endTime"),
                        startLevel = o.optInt("startLevel"),
                        endLevel = o.optInt("endLevel"),
                        peakPowerW = o.optDouble("peakPowerW", 0.0).toFloat(),
                        avgPowerW = o.optDouble("avgPowerW", 0.0).toFloat(),
                        maxTempC = o.optDouble("maxTempC", 0.0).toFloat(),
                        accumulatedMah = o.optDouble("accumulatedMah", 0.0).toFloat(),
                        detectedProtocol = o.optString("detectedProtocol"),
                        chargerModelCode = o.optString("chargerModelCode"),
                        samplesCount = o.optInt("samplesCount"),
                        csvFilePath = o.optString("csvFilePath")
                    )
                )
            }
        }
        return list
    }

    fun clearSessions(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_SESSIONS).apply()
    }
}

// ============================================================================
// 4. ĐỌC THÔNG SỐ KERNEL LINUX (/sys/class/power_supply) NẾU HỖ TRỢ
// ============================================================================

fun readKernelSysfsLine(path: String): String {
    return try {
        val f = File(path)
        if (f.exists() && f.canRead()) f.readText().trim() else ""
    } catch (_: Exception) {
        ""
    }
}

// ============================================================================
// 5. MAIN ACTIVITY & GIAO DIỆN CHÍNH (3 TABS)
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E676),
                    secondary = Color(0xFF38BDF8),
                    background = Color(0xFF0B1120),
                    surface = Color(0xFF1E293B)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BatteryPowerMonitorApp()
                }
            }
        }
    }
}

@Composable
fun BatteryPowerMonitorApp() {
    val context = LocalContext.current
    val batteryManager = remember {
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var telemetry by remember { mutableStateOf(BatteryTelemetry()) }
    val powerHistory = remember { mutableStateListOf<Float>() }
    var refreshIntervalMs by remember { mutableLongStateOf(1000L) }

    // Trạng thái củ sạc được chọn hoặc tự động nhận diện
    var autoSelectCharger by remember { mutableStateOf(true) }
    var selectedBrandFilter by remember { mutableStateOf("Tất cả") }
    var manualCharger by remember { mutableStateOf(ChargerCatalogRepository.chargers[5]) } // Baseus GaN5 Pro 65W

    val activeCharger = if (autoSelectCharger) {
        ChargerCatalogRepository.suggestChargerByPower(telemetry.powerWatt, selectedBrandFilter)
    } else {
        manualCharger
    }

    val protocolResult = remember(telemetry, activeCharger) {
        ChargerCatalogRepository.detectFastChargeProtocol(telemetry, activeCharger)
    }

    // Trạng thái tự động ghi log suốt quá trình sạc (Tính năng 1)
    var autoRecordCharging by remember { mutableStateOf(true) }
    var isRecordingSession by remember { mutableStateOf(false) }
    val currentSessionSamples = remember { mutableStateListOf<LogSample>() }
    var sessionStartTimeStr by remember { mutableStateOf("") }
    var sessionStartLevel by remember { mutableIntStateOf(0) }
    var sessionPeakPower by remember { mutableFloatStateOf(0f) }
    var sessionMaxTemp by remember { mutableFloatStateOf(0f) }
    var sessionAccumulatedMah by remember { mutableFloatStateOf(0f) }
    var lastSampleTimeMs by remember { mutableLongStateOf(0L) }
    var savedSessions by remember { mutableStateOf(SessionStorageManager.loadSessions(context)) }
    var lastSavedBanner by remember { mutableStateOf("") }

    // Lắng nghe BroadcastReceiver cho Điện áp, Nhiệt độ, % Pin và sự kiện Cắm/Rút sạc
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return

                val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
                val voltageV = if (voltageMv > 100) voltageMv / 1000f else voltageMv.toFloat()

                val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                val tempC = tempTenths / 10f

                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val percent = if (scale > 0) (level * 100) / scale else 0

                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isChargingNow = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val plugType = when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "Sạc nhanh AC (Adapter)"
                    BatteryManager.BATTERY_PLUGGED_USB -> "Cổng USB Standard"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Sạc không dây Qi/MagSafe"
                    BatteryManager.BATTERY_PLUGGED_DOCK -> "Đế sạc (Dock)"
                    else -> "Đang xả pin"
                }

                val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
                val healthStr = when (health) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "Tốt"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Quá nhiệt"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "Hỏng"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Quá áp"
                    else -> "Bình thường"
                }

                telemetry = telemetry.copy(
                    voltageVolt = voltageV,
                    temperatureC = tempC,
                    levelPercent = percent,
                    isCharging = isChargingNow,
                    plugType = plugType,
                    healthStatus = healthStr
                )
            }
        }

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(receiver, filter)
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Tự động kích hoạt / kết thúc lưu phiên sạc khi trạng thái cắm sạc thay đổi
    LaunchedEffect(telemetry.isCharging, autoRecordCharging) {
        val sdf = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
        if (telemetry.isCharging && autoRecordCharging && !isRecordingSession) {
            // Bắt đầu phiên sạc mới
            isRecordingSession = true
            currentSessionSamples.clear()
            sessionStartTimeStr = sdf.format(Date())
            sessionStartLevel = telemetry.levelPercent
            sessionPeakPower = telemetry.powerWatt
            sessionMaxTemp = telemetry.temperatureC
            sessionAccumulatedMah = 0f
            lastSampleTimeMs = System.currentTimeMillis()
            lastSavedBanner = "Đang tự động ghi nhận thông số phiên sạc..."
        } else if (!telemetry.isCharging && isRecordingSession) {
            // Rút sạc -> Tự động đóng gói và lưu phiên sạc
            isRecordingSession = false
            if (currentSessionSamples.isNotEmpty()) {
                val avgP = currentSessionSamples.map { it.powerW }.average().toFloat()
                val record = ChargingSessionRecord(
                    sessionId = "SES_${System.currentTimeMillis()}",
                    startTime = sessionStartTimeStr,
                    endTime = sdf.format(Date()),
                    startLevel = sessionStartLevel,
                    endLevel = telemetry.levelPercent,
                    peakPowerW = sessionPeakPower,
                    avgPowerW = avgP,
                    maxTempC = sessionMaxTemp,
                    accumulatedMah = sessionAccumulatedMah,
                    detectedProtocol = protocolResult.primaryStandard,
                    chargerModelCode = "${activeCharger.brand} ${activeCharger.modelCode}",
                    samplesCount = currentSessionSamples.size,
                    csvFilePath = ""
                )
                val savedPath = SessionStorageManager.saveSession(
                    context,
                    record,
                    currentSessionSamples.toList()
                )
                savedSessions = SessionStorageManager.loadSessions(context)
                lastSavedBanner = "Đã tự động lưu phiên sạc (${currentSessionSamples.size} mẫu) vào: ${File(savedPath).name}"
            }
        }
    }

    // Vòng lặp đọc dòng điện tức thời, đọc thêm thông số USB/PD từ Kernel và ghi mẫu vào bộ nhớ
    LaunchedEffect(refreshIntervalMs) {
        val sdfTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        while (true) {
            val rawCurrent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val currentMa = if (abs(rawCurrent) > 50_000) rawCurrent / 1000f else rawCurrent.toFloat()
            val currentAmp = abs(currentMa) / 1000f

            // Thử đọc điện áp VBUS hoặc trạng thái PD từ sysfs nếu ROM cho phép
            val usbTypeSys = readKernelSysfsLine("/sys/class/power_supply/usb/usb_type")
            val pdAuthSys = readKernelSysfsLine("/sys/class/power_supply/usb/pd_active")
            val vbusRaw = readKernelSysfsLine("/sys/class/power_supply/usb/voltage_now").toFloatOrNull() ?: 0f
            val vbusV = if (vbusRaw > 100_000) vbusRaw / 1_000_000f else if (vbusRaw > 100) vbusRaw / 1000f else 0f

            val powerWatt = telemetry.voltageVolt * currentAmp

            telemetry = telemetry.copy(
                currentMa = currentMa,
                currentAmp = currentAmp,
                powerWatt = powerWatt,
                sysUsbType = usbTypeSys,
                sysAdapterFastCharge = if (pdAuthSys == "1") "PD_PPS" else "",
                vbusVolt = vbusV
            )

            if (powerHistory.size >= 50) {
                powerHistory.removeAt(0)
            }
            powerHistory.add(powerWatt)

            // Ghi mẫu tự động nếu đang trong quá trình sạc
            if (isRecordingSession && telemetry.isCharging) {
                val nowMs = System.currentTimeMillis()
                val deltaHours = if (lastSampleTimeMs > 0L) (nowMs - lastSampleTimeMs) / 3_600_000f else 0f
                lastSampleTimeMs = nowMs

                sessionAccumulatedMah += abs(currentMa) * deltaHours
                if (powerWatt > sessionPeakPower) sessionPeakPower = powerWatt
                if (telemetry.temperatureC > sessionMaxTemp) sessionMaxTemp = telemetry.temperatureC

                currentSessionSamples.add(
                    LogSample(
                        timestampMs = nowMs,
                        timeFormatted = sdfTime.format(Date(nowMs)),
                        voltageV = telemetry.voltageVolt,
                        currentA = currentAmp,
                        powerW = powerWatt,
                        tempC = telemetry.temperatureC,
                        levelPct = telemetry.levelPercent,
                        protocol = protocolResult.primaryStandard
                    )
                )
            }

            delay(refreshIntervalMs)
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Battery Power & Charger Analyzer",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Đo công suất • Nhận diện củ sạc & chuẩn PD/PPS/QC • Tự động lưu Log",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                    // Chỉ báo trạng thái tự động ghi log
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isRecordingSession) Color(0xFF00E676).copy(alpha = 0.2f) else Color(0xFF334155)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isRecordingSession) Color(0xFF00E676) else Color(0xFF94A3B8))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isRecordingSession) "REC (${currentSessionSamples.size})" else "STANDBY",
                                color = if (isRecordingSession) Color(0xFF00E676) else Color(0xFFCBD5E1),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Thanh chuyển Tab
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF1E293B),
                    contentColor = Color(0xFF00E676),
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("⚡ Đo & Chuẩn Sạc", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("🔌 Củ Sạc (4 Hãng)", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("📈 Nhật Ký Sạc (${currentSessionSamples.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> RealtimeDashboardTab(
                    telemetry = telemetry,
                    protocolResult = protocolResult,
                    activeCharger = activeCharger,
                    powerHistory = powerHistory,
                    refreshIntervalMs = refreshIntervalMs,
                    onIntervalChange = { refreshIntervalMs = it },
                    onOpenChargerCatalog = { selectedTab = 1 }
                )
                1 -> ChargerDatabaseTab(
                    selectedBrand = selectedBrandFilter,
                    onBrandChange = { selectedBrandFilter = it },
                    autoSelectCharger = autoSelectCharger,
                    onAutoSelectToggle = { autoSelectCharger = it },
                    activeCharger = activeCharger,
                    onSelectCharger = {
                        autoSelectCharger = false
                        manualCharger = it
                    }
                )
                2 -> ChargingLogHistoryTab(
                    autoRecordCharging = autoRecordCharging,
                    onAutoRecordChange = { autoRecordCharging = it },
                    isRecordingSession = isRecordingSession,
                    currentSamples = currentSessionSamples,
                    sessionStartTimeStr = sessionStartTimeStr,
                    sessionPeakPower = sessionPeakPower,
                    sessionMaxTemp = sessionMaxTemp,
                    sessionAccumulatedMah = sessionAccumulatedMah,
                    lastSavedBanner = lastSavedBanner,
                    savedSessions = savedSessions,
                    onForceSaveCurrent = {
                        if (currentSessionSamples.isNotEmpty()) {
                            val sdf = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
                            val avgP = currentSessionSamples.map { it.powerW }.average().toFloat()
                            val record = ChargingSessionRecord(
                                sessionId = "SES_${System.currentTimeMillis()}",
                                startTime = sessionStartTimeStr.ifEmpty { sdf.format(Date()) },
                                endTime = sdf.format(Date()),
                                startLevel = sessionStartLevel,
                                endLevel = telemetry.levelPercent,
                                peakPowerW = sessionPeakPower,
                                avgPowerW = avgP,
                                maxTempC = sessionMaxTemp,
                                accumulatedMah = sessionAccumulatedMah,
                                detectedProtocol = protocolResult.primaryStandard,
                                chargerModelCode = "${activeCharger.brand} ${activeCharger.modelCode}",
                                samplesCount = currentSessionSamples.size,
                                csvFilePath = ""
                            )
                            val path = SessionStorageManager.saveSession(context, record, currentSessionSamples.toList())
                            savedSessions = SessionStorageManager.loadSessions(context)
                            lastSavedBanner = "Đã xuất thủ công phiên sạc ra file: ${File(path).name}"
                        }
                    },
                    onClearHistory = {
                        SessionStorageManager.clearSessions(context)
                        savedSessions = emptyList()
                        lastSavedBanner = "Đã xóa lịch sử các phiên sạc cũ."
                    }
                )
            }
        }
    }
}

// ============================================================================
// TAB 1: ĐO CÔNG SUẤT THỜI GIAN THỰC + NHẬN DIỆN CHUẨN SẠC NHANH & CỦ SẠC
// ============================================================================

@Composable
fun RealtimeDashboardTab(
    telemetry: BatteryTelemetry,
    protocolResult: ProtocolDetectionResult,
    activeCharger: ChargerSpecProfile,
    powerHistory: List<Float>,
    refreshIntervalMs: Long,
    onIntervalChange: (Long) -> Unit,
    onOpenChargerCatalog: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // 1. Thẻ Công suất chính (Watt)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val statusColor = if (telemetry.isCharging) Color(0xFF00E676) else Color(0xFFFFB74D)
                    Text(
                        text = if (telemetry.isCharging) "⚡ ĐANG SẠC (${telemetry.plugType})" else "🔋 ĐANG XẢ PIN",
                        color = statusColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    val animatedPower by animateFloatAsState(targetValue = telemetry.powerWatt, label = "power")
                    Text(
                        text = String.format("%.2f W", animatedPower),
                        color = Color.White,
                        fontSize = 46.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Công suất nạp tức thời (P = U × I)",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // 2. Thẻ Nhận diện Cơ Chế Sạc Nhanh (QC 2.0/3.0, PD 3.0/3.1/3.2, PPS...)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF172554))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CƠ CHẾ SẠC NHANH ĐANG HOẠT ĐỘNG",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Surface(
                            color = Color(0xFF0284C7),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = protocolResult.confidenceBadge,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = protocolResult.primaryStandard,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Cấu hình thương lượng: ${protocolResult.negotiatedStep}",
                        color = Color(0xFF7DD3FC),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = protocolResult.subProtocolDetail,
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // 3. Thẻ Nhận diện Củ Sạc (Hãng, Tên, Mã Model, Ảnh minh họa, Chip điều khiển)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenChargerCatalog() },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Ảnh minh họa sản phẩm vẽ trực tiếp bằng Vector/Canvas theo đúng kiểu dáng hãng
                    ChargerProductIllustration(
                        charger = activeCharger,
                        modifier = Modifier.size(84.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = activeCharger.brand.uppercase(),
                                color = Color(activeCharger.accentColorHex),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "Đổi củ sạc ➔",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = activeCharger.productName,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Mã SP (Model): ${activeCharger.modelCode} • Max ${activeCharger.maxPowerW}W",
                            color = Color(0xFFE2E8F0),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "IC Giao thức: ${activeCharger.protocolChipIc}",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "GaN / Nguồn: ${activeCharger.primaryControllerIc}",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 4. Lưới thông số Cường độ dòng điện (Ampe) & Điện áp (Volt)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Cường độ dòng điện",
                    mainValue = String.format("%.3f A", telemetry.currentAmp),
                    subValue = String.format("%.0f mA", telemetry.currentMa),
                    accentColor = Color(0xFF29B6F6)
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Điện áp Pin",
                    mainValue = String.format("%.3f V", telemetry.voltageVolt),
                    subValue = String.format("%.0f mV", telemetry.voltageVolt * 1000),
                    accentColor = Color(0xFFAB47BC)
                )
            }
        }

        // 5. Lưới thông số phụ: Mức pin, Nhiệt độ, Sức khỏe
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SmallMetricCard(
                    modifier = Modifier.weight(1f),
                    label = "Mức Pin",
                    value = "${telemetry.levelPercent}%"
                )
                SmallMetricCard(
                    modifier = Modifier.weight(1f),
                    label = "Nhiệt độ",
                    value = String.format("%.1f °C", telemetry.temperatureC)
                )
                SmallMetricCard(
                    modifier = Modifier.weight(1f),
                    label = "Sức khỏe",
                    value = telemetry.healthStatus
                )
            }
        }

        // 6. Biểu đồ công suất thời gian thực
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Đồ thị biến thiên Công suất (W)",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Chu kỳ: ${refreshIntervalMs}ms",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    PowerChart(
                        dataPoints = powerHistory,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // 7. Tùy chỉnh tốc độ lấy mẫu
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tốc độ lấy mẫu:", color = Color(0xFF94A3B8), fontSize = 12.sp)
                listOf(250L to "0.25s", 500L to "0.5s", 1000L to "1.0s").forEach { (ms, label) ->
                    FilterChip(
                        selected = refreshIntervalMs == ms,
                        onClick = { onIntervalChange(ms) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
        }
    }
}

// ============================================================================
// TAB 2: CƠ SỞ DỮ LIỆU CỦ SẠC & LINH KIỆN (APPLE, BASEUS, BELKIN, XIAOMI)
// ============================================================================

@Composable
fun ChargerDatabaseTab(
    selectedBrand: String,
    onBrandChange: (String) -> Unit,
    autoSelectCharger: Boolean,
    onAutoSelectToggle: (Boolean) -> Unit,
    activeCharger: ChargerSpecProfile,
    onSelectCharger: (ChargerSpecProfile) -> Unit
) {
    val brands = listOf("Tất cả", "Apple", "Baseus", "Belkin", "Xiaomi")
    val filteredChargers = remember(selectedBrand) {
        if (selectedBrand == "Tất cả") ChargerCatalogRepository.chargers
        else ChargerCatalogRepository.chargers.filter { it.brand == selectedBrand }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // Công tắc tự động khớp củ sạc theo công suất hoặc chọn thủ công
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tự động khớp củ sạc theo dòng/áp thực tế",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Bấm vào một củ sạc bên dưới nếu muốn cố định mẫu củ sạc bạn đang cắm",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = autoSelectCharger,
                    onCheckedChange = onAutoSelectToggle
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Bộ lọc hãng sản xuất
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            brands.forEach { brand ->
                FilterChip(
                    selected = selectedBrand == brand,
                    onClick = { onBrandChange(brand) },
                    label = { Text(brand, fontWeight = FontWeight.SemiBold) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Danh sách chi tiết kỹ thuật từng củ sạc
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(filteredChargers) { item ->
                val isSelected = item.modelCode == activeCharger.modelCode
                ChargerDetailSpecCard(
                    charger = item,
                    isSelected = isSelected,
                    onClick = { onSelectCharger(item) }
                )
            }
        }
    }
}

@Composable
fun ChargerDetailSpecCard(
    charger: ChargerSpecProfile,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) Color(0xFF00E676) else Color.Transparent
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFF1E3A2F) else Color(0xFF1E293B)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChargerProductIllustration(
                    charger = charger,
                    modifier = Modifier.size(76.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${charger.brand.uppercase()} • ${charger.maxPowerW}W MAX",
                            color = Color(charger.accentColorHex),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        if (isSelected) {
                            Text(
                                text = "✓ ĐANG CHỌN",
                                color = Color(0xFF00E676),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                    Text(
                        text = charger.productName,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Mã sản phẩm (Model): ${charger.modelCode}",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Cấu hình cổng: ${charger.portsConfig} • ${charger.dimensionsWeight}",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = Color.White.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(8.dp))

            // Mã nhận dạng linh kiện bên trong (Theo báo cáo kỹ thuật & Teardown)
            SpecRowItem("IC Điều khiển nguồn (PWM/PFC):", charger.primaryControllerIc)
            SpecRowItem("Chip giao thức (Protocol IC):", charger.protocolChipIc)
            SpecRowItem("Bán dẫn công suất (GaN/MOS):", charger.ganPowerFet)
            SpecRowItem("Các mức PDO công bố:", charger.pdoProfiles)
            SpecRowItem("Dải PPS / AVS:", charger.ppsOrAvsRange)

            Spacer(modifier = Modifier.height(8.dp))

            // Nhãn các chuẩn sạc nhanh hỗ trợ
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                charger.supportedProtocols.forEach { proto ->
                    Surface(
                        color = Color(0xFF0F172A),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = proto,
                            color = Color(0xFF7DD3FC),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SpecRowItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            modifier = Modifier.width(165.dp)
        )
        Text(
            text = value,
            color = Color(0xFFF1F5F9),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}

// ============================================================================
// HÌNH ẢNH SẢN PHẨM CỦ SẠC (VẼ VECTOR CANVAS ĐẶC TRƯNG THEO TỪNG MODEL)
// ============================================================================

@Composable
fun ChargerProductIllustration(
    charger: ChargerSpecProfile,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0F172A)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val bodyColor = Color(charger.bodyColorHex)
            val accentColor = Color(charger.accentColorHex)

            // Vẽ 2 chân cắm AC kim loại phía trên hoặc bên hông
            val prongColor = Color(0xFF94A3B8)
            drawRoundRect(
                color = prongColor,
                topLeft = Offset(w * 0.36f, h * 0.08f),
                size = Size(w * 0.07f, h * 0.16f),
                cornerRadius = CornerRadius(4f, 4f)
            )
            drawRoundRect(
                color = prongColor,
                topLeft = Offset(w * 0.57f, h * 0.08f),
                size = Size(w * 0.07f, h * 0.16f),
                cornerRadius = CornerRadius(4f, 4f)
            )

            // Vẽ thân củ sạc theo kiểu dáng công nghiệp của hãng
            val bodyLeft = if (charger.formFactor == "GAN_TOWER") w * 0.25f else w * 0.18f
            val bodyWidth = if (charger.formFactor == "GAN_TOWER") w * 0.50f else w * 0.64f
            val bodyTop = h * 0.22f
            val bodyHeight = h * 0.66f
            val radius = if (charger.formFactor == "PUDDING_CYLINDER") 28f else 18f

            drawRoundRect(
                color = bodyColor,
                topLeft = Offset(bodyLeft, bodyTop),
                size = Size(bodyWidth, bodyHeight),
                cornerRadius = CornerRadius(radius, radius)
            )

            // Dải màu nhận diện thương hiệu (LED Baseus / Viền cam Xiaomi / Điểm nhấn Belkin)
            drawRoundRect(
                color = accentColor,
                topLeft = Offset(bodyLeft + 8f, bodyTop + bodyHeight * 0.72f),
                size = Size(bodyWidth - 16f, 6f),
                cornerRadius = CornerRadius(3f, 3f)
            )

            // Vẽ khe cổng USB-C ở mặt trước
            drawRoundRect(
                color = Color(0xFF0F172A),
                topLeft = Offset(w * 0.40f, bodyTop + bodyHeight * 0.82f),
                size = Size(w * 0.20f, 8f),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }

        // Nhãn công suất & thương hiệu in trên thân củ sạc
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            val textColor = if (charger.bodyColorHex > 0xFF888888) Color(0xFF0F172A) else Color.White
            Text(
                text = charger.brand.take(6).uppercase(),
                color = textColor.copy(alpha = 0.75f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${charger.maxPowerW}W",
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = if (charger.ganPowerFet.contains("GaN")) "GaN" else "PD",
                color = Color(charger.accentColorHex),
                fontSize = 8.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

// ============================================================================
// TAB 3: TỰ ĐỘNG LƯU THÔNG SỐ SUỐT QUÁ TRÌNH SẠC (AUTO-LOGGING & CSV EXPORT)
// ============================================================================

@Composable
fun ChargingLogHistoryTab(
    autoRecordCharging: Boolean,
    onAutoRecordChange: (Boolean) -> Unit,
    isRecordingSession: Boolean,
    currentSamples: List<LogSample>,
    sessionStartTimeStr: String,
    sessionPeakPower: Float,
    sessionMaxTemp: Float,
    sessionAccumulatedMah: Float,
    lastSavedBanner: String,
    savedSessions: List<ChargingSessionRecord>,
    onForceSaveCurrent: () -> Unit,
    onClearHistory: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Hộp điều khiển tự động lưu suốt quá trình sạc
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Tự động ghi lại suốt quá trình sạc",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tự động mở phiên khi cắm sạc, ghi mẫu định kỳ (V, A, W, °C, %) và tự xuất file CSV khi rút sạc",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = autoRecordCharging,
                        onCheckedChange = onAutoRecordChange
                    )
                }

                if (lastSavedBanner.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "📁 $lastSavedBanner",
                        color = Color(0xFF00E676),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Thống kê phiên sạc hiện tại
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Đỉnh: ${String.format("%.1fW", sessionPeakPower)} • Nhiệt max: ${String.format("%.1f°C", sessionMaxTemp)} • Nạp: ${String.format("%.1f mAh", sessionAccumulatedMah)}",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Button(
                        onClick = onForceSaveCurrent,
                        enabled = currentSamples.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Lưu CSV ngay", fontSize = 11.sp)
                    }
                }
            }
        }

        // Bảng mẫu đo trực tiếp trong phiên đang sạc (Realtime Telemetry Stream)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Dòng dữ liệu ghi nhận trực tiếp (${currentSamples.size} mẫu trong phiên)",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F172A))
                        .padding(vertical = 6.dp, horizontal = 8.dp)
                ) {
                    Text("Giờ", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1.2f))
                    Text("Áp (V)", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("Dòng (A)", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("CS (W)", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("Nhiệt/Pin", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1.2f))
                }

                if (currentSamples.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Hãy cắm sạc để ứng dụng tự động ghi nhận thông số theo thời gian thực...",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(currentSamples.asReversed()) { s ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp, horizontal = 8.dp)
                            ) {
                                Text(s.timeFormatted, color = Color(0xFFCBD5E1), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1.2f))
                                Text(String.format("%.2fV", s.voltageV), color = Color(0xFFAB47BC), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                Text(String.format("%.2fA", s.currentA), color = Color(0xFF29B6F6), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                Text(String.format("%.1fW", s.powerW), color = Color(0xFF00E676), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                Text("${String.format("%.1f°", s.tempC)}/${s.levelPct}%", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1.2f))
                            }
                            Divider(color = Color.White.copy(alpha = 0.04f))
                        }
                    }
                }
            }
        }

        // Danh sách các phiên sạc đã lưu trong máy
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.9f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Lịch sử các phiên sạc đã lưu (${savedSessions.size})",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (savedSessions.isNotEmpty()) {
                        Text(
                            text = "Xóa tất cả",
                            color = Color(0xFFEF4444),
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { onClearHistory() }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))

                if (savedSessions.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Chưa có phiên sạc nào được lưu.",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(savedSessions) { ses ->
                            Surface(
                                color = Color(0xFF0F172A),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${ses.startTime} ➔ ${ses.endTime}",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Pin: ${ses.startLevel}% ➔ ${ses.endLevel}%",
                                            color = Color(0xFF00E676),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = "Củ sạc: ${ses.chargerModelCode} • Chuẩn: ${ses.detectedProtocol}",
                                        color = Color(0xFF38BDF8),
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = "P_max: ${String.format("%.1fW", ses.peakPowerW)} • P_tb: ${String.format("%.1fW", ses.avgPowerW)} • Nhiệt max: ${String.format("%.1f°C", ses.maxTempC)} • ${ses.samplesCount} mẫu CSV",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// CÁC COMPONENT DÙNG CHUNG (METRIC CARDS & BIỂU ĐỒ)
// ============================================================================

@Composable
fun MetricCard(
    modifier: Modifier = Modifier,
    title: String,
    mainValue: String,
    subValue: String,
    accentColor: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = title, color = Color(0xFF94A3B8), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = mainValue,
                color = accentColor,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(text = subValue, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
}

@Composable
fun SmallMetricCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, color = Color(0xFF94A3B8), fontSize = 11.sp)
            Spacer(modifier = Modifier.height(3.dp))
            Text(text = value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun PowerChart(
    dataPoints: List<Float>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (dataPoints.size < 2) return@Canvas

        val maxVal = (dataPoints.maxOrNull() ?: 10f).coerceAtLeast(5f) * 1.2f
        val stepX = size.width / (50 - 1).coerceAtLeast(1)

        val strokePath = Path()
        val fillPath = Path()

        dataPoints.forEachIndexed { index, value ->
            val x = index * stepX
            val y = size.height - (value / maxVal) * size.height
            if (index == 0) {
                strokePath.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                strokePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            if (index == dataPoints.lastIndex) {
                fillPath.lineTo(x, size.height)
                fillPath.close()
            }
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF00E676).copy(alpha = 0.35f), Color.Transparent)
            )
        )

        drawPath(
            path = strokePath,
            color = Color(0xFF00E676),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )

        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(
                color = Color.White.copy(alpha = 0.08f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}