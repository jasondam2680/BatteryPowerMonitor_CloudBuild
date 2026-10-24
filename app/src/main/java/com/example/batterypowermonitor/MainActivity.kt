package com.example.batterypowermonitor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// ============================================================================
// 1. MÔ HÌNH DỮ LIỆU ĐO LƯỜNG (TÁCH BẠCH PHÍA PIN & PHÍA CỔNG VBUS)
// ============================================================================

data class SysfsProbeReport(
    val vbusVoltageV: Float? = null,
    val ibusCurrentA: Float? = null,
    val vbusSourcePath: String = "Không đọc được (SELinux chặn hoặc ROM ẩn)",
    val ibusSourcePath: String = "Không đọc được (SELinux chặn hoặc ROM ẩn)",
    val kernelProtocolRaw: String = "",
    val kernelProtocolPath: String = "Bị chặn bởi SELinux (Cần Root/ADB để đọc sysfs)",
    val isVbusMeasured: Boolean = false,
    val isKernelProtocolVerified: Boolean = false
)

data class BatteryTelemetry(
    // Thông số phía Cell Pin (Battery-side) từ Android BatteryManager API
    val batCurrentMa: Float = 0f,
    val batCurrentAmp: Float = 0f,
    val batVoltageVolt: Float = 0f,       // Điện áp 1 cell (3.7V - 4.45V) từ EXTRA_VOLTAGE
    val cellSeriesCount: Int = 1,         // 1 = Single-cell (1S), 2 = Dual-cell nối tiếp (2S)
    val effectiveBatPowerW: Float = 0f,   // P_bat = V_bat * I_bat * cellSeriesCount

    // Thông số phía Cổng sạc (USB VBUS-side) nếu đọc được từ sysfs
    val sysfsReport: SysfsProbeReport = SysfsProbeReport(),
    val vbusInputPowerW: Float? = null,   // P_vbus = V_bus * I_bus (Chỉ có khi đọc được cả VBUS & IBUS)

    // Trạng thái chung
    val temperatureC: Float = 0f,
    val levelPercent: Int = 0,
    val isCharging: Boolean = false,
    val plugType: String = "Chưa cắm sạc",
    val healthStatus: String = "Tốt"
)

data class LogSample(
    val timestampMs: Long,
    val timeFormatted: String,
    val batVoltageV: Float,
    val batCurrentA: Float,
    val batPowerW: Float,
    val vbusVoltageV: Float?,
    val ibusCurrentA: Float?,
    val vbusPowerW: Float?,
    val tempC: Float,
    val levelPct: Int,
    val protocolLabel: String
)

data class ChargingSessionRecord(
    val sessionId: String,
    val startTime: String,
    var endTime: String,
    val startLevel: Int,
    var endLevel: Int,
    var peakBatPowerW: Float,
    var avgBatPowerW: Float,
    var peakVbusPowerW: Float?,
    var maxTempC: Float,
    var accumulatedMah: Float,
    var detectedProtocol: String,
    var referenceChargerName: String,
    var samplesCount: Int,
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
    val accentColorHex: Long,
    val productImageUrl: String,
    val verificationStatus: String, // "Đã kiểm chứng Teardown" hoặc "Thông số công bố của hãng (IC tham khảo)"
    val sourceTitle: String,
    val sourceUrl: String
)

data class ProtocolDetectionResult(
    val isVerifiedFromKernel: Boolean, // true = Đọc thật từ sysfs, false = Suy luận ước tính từ P_bat
    val primaryStandard: String,
    val technicalExplanation: String,
    val powerBasisNote: String,
    val verificationBadge: String
)

// ============================================================================
// 2. BỘ QUÉT ĐA ĐƯỜNG DẪN SYSFS (MULTI-PATH KERNEL SYSFS SCANNER)
// ============================================================================

object KernelSysfsScanner {
    private val vbusCandidates = listOf(
        "/sys/class/power_supply/usb/voltage_now",
        "/sys/class/power_supply/main-charger/voltage_now",
        "/sys/class/power_supply/ac/voltage_now",
        "/sys/class/power_supply/pc_port/voltage_now",
        "/sys/class/power_supply/charge_pump_master/cp_vbus",
        "/sys/class/power_supply/wireless/voltage_now"
    )

    private val ibusCandidates = listOf(
        "/sys/class/power_supply/usb/current_now",
        "/sys/class/power_supply/usb/input_current_now",
        "/sys/class/power_supply/main-charger/current_now",
        "/sys/class/power_supply/ac/current_now",
        "/sys/class/power_supply/charge_pump_master/cp_ibus"
    )

    private val protocolCandidates = listOf(
        "/sys/class/power_supply/usb/usb_type",
        "/sys/class/power_supply/usb/real_type",
        "/sys/class/power_supply/usb/pd_active",
        "/sys/class/power_supply/usb/typec_mode",
        "/sys/class/power_supply/bms/charge_type"
    )

    private fun readFirstReadableFile(paths: List<String>): Pair<String, String>? {
        for (p in paths) {
            try {
                val f = File(p)
                if (f.exists() && f.canRead()) {
                    val content = f.readText().trim()
                    if (content.isNotEmpty()) {
                        return p to content
                    }
                }
            } catch (_: Exception) {
                // SELinux EACCES hoặc IOException
            }
        }
        // Quét động thêm các node tcpm-* nếu có
        try {
            val psDir = File("/sys/class/power_supply")
            val children = psDir.listFiles() ?: emptyArray()
            for (child in children) {
                if (child.name.startsWith("tcpm") || child.name.contains("usb", ignoreCase = true)) {
                    val usbTypeFile = File(child, "usb_type")
                    if (usbTypeFile.exists() && usbTypeFile.canRead()) {
                        val txt = usbTypeFile.readText().trim()
                        if (txt.isNotEmpty()) return usbTypeFile.absolutePath to txt
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun normalizeSysfsVoltage(rawStr: String): Float? {
        val v = rawStr.toFloatOrNull() ?: return null
        val absV = abs(v)
        return when {
            absV <= 0.1f -> null
            absV > 100_000f -> absV / 1_000_000f // microVolt (µV) -> Volt
            absV > 100f -> absV / 1_000f         // milliVolt (mV) -> Volt
            else -> absV                         // Volt (V)
        }
    }

    private fun normalizeSysfsCurrent(rawStr: String): Float? {
        val i = rawStr.toFloatOrNull() ?: return null
        val absI = abs(i)
        return when {
            absI <= 0.001f -> null
            absI > 50_000f -> absI / 1_000_000f  // microAmp (µA) -> Amp
            absI > 50f -> absI / 1_000f          // milliAmp (mA) -> Amp
            else -> absI                         // Amp (A)
        }
    }

    fun probe(): SysfsProbeReport {
        val vbusHit = readFirstReadableFile(vbusCandidates)
        val ibusHit = readFirstReadableFile(ibusCandidates)

        val vbusV = vbusHit?.let { normalizeSysfsVoltage(it.second) }
        val ibusA = ibusHit?.let { normalizeSysfsCurrent(it.second) }

        val protoBuilder = mutableListOf<String>()
        var protoPathFound = ""
        for (p in protocolCandidates) {
            try {
                val f = File(p)
                if (f.exists() && f.canRead()) {
                    val txt = f.readText().trim()
                    if (txt.isNotEmpty() && txt != "0" && !txt.equals("Unknown", ignoreCase = true)) {
                        protoBuilder.add("${f.name}=$txt")
                        if (protoPathFound.isEmpty()) protoPathFound = p
                    }
                }
            } catch (_: Exception) {}
        }

        val protoRaw = protoBuilder.joinToString(" | ")
        return SysfsProbeReport(
            vbusVoltageV = vbusV,
            ibusCurrentA = ibusA,
            vbusSourcePath = vbusHit?.first ?: "SELinux chặn đọc VBUS (/sys/class/power_supply/*)",
            ibusSourcePath = ibusHit?.first ?: "SELinux chặn đọc IBUS (/sys/class/power_supply/*)",
            kernelProtocolRaw = protoRaw,
            kernelProtocolPath = if (protoPathFound.isNotEmpty()) protoPathFound else "Bị chặn bởi SELinux trên Android không Root",
            isVbusMeasured = (vbusV != null && vbusV >= 3.5f),
            isKernelProtocolVerified = protoRaw.isNotEmpty()
        )
    }
}

// ============================================================================
// 3. DANH MỤC CỦ SẠC THAM KHẢO & ĐỐI CHIẾU THỦ CÔNG (CÓ LINK NGUỒN RÕ RÀNG)
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
            primaryControllerIc = "PI InnoSwitch3-Pro (Tùy biến cho Apple)",
            protocolChipIc = "Infineon / Cypress CYPD3135 (USB-IF PD 3.0)",
            ganPowerFet = "Silicon MOSFET truyền thống (Không dùng GaN)",
            supportedProtocols = listOf("USB PD 3.0 (Fixed PDO)", "Apple 2.4A", "USB DCP 5V/1.5A"),
            pdoProfiles = "5V/3A (15W), 9V/2.22A (20W)",
            ppsOrAvsRange = "Không hỗ trợ PPS (Chỉ có 2 mức Fixed PDO)",
            dimensionsWeight = "42 × 42 × 27 mm • 60g",
            accentColorHex = 0xFF38BDF8,
            productImageUrl = "https://store.storeimages.cdn-apple.com/1/as-images.apple.com/is/MWVV3?wid=600&hei=600&fmt=jpeg&qlt=90&.v=Z0NhK081OTZRcVdjS2kxLys5ZGpDUWtuVHYzMERCZURia3c5SzJFOTlPaUxZRW05UENvR2I1ditwQTlTUkJwZHVBYVpQODZ2VDA1a1lBSm83UHYrTWc",
            verificationStatus = "Đã kiểm chứng Teardown (ChargerLAB)",
            sourceTitle = "ChargerLAB: Teardown of New Apple 20W Charger (A2940)",
            sourceUrl = "https://www.chargerlab.com/updated-power-ic-teardown-of-new-apple-20w-charger-a2940"
        ),
        ChargerSpecProfile(
            brand = "Apple",
            productName = "Apple 35W Dual USB-C Port Power Adapter",
            modelCode = "A2676",
            maxPowerW = 35,
            portsConfig = "2x USB-C (Tự động chia tải thông minh)",
            primaryControllerIc = "Power Integrations InnoSwitch4-CZ + ClampZero",
            protocolChipIc = "Infineon EZ-PD CCG3PA (Dual-Port PD 3.0)",
            ganPowerFet = "PowiGaN Integrated Switch",
            supportedProtocols = listOf("USB PD 3.0 (Fixed PDO)", "Apple 2.4A", "DCP"),
            pdoProfiles = "5V/3A, 9V/3A, 15V/2.33A, 20V/1.75A (35W Max)",
            ppsOrAvsRange = "Không hỗ trợ PPS • Chia tải 17.5W + 17.5W hoặc 27.5W + 7.5W",
            dimensionsWeight = "56 × 56 × 28 mm • 115g",
            accentColorHex = 0xFF38BDF8,
            productImageUrl = "https://store.storeimages.cdn-apple.com/4982/as-images.apple.com/is/MNWM3?wid=600&hei=600&fmt=jpeg&qlt=90&.v=1652736144126",
            verificationStatus = "Đã kiểm chứng Teardown (ChargerLAB)",
            sourceTitle = "ChargerLAB: Teardown of Apple A2676 35W Dual USB-C Charger",
            sourceUrl = "https://www.chargerlab.com/teardown-of-apple-a2676-35w-charger-dual-usb-c-port-fast-charging"
        ),
        ChargerSpecProfile(
            brand = "Apple",
            productName = "Apple 40W Dynamic Power Adapter (with 60W Max)",
            modelCode = "A3351",
            maxPowerW = 60,
            portsConfig = "1x USB-C (40W liên tục / 60W Peak Dynamic)",
            primaryControllerIc = "Đang cập nhật mã IC cụ thể (Tham khảo tài liệu Apple)",
            protocolChipIc = "USB-PD 3.1 Controller (Hỗ trợ Apple Dynamic Power)",
            ganPowerFet = "Cấu trúc GaN mật độ cao",
            supportedProtocols = listOf("USB PD 3.1", "USB PD 3.0", "Apple 2.4A"),
            pdoProfiles = "5V/3A, 9V/3A, 15V/2.67A (40W) • Peak 20V/3A (60W)",
            ppsOrAvsRange = "Cơ chế Dynamic Power theo tài liệu kỹ thuật iPhone 17 Pro",
            dimensionsWeight = "50 × 50 × 28 mm • 98g",
            accentColorHex = 0xFF0284C7,
            productImageUrl = "https://store.storeimages.cdn-apple.com/4982/as-images.apple.com/is/MNWM3?wid=600&hei=600&fmt=jpeg&qlt=90&.v=1652736144126",
            verificationStatus = "Thông số công bố của Apple Support",
            sourceTitle = "Apple Support: iPhone 17 Pro Tech Specs (Model A3351)",
            sourceUrl = "https://support.apple.com/en-us/125091"
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
            ppsOrAvsRange = "AVS (Adjustable Voltage Supply): 15V–28V @ 140W Max (Không có PPS 3.3-21V)",
            dimensionsWeight = "95.7 × 74.4 × 28.5 mm • 285g",
            accentColorHex = 0xFF38BDF8,
            productImageUrl = "https://www.chargerlab.com/wp-content/uploads/2026/03/2026031908463570-1.jpg",
            verificationStatus = "Đã kiểm chứng Teardown (ChargerLAB)",
            sourceTitle = "ChargerLAB: Teardown of Apple 140W USB-C Power Adapter (A3607)",
            sourceUrl = "https://www.chargerlab.com/teardown-of-apple-140w-usb-c-power-adapter-a3607"
        ),

        // --- BASEUS ---
        ChargerSpecProfile(
            brand = "Baseus",
            productName = "Baseus GaN2 Quick Charger Dual-C 45W",
            modelCode = "CCGAN-M02",
            maxPowerW = 45,
            portsConfig = "2x USB-C (30W + 18W khi cắm 2 cổng)",
            primaryControllerIc = "ON Semiconductor NCP1342 QR PWM (Tham khảo dòng GaN2)",
            protocolChipIc = "iSmartWare SW3516H Dual-Port Buck & Protocol",
            ganPowerFet = "Navitas GaNFast NV6115",
            supportedProtocols = listOf("USB PD 3.0", "PPS", "QC 3.0", "QC 2.0", "SCP", "FCP", "AFC"),
            pdoProfiles = "5V/3A, 9V/3A, 12V/3A, 15V/3A, 20V/2.25A (45W)",
            ppsOrAvsRange = "PPS: 3.3V–11V @ 3A (33W Max)",
            dimensionsWeight = "62 × 32 × 30 mm • 85g",
            accentColorHex = 0xFF38BDF8,
            productImageUrl = "https://www.baseus.com/cdn/shop/products/Baseus_GaN2_3_Ports_Fast_Charger_65W_Black_1_front_side.jpg?v=1667904127",
            verificationStatus = "Thông số công bố của hãng (IC tham khảo dòng GaN2)",
            sourceTitle = "Baseus GaN2 45W Dual Type-C Official Specs",
            sourceUrl = "https://lifemobile.lk/product/baseus-gan-45w-dual-type-c-mini-quick-charger/"
        ),
        ChargerSpecProfile(
            brand = "Baseus",
            productName = "Baseus GaN5 Pro Fast Charger 65W (2C1A)",
            modelCode = "CCGAN65C5",
            maxPowerW = 65,
            portsConfig = "2x USB-C + 1x USB-A",
            primaryControllerIc = "iSmartWare SW1125P Integrated GaN Master IC (SR: SW1608)",
            protocolChipIc = "2x iSmartWare SW3516P Buck & Multi-Protocol IC",
            ganPowerFet = "iSmartWare SW1125P Monolithic GaN FET",
            supportedProtocols = listOf("USB PD 3.0", "PPS", "QC 3.0", "QC 2.0", "SCP", "FCP", "AFC"),
            pdoProfiles = "5V/3A, 9V/3A, 12V/3A, 15V/3A, 20V/3.25A (65W)",
            ppsOrAvsRange = "PPS: 3.3V–11V @ 5A (55W) / 3.3V–20V @ 3A",
            dimensionsWeight = "61 × 36 × 32 mm • 120g",
            accentColorHex = 0xFF00E5FF,
            productImageUrl = "https://baseusshop.hu/wp-content/uploads/2024/01/CCGP120201_1.jpeg",
            verificationStatus = "Đã kiểm chứng Teardown (ChargerLAB)",
            sourceTitle = "ChargerLAB: Teardown of Baseus 65W GaN5 Pro (CCGAN65C5)",
            sourceUrl = "https://www.chargerlab.com/teardown-of-baseus-65w-gan5-pro-charger-ccgan65c5"
        ),
        ChargerSpecProfile(
            brand = "Baseus",
            productName = "Baseus GaN2 Pro 100W / 120W Fast Charger",
            modelCode = "CCGAN100US / CCGAN-J01",
            maxPowerW = 100,
            portsConfig = "2x USB-C + 2x USB-A (hoặc 2C1A)",
            primaryControllerIc = "NXP TEA2016AAT PFC+LLC Digital Controller",
            protocolChipIc = "Injoinic IP2726S (Chứng nhận Qualcomm QC5)",
            ganPowerFet = "Navitas NV6125 + NV6127 GaNFast",
            supportedProtocols = listOf("USB PD 3.0", "PPS", "QC 5", "QC 4+", "QC 3.0", "SCP", "FCP", "AFC"),
            pdoProfiles = "5V/3A, 9V/3A, 12V/3A, 15V/3A, 20V/5A (100W)",
            ppsOrAvsRange = "PPS: 3.3V–21V @ 5A (100W Max)",
            dimensionsWeight = "94 × 54 × 30 mm • 220g",
            accentColorHex = 0xFF0EA5E9,
            productImageUrl = "https://shop.baltrade.eu/img/product/big/baseus-gan2-pro-ccgan-j01-120w-szybka-ladowarka-sieciowa-z-2-gniazdami-usb-c-pps-pd-3-0-oraz-usb-8.jpg",
            verificationStatus = "Đã kiểm chứng Teardown (ChargerLAB)",
            sourceTitle = "ChargerLAB: Teardown of Baseus 100W GaN II Fast Charger (QC 5.0)",
            sourceUrl = "https://www.chargerlab.com/teardown-of-baseus-100w-gan-ii-fast-charger-qc-5-0"
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
            accentColorHex = 0xFFF59E0B,
            productImageUrl = "https://www.startech.com.bd/image/cache/catalog/mobile-accessories/baseus/gan5-pro/gan5-pro-01-500x500.webp",
            verificationStatus = "Đã kiểm chứng Teardown (ChargerLAB)",
            sourceTitle = "ChargerLAB: Teardown of Baseus 140W PD3.1 GaN5 Pro Charger",
            sourceUrl = "https://www.chargerlab.com/teardown-of-baseus-140w-pd3-1-gan-5-pro-fast-charger-2c1a"
        ),

        // --- BELKIN ---
        ChargerSpecProfile(
            brand = "Belkin",
            productName = "Belkin BoostCharge Compact 45W GaN Wall Charger",
            modelCode = "WCA013 (WCA013dqWH / WCA013myWH)",
            maxPowerW = 45,
            portsConfig = "1x USB-C (45W PD 3.1 + PPS)",
            primaryControllerIc = "Integrated QR GaN Controller + Allpower APG077N01G SR",
            protocolChipIc = "Injoinic IP2723T (USB-IF PD 3.0 & PPS TID: 3135)",
            ganPowerFet = "Allpower APNE70R200K GaN Power Device",
            supportedProtocols = listOf("USB PD 3.1 / 3.0", "PPS", "QC 4+", "QC 3.0", "FCP", "SCP", "AFC", "Apple 2.4A"),
            pdoProfiles = "5V/3A, 9V/3A, 12V/3A, 15V/3A, 20V/2.25A (45W)",
            ppsOrAvsRange = "PPS: 5.0V–16.0V @ 2.8A–3.0A (Theo Belkin FAQ & KM003C Test)",
            dimensionsWeight = "39.5 × 34.9 × 34.8 mm • 57g",
            accentColorHex = 0xFF10B981,
            productImageUrl = "https://www.belkin.com/dw/image/v2/BGBH_PRD/on/demandware.static/-/Sites-master-product-catalog-blk/default/dwca329891/images/hi-res/6/6c8c8be3a5f21f72_belkin-wca013-my-wh-45w-cubic-wall-charger-gallery-hero-shot-01__lz__en_SG.jpg?sfrm=png",
            verificationStatus = "Đã kiểm chứng Teardown & Belkin Official FAQ",
            sourceTitle = "ChargerLAB Teardown WCA013 & Belkin Support Article #336958",
            sourceUrl = "https://www.chargerlab.com/teardown-of-belkin-boostcharge-45w-gan-wall-charger-spider-man-limited-edition-wca013yz"
        ),
        ChargerSpecProfile(
            brand = "Belkin",
            productName = "Belkin BoostCharge Pro Dual USB-C 65W GaN Charger",
            modelCode = "WCH013 (WCH013dqWH / BBC013)",
            maxPowerW = 65,
            portsConfig = "2x USB-C (65W Single hoặc 45W + 20W Dual)",
            primaryControllerIc = "Power Integrations InnoSwitch3-Pro / Navitas GaN (Tham khảo)",
            protocolChipIc = "USB-PD 3.0 + PPS Dual-Port Controller",
            ganPowerFet = "Navitas GaNFast Series",
            supportedProtocols = listOf("USB PD 3.0", "PPS", "Apple 2.4A", "DCP"),
            pdoProfiles = "5V/3A, 9V/3A, 15V/3A, 20V/3.25A (65W Max)",
            ppsOrAvsRange = "PPS: 3.3V–21.0V @ 3.25A",
            dimensionsWeight = "58 × 42 × 30 mm • 118g",
            accentColorHex = 0xFF10B981,
            productImageUrl = "https://www.belkin.com/dw/image/v2/BGBH_PRD/on/demandware.static/-/Sites-master-product-catalog-blk/default/dw76447a99/images/hi-res/6/6eba9cefdc53b129_WCH013_Belkin_65W%20Dual%20USB-C%20Wall%20Charger_DQ%2065W%2034%20WHITE_WEB.jpg?sfrm=png",
            verificationStatus = "Thông số công bố của Belkin Support (#333638)",
            sourceTitle = "Belkin Official Support: WCH013 Power Specifications",
            sourceUrl = "https://www.belkin.com/support-article/?articleNum=333638"
        ),
        ChargerSpecProfile(
            brand = "Belkin",
            productName = "Belkin BoostCharge Pro 140W 4-Port GaN Charger",
            modelCode = "WCH014 (WCH014dqWH / WCH014myWH)",
            maxPowerW = 140,
            portsConfig = "3x USB-C + 1x USB-A (C1: 140W, C2/C3: 65W, A: 12W)",
            primaryControllerIc = "Onsemi NCP1622 PFC + Joulwatt JW1550A (140W) + SouthChip SC3023 (65W)",
            protocolChipIc = "Weltrend WT6676 (C1 PD 3.1) + 2x Canyon CY2333 (C2/C3 QC5/PD3.0)",
            ganPowerFet = "Navitas NV6136C + Innoscience INN650DA260A GaN + BASiC SiC Diode",
            supportedProtocols = listOf("USB PD 3.1 EPR", "USB PD 3.0", "PPS", "QC 5", "QC 3.0", "Apple 2.4A"),
            pdoProfiles = "C1: 5V/3A, 9V/3A, 15V/3A, 20V/5A, 28V/5A (140W) • C2/C3: 20V/3.25A",
            ppsOrAvsRange = "C1 PPS: 5V–21V @ 5A • C2/C3 PPS: 3.3V–21V @ 3.25A",
            dimensionsWeight = "96.0 × 77.3 × 30.9 mm • 327g",
            accentColorHex = 0xFF2563EB,
            productImageUrl = "https://www.belkin.com/dw/image/v2/BGBH_PRD/on/demandware.static/-/Sites-master-product-catalog-blk/default/dw29232704/images/hi-res/6/6eb387e7aa101a0b_WCH014myWH_140W4PortGaNWallCharger_(MPW257)_shot01_web.jpg?sw=700&sh=700&sm=fit&sfrm=png",
            verificationStatus = "Đã kiểm chứng Teardown (ChargerLAB)",
            sourceTitle = "ChargerLAB: Teardown of Belkin 140W 4-Port GaN (WCH014)",
            sourceUrl = "https://www.chargerlab.com/teardown-of-belkin-boostcharge-pro-140w-4-port-gan-wall-charger-wch014"
        ),

        // --- XIAOMI ---
        ChargerSpecProfile(
            brand = "Xiaomi",
            productName = "Xiaomi 33W USB-A Fast Charger",
            modelCode = "MDY-16-EF / MDY-11-EZ",
            maxPowerW = 33,
            portsConfig = "1x USB-A (Chân đặc trưng Xiaomi hỗ trợ PD & QC)",
            primaryControllerIc = "Power Integrations InnoSwitch3-CP",
            protocolChipIc = "Weltrend WT6615F / Fitipower FP6606C",
            ganPowerFet = "Silicon SuperJunction MOSFET",
            supportedProtocols = listOf("Xiaomi 33W Mi Turbo", "USB PD 3.0", "PPS", "QC 3.0", "QC 2.0"),
            pdoProfiles = "5V/3A, 9V/3A, 12V/2.25A, 20V/1.35A",
            ppsOrAvsRange = "Mi PPS: 3.3V–11V @ 3A (33W Max)",
            dimensionsWeight = "46.8 × 28.0 × 55.4 mm • 82g",
            accentColorHex = 0xFFFF6900,
            productImageUrl = "https://i03.appmifile.com/702_item_in/24/07/2026/654a45ba186044cf86a88136b72aa3cd.png?thumb=1&w=400&q=85",
            verificationStatus = "Đã kiểm chứng Teardown (ChargerLAB)",
            sourceTitle = "ChargerLAB: Teardown of Xiaomi 33W Charger (MDY-16-EF)",
            sourceUrl = "https://www.chargerlab.com/teardown-of-xiaomi-33w-usb-a-charger-mdy-16-ef"
        ),
        ChargerSpecProfile(
            brand = "Xiaomi",
            productName = "Xiaomi 67W GaN 3-Port Charger (2C1A)",
            modelCode = "MDY-19-EA",
            maxPowerW = 67,
            portsConfig = "2x USB-C + 1x USB-A (67W Surge / 65W PD)",
            primaryControllerIc = "HYASiC HYC3657 + HYC906M Integrated GaN Solution",
            protocolChipIc = "2x iSmartWare SW356M Buck & Protocol IC + Vergiga VS3620GEMC",
            ganPowerFet = "HYASiC Integrated GaN FET",
            supportedProtocols = listOf("Xiaomi 67W Surge Turbo", "USB PD 3.0", "PPS", "QC 5", "QC 3.0", "DCP"),
            pdoProfiles = "5V/3A, 9V/3A, 15V/3A, 20V/3.25A (65W PD)",
            ppsOrAvsRange = "PPS 1: 3.6V–11V @ 6.1A (67W) • PPS 2: 3.6V–20V @ 3.25A",
            dimensionsWeight = "50.0 × 50.1 × 30.1 mm • 104g",
            accentColorHex = 0xFFFF6900,
            productImageUrl = "https://i02.appmifile.com/mi-com-product/fly-birds/xiaomi-67w-gan-dual-port-charger/m/5467b14b0fdfde357b7679f36a113d96.jpg",
            verificationStatus = "Đã kiểm chứng Teardown (ChargerLAB)",
            sourceTitle = "ChargerLAB: Teardown of Xiaomi 67W GaN Charger (MDY-19-EA)",
            sourceUrl = "https://www.chargerlab.com/teardown-of-xiaomi-67w-gan-charger-mdy-19-ea"
        ),
        ChargerSpecProfile(
            brand = "Xiaomi",
            productName = "Xiaomi 100W GaN Charger (Xiaomi 17 Series)",
            modelCode = "MDY-18-EW",
            maxPowerW = 100,
            portsConfig = "1x USB-C (Zero Standby Power Architecture)",
            primaryControllerIc = "SouthChip SC3005Z Primary Controller + SC3501 SR",
            protocolChipIc = "SouthChip Custom Protocol IC 'C4' (Mi HyperCharge + PD)",
            ganPowerFet = "Navitas NV6144C GaNFast + Silan Synchronous Rectifier",
            supportedProtocols = listOf("Xiaomi 100W HyperCharge", "USB PD 3.0 (65W)", "PPS", "QC 4+", "QC 3.0"),
            pdoProfiles = "5V/3A, 9V/3A, 15V/3A, 20V/3.25A (65W PD) • 20V/5A (100W)",
            ppsOrAvsRange = "Proprietary PPS: 3.6V–20V @ 5A (100W Max)",
            dimensionsWeight = "55 × 49 × 28 mm • 125g",
            accentColorHex = 0xFFFF6900,
            productImageUrl = "https://i02.appmifile.com/630_operatorx_operatorx_opx/17/12/2025/d2bac4a8d8c6a77d87fe9788ec09ab53.png?thumb=1&w=400&q=85",
            verificationStatus = "Đã kiểm chứng Teardown (ChargerLAB)",
            sourceTitle = "ChargerLAB: Teardown of Xiaomi 100W GaN Charger (MDY-18-EW)",
            sourceUrl = "https://www.chargerlab.com/teardown-of-xiaomi-100w-gan-charger-for-xiaomi-17-series-mdy-18-ew"
        ),
        ChargerSpecProfile(
            brand = "Xiaomi",
            productName = "Xiaomi 120W HyperCharge Combo (Type-A)",
            modelCode = "MDY-14-EE / MDY-14-EG",
            maxPowerW = 120,
            portsConfig = "1x USB-A (Kèm cáp 6A chân C có E-Marker riêng)",
            primaryControllerIc = "ON Semiconductor NCP1342 + Custom PFC (Tham khảo dòng 120W)",
            protocolChipIc = "Weltrend WT6633P (Giao thức mã hóa Xiaomi HyperCharge)",
            ganPowerFet = "Navitas GaNFast NV6127 / Innoscience GaN",
            supportedProtocols = listOf("Xiaomi 120W HyperCharge", "USB PD 3.0 (65W)", "PPS", "QC 3.0", "QC 2.0"),
            pdoProfiles = "5V/3A, 9V/3A, 11V/6A, 20V/6A (120W Max) • PD: 20V/3.25A (65W)",
            ppsOrAvsRange = "Mi PPS: 3.6V–11V @ 6A / 3.6V–20V @ 6A (120W)",
            dimensionsWeight = "56.0 × 60.0 × 28.8 mm • 150g",
            accentColorHex = 0xFFFF5500,
            productImageUrl = "https://i02.appmifile.com/452_operator_sg/23/09/2024/aefc15ca78ce28b9679fc75d8723ce7c.jpg",
            verificationStatus = "Thông số công bố của Xiaomi Global",
            sourceTitle = "Xiaomi Official: 120W HyperCharge Combo Specifications",
            sourceUrl = "https://www.mi.com/my/product/xiaomi-120w-hypercharge-combo-type-a/"
        )
    )

    /**
     * Phân tích cơ chế sạc nhanh ĐỘC LẬP (Không dùng củ sạc để suy luận vòng tròn)
     */
    fun analyzeChargingProtocol(telemetry: BatteryTelemetry): ProtocolDetectionResult {
        if (!telemetry.isCharging) {
            return ProtocolDetectionResult(
                isVerifiedFromKernel = true,
                primaryStandard = "Chưa cắm sạc (Đang xả pin)",
                technicalExplanation = "Thiết bị đang lấy điện từ cell pin. Không có giao tiếp thương lượng nguồn VBUS.",
                powerBasisNote = "P_bat = ${String.format("%.2fW", telemetry.effectiveBatPowerW)} (V_bat = ${String.format("%.2fV", telemetry.batVoltageVolt)})",
                verificationBadge = "Trạng thái chờ"
            )
        }

        val sys = telemetry.sysfsReport
        val pUsed = telemetry.vbusInputPowerW ?: telemetry.effectiveBatPowerW
        val powerSourceStr = if (telemetry.vbusInputPowerW != null) {
            "Đo từ VBUS×IBUS (${String.format("%.2fW", pUsed)})"
        } else {
            "Ước tính từ phía cell pin P_bat (${String.format("%.2fW", pUsed)})"
        }

        // TRƯỜNG HỢP 1: Đọc được cờ xác thực thực tế từ Kernel sysfs
        if (sys.isKernelProtocolVerified) {
            val rawUpper = sys.kernelProtocolRaw.uppercase()
            val parsedName = when {
                rawUpper.contains("PD_PPS") || rawUpper.contains("APDO") ->
                    "USB PD 3.0 / 3.1 — Chế độ PPS (APDO)"
                rawUpper.contains("PD_DRP") || rawUpper.contains("PD") || rawUpper.contains("PD_ACTIVE=1") ->
                    "USB Power Delivery (USB PD Fixed PDO)"
                rawUpper.contains("HVDCP_3P5") -> "Qualcomm Quick Charge 3+ (QC 3.5)"
                rawUpper.contains("HVDCP_3") -> "Qualcomm Quick Charge 3.0"
                rawUpper.contains("HVDCP") -> "Qualcomm Quick Charge 2.0 (HVDCP)"
                rawUpper.contains("CDP") -> "USB Charging Downstream Port (CDP 5V/1.5A)"
                rawUpper.contains("DCP") -> "USB Dedicated Charging Port (DCP / Adapter)"
                else -> "Chuẩn báo từ Kernel: ${sys.kernelProtocolRaw}"
            }

            return ProtocolDetectionResult(
                isVerifiedFromKernel = true,
                primaryStandard = parsedName,
                technicalExplanation = "Đọc trực tiếp từ node Kernel (${sys.kernelProtocolPath}): [${sys.kernelProtocolRaw}].",
                powerBasisNote = powerSourceStr,
                verificationBadge = "✓ XÁC THỰC TỪ KERNEL"
            )
        }

        // TRƯỜNG HỢP 2: SELinux chặn đọc sysfs -> Chỉ phân loại theo ngưỡng công suất nạp vào pin & cảnh báo rõ
        return when {
            pUsed >= 50f -> ProtocolDetectionResult(
                isVerifiedFromKernel = false,
                primaryStandard = "Sạc siêu nhanh công suất cao (>50W tại Pin)",
                technicalExplanation = "SELinux chặn đọc node USB PD của Kernel. Với mức công suất nạp vào cell pin đạt ${String.format("%.1fW", pUsed)}, mạch sạc đang chạy chế độ Charge Pump dòng cao (PPS APDO / PD 3.1 hoặc chuẩn riêng của hãng).",
                powerBasisNote = "$powerSourceStr • Lưu ý: Chưa thể phân biệt giữa PD 3.0/3.1/3.2 nếu không có quyền Root hoặc USB Tester.",
                verificationBadge = "⚠️ SUY LUẬN TỪ P_BAT (SELINUX CHẶN SYSFS)"
            )
            pUsed >= 22f -> ProtocolDetectionResult(
                isVerifiedFromKernel = false,
                primaryStandard = "Sạc nhanh USB-PD / PPS (Ngưỡng 22W–50W tại Pin)",
                technicalExplanation = "SELinux chặn đọc cờ thương lượng PD. Công suất nạp phía pin đạt ${String.format("%.1fW", pUsed)} (tương đương đầu vào VBUS khoảng ${String.format("%.0fW–%.0fW", pUsed * 1.1f, pUsed * 1.2f)} sau hao phí chuyển đổi).",
                powerBasisNote = "$powerSourceStr • Android API không cho biết củ sạc đang dùng PD 3.0, PD 3.1 hay PD 3.2.",
                verificationBadge = "⚠️ SUY LUẬN TỪ P_BAT (CHƯA XÁC THỰC KERNEL)"
            )
            pUsed >= 12f -> ProtocolDetectionResult(
                isVerifiedFromKernel = false,
                primaryStandard = "Sạc nhanh cơ bản 15W–22W (PD 9V / QC 2.0–3.0 / PPS)",
                technicalExplanation = "SELinux chặn đọc node USB. Công suất phía pin (${String.format("%.1fW", pUsed)}) tương ứng với cấu hình sạc nhanh 9V/2A–2.5A hoặc giai đoạn giảm dòng khi pin >60%.",
                powerBasisNote = powerSourceStr,
                verificationBadge = "⚠️ SUY LUẬN TỪ P_BAT (CHƯA XÁC THỰC KERNEL)"
            )
            else -> {
                val isHighSoc = telemetry.levelPercent >= 75
                ProtocolDetectionResult(
                    isVerifiedFromKernel = false,
                    primaryStandard = if (isHighSoc) "Giai đoạn sạc bão hòa CV Stage (>75% Pin)" else "Sạc tiêu chuẩn 5V (<12W tại Pin)",
                    technicalExplanation = if (isHighSoc) {
                        "Khi pin đạt ${telemetry.levelPercent}%, IC quản lý nguồn trong điện thoại chủ động hạ dòng nạp xuống ${String.format("%.2fA", telemetry.batCurrentAmp)} để bảo vệ cell pin dù đang cắm củ sạc nhanh."
                    } else {
                        "Công suất nạp thấp (${String.format("%.1fW", pUsed)}). Đang sạc chuẩn 5V cơ bản hoặc máy đang giảm dòng do nhiệt độ (${String.format("%.1f°C", telemetry.temperatureC)})."
                    },
                    powerBasisNote = powerSourceStr,
                    verificationBadge = "⚠️ SUY LUẬN TỪ P_BAT"
                )
            }
        }
    }
}

// ============================================================================
// 4. SINGLETON REPOSITORY (GIỮ TRẠNG THÁI QUA XOAY MÀN HÌNH + STREAMING CSV)
// ============================================================================

object TelemetryRepository {
    private const val PREFS_NAME = "battery_monitor_v3_prefs"
    private const val KEY_SESSIONS = "saved_sessions_v3_json"
    private const val MAX_UI_SAMPLES = 120 // Giới hạn 120 điểm trong RAM để không tràn bộ nhớ

    private val _telemetry = MutableStateFlow(BatteryTelemetry())
    val telemetry: StateFlow<BatteryTelemetry> = _telemetry.asStateFlow()

    private val _powerHistory = MutableStateFlow<List<Float>>(emptyList())
    val powerHistory: StateFlow<List<Float>> = _powerHistory.asStateFlow()

    private val _recentSamples = MutableStateFlow<List<LogSample>>(emptyList())
    val recentSamples: StateFlow<List<LogSample>> = _recentSamples.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _autoRecordEnabled = MutableStateFlow(true)
    val autoRecordEnabled: StateFlow<Boolean> = _autoRecordEnabled.asStateFlow()

    private val _cellSeriesCount = MutableStateFlow(1)
    val cellSeriesCount: StateFlow<Int> = _cellSeriesCount.asStateFlow()

    private val _refreshIntervalMs = MutableStateFlow(1000L)
    val refreshIntervalMs: StateFlow<Long> = _refreshIntervalMs.asStateFlow()

    private val _referenceCharger = MutableStateFlow(ChargerCatalogRepository.chargers[8]) // Mặc định Belkin WCA013 45W
    val referenceCharger: StateFlow<ChargerSpecProfile> = _referenceCharger.asStateFlow()

    private val _savedSessions = MutableStateFlow<List<ChargingSessionRecord>>(emptyList())
    val savedSessions: StateFlow<List<ChargingSessionRecord>> = _savedSessions.asStateFlow()

    private val _statusBanner = MutableStateFlow("")
    val statusBanner: StateFlow<String> = _statusBanner.asStateFlow()

    // Biến phiên sạc đang chạy (Streaming trực tiếp ra file CSV trên đĩa)
    private var activeCsvFile: File? = null
    private var activeSessionRecord: ChargingSessionRecord? = null
    private var powerSumW: Double = 0.0
    private var lastSampleTimestampMs: Long = 0L

    fun initHistory(context: Context) {
        _savedSessions.value = loadSessionsFromDisk(context)
    }

    fun setCellSeriesCount(count: Int) {
        _cellSeriesCount.value = count.coerceIn(1, 2)
    }

    fun setRefreshInterval(ms: Long) {
        _refreshIntervalMs.value = ms
    }

    fun setAutoRecord(enabled: Boolean) {
        _autoRecordEnabled.value = enabled
    }

    fun setReferenceCharger(charger: ChargerSpecProfile) {
        _referenceCharger.value = charger
    }

    fun updateTelemetrySnapshot(context: Context, newSnap: BatteryTelemetry) {
        _telemetry.value = newSnap

        // Cập nhật đồ thị (tối đa 60 điểm)
        val displayPower = newSnap.vbusInputPowerW ?: newSnap.effectiveBatPowerW
        val updatedHist = (_powerHistory.value + displayPower).takeLast(60)
        _powerHistory.value = updatedHist

        val proto = ChargerCatalogRepository.analyzeChargingProtocol(newSnap)

        // Tự động mở / đóng phiên ghi CSV trực tiếp xuống đĩa
        if (newSnap.isCharging && _autoRecordEnabled.value && !_isRecording.value) {
            startStreamingSession(context, newSnap, proto.primaryStandard)
        } else if (!newSnap.isCharging && _isRecording.value) {
            stopAndFinalizeSession(context, newSnap.levelPercent)
        }

        // Nếu đang ghi phiên sạc -> Append trực tiếp 1 dòng vào file CSV trên đĩa
        if (_isRecording.value && newSnap.isCharging) {
            appendSampleToCsv(newSnap, proto.primaryStandard)
        }
    }

    fun startStreamingSession(context: Context, snap: BatteryTelemetry, protocolLabel: String) {
        if (_isRecording.value) return
        val sdfFile = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val sdfHuman = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
        val now = Date()

        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val csvFile = File(dir, "ChargingLog_${sdfFile.format(now)}.csv")

        runCatching {
            FileOutputStream(csvFile, false).bufferedWriter().use { writer ->
                writer.write("Thoi_Gian,V_Bat(V),I_Bat(A),P_Bat_Est(W),So_Cell,V_Bus(V),I_Bus(A),P_Vbus(W),Nhiet_Do(C),Pin(%),Chuan_Sac\n")
            }
        }

        activeCsvFile = csvFile
        powerSumW = 0.0
        lastSampleTimestampMs = System.currentTimeMillis()
        _recentSamples.value = emptyList()

        activeSessionRecord = ChargingSessionRecord(
            sessionId = "SES_${System.currentTimeMillis()}",
            startTime = sdfHuman.format(now),
            endTime = "Đang sạc...",
            startLevel = snap.levelPercent,
            endLevel = snap.levelPercent,
            peakBatPowerW = snap.effectiveBatPowerW,
            avgBatPowerW = snap.effectiveBatPowerW,
            peakVbusPowerW = snap.vbusInputPowerW,
            maxTempC = snap.temperatureC,
            accumulatedMah = 0f,
            detectedProtocol = protocolLabel,
            referenceChargerName = "${_referenceCharger.value.brand} ${_referenceCharger.value.modelCode}",
            samplesCount = 0,
            csvFilePath = csvFile.absolutePath
        )

        _isRecording.value = true
        _statusBanner.value = "Đang ghi trực tiếp (Streaming) vào: ${csvFile.name}"
    }

    private fun appendSampleToCsv(snap: BatteryTelemetry, protocolLabel: String) {
        val csvFile = activeCsvFile ?: return
        val rec = activeSessionRecord ?: return
        val nowMs = System.currentTimeMillis()
        val sdfTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timeStr = sdfTime.format(Date(nowMs))

        val deltaHours = if (lastSampleTimestampMs > 0L) (nowMs - lastSampleTimestampMs) / 3_600_000f else 0f
        lastSampleTimestampMs = nowMs

        rec.samplesCount += 1
        rec.endLevel = snap.levelPercent
        rec.accumulatedMah += abs(snap.batCurrentMa) * deltaHours
        powerSumW += snap.effectiveBatPowerW
        rec.avgBatPowerW = (powerSumW / rec.samplesCount).toFloat()

        if (snap.effectiveBatPowerW > rec.peakBatPowerW) rec.peakBatPowerW = snap.effectiveBatPowerW
        if (snap.vbusInputPowerW != null) {
            val curPeakVbus = rec.peakVbusPowerW ?: 0f
            if (snap.vbusInputPowerW > curPeakVbus) rec.peakVbusPowerW = snap.vbusInputPowerW
        }
        if (snap.temperatureC > rec.maxTempC) rec.maxTempC = snap.temperatureC
        rec.detectedProtocol = protocolLabel

        // Ghi nối tiếp (Append) ngay lập tức xuống đĩa để nếu tắt máy đột ngột vẫn còn nguyên file CSV
        val vbusStr = snap.sysfsReport.vbusVoltageV?.let { String.format(Locale.US, "%.3f", it) } ?: "N/A"
        val ibusStr = snap.sysfsReport.ibusCurrentA?.let { String.format(Locale.US, "%.3f", it) } ?: "N/A"
        val pvbusStr = snap.vbusInputPowerW?.let { String.format(Locale.US, "%.2f", it) } ?: "N/A"

        runCatching {
            FileOutputStream(csvFile, true).bufferedWriter().use { writer ->
                writer.write(
                    "$timeStr,${String.format(Locale.US, "%.3f", snap.batVoltageVolt)}," +
                            "${String.format(Locale.US, "%.3f", snap.batCurrentAmp)}," +
                            "${String.format(Locale.US, "%.2f", snap.effectiveBatPowerW)}," +
                            "${snap.cellSeriesCount}S,$vbusStr,$ibusStr,$pvbusStr," +
                            "${String.format(Locale.US, "%.1f", snap.temperatureC)}," +
                            "${snap.levelPercent},\"$protocolLabel\"\n"
                )
            }
        }

        val sampleObj = LogSample(
            timestampMs = nowMs,
            timeFormatted = timeStr,
            batVoltageV = snap.batVoltageVolt,
            batCurrentA = snap.batCurrentAmp,
            batPowerW = snap.effectiveBatPowerW,
            vbusVoltageV = snap.sysfsReport.vbusVoltageV,
            ibusCurrentA = snap.sysfsReport.ibusCurrentA,
            vbusPowerW = snap.vbusInputPowerW,
            tempC = snap.temperatureC,
            levelPct = snap.levelPercent,
            protocolLabel = protocolLabel
        )
        _recentSamples.value = (_recentSamples.value + sampleObj).takeLast(MAX_UI_SAMPLES)
    }

    fun stopAndFinalizeSession(context: Context, finalLevel: Int) {
        val rec = activeSessionRecord
        _isRecording.value = false
        activeCsvFile = null
        activeSessionRecord = null

        if (rec == null || rec.samplesCount == 0) return

        val sdfHuman = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
        rec.endTime = sdfHuman.format(Date())
        rec.endLevel = finalLevel
        rec.referenceChargerName = "${_referenceCharger.value.brand} ${_referenceCharger.value.modelCode}"

        saveSessionMetadataToPrefs(context, rec)
        _savedSessions.value = loadSessionsFromDisk(context)
        _statusBanner.value = "Đã lưu hoàn tất phiên sạc (${rec.samplesCount} mẫu) vào: ${File(rec.csvFilePath).name}"
    }

    private fun saveSessionMetadataToPrefs(context: Context, session: ChargingSessionRecord) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingJson = prefs.getString(KEY_SESSIONS, "[]") ?: "[]"
        val arr = runCatching { JSONArray(existingJson) }.getOrDefault(JSONArray())

        val obj = JSONObject().apply {
            put("sessionId", session.sessionId)
            put("startTime", session.startTime)
            put("endTime", session.endTime)
            put("startLevel", session.startLevel)
            put("endLevel", session.endLevel)
            put("peakBatPowerW", session.peakBatPowerW.toDouble())
            put("avgBatPowerW", session.avgBatPowerW.toDouble())
            put("peakVbusPowerW", session.peakVbusPowerW?.toDouble() ?: -1.0)
            put("maxTempC", session.maxTempC.toDouble())
            put("accumulatedMah", session.accumulatedMah.toDouble())
            put("detectedProtocol", session.detectedProtocol)
            put("referenceChargerName", session.referenceChargerName)
            put("samplesCount", session.samplesCount)
            put("csvFilePath", session.csvFilePath)
        }

        val newArr = JSONArray()
        newArr.put(obj)
        for (idx in 0 until minOf(arr.length(), 49)) {
            newArr.put(arr.getJSONObject(idx))
        }
        prefs.edit().putString(KEY_SESSIONS, newArr.toString()).apply()
    }

    private fun loadSessionsFromDisk(context: Context): List<ChargingSessionRecord> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SESSIONS, "[]") ?: "[]"
        val list = mutableListOf<ChargingSessionRecord>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pvbus = o.optDouble("peakVbusPowerW", -1.0)
                list.add(
                    ChargingSessionRecord(
                        sessionId = o.optString("sessionId"),
                        startTime = o.optString("startTime"),
                        endTime = o.optString("endTime"),
                        startLevel = o.optInt("startLevel"),
                        endLevel = o.optInt("endLevel"),
                        peakBatPowerW = o.optDouble("peakBatPowerW", 0.0).toFloat(),
                        avgBatPowerW = o.optDouble("avgBatPowerW", 0.0).toFloat(),
                        peakVbusPowerW = if (pvbus > 0) pvbus.toFloat() else null,
                        maxTempC = o.optDouble("maxTempC", 0.0).toFloat(),
                        accumulatedMah = o.optDouble("accumulatedMah", 0.0).toFloat(),
                        detectedProtocol = o.optString("detectedProtocol"),
                        referenceChargerName = o.optString("referenceChargerName"),
                        samplesCount = o.optInt("samplesCount"),
                        csvFilePath = o.optString("csvFilePath")
                    )
                )
            }
        }
        return list
    }

    /**
     * Xóa sạch cả JSON trong SharedPreferences VÀ toàn bộ các tệp .csv vật lý trên bộ nhớ
     */
    fun deleteAllSessionsAndCsvFiles(context: Context) {
        val currentList = loadSessionsFromDisk(context)
        var deletedFilesCount = 0
        currentList.forEach { ses ->
            runCatching {
                val f = File(ses.csvFilePath)
                if (f.exists() && f.delete()) deletedFilesCount++
            }
        }
        // Quét thêm thư mục externalFilesDir để xóa các file ChargingLog_*.csv mồ côi
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            dir.listFiles()?.forEach { file ->
                if (file.name.startsWith("ChargingLog_") && file.name.endsWith(".csv")) {
                    if (file.delete()) deletedFilesCount++
                }
            }
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_SESSIONS).apply()
        _savedSessions.value = emptyList()
        _statusBanner.value = "Đã xóa sạch lịch sử và $deletedFilesCount tệp CSV trong bộ nhớ máy."
    }
}

// ============================================================================
// 5. FOREGROUND SERVICE (GHI LOG BỀN BỈ KHI TẮT MÀN HÌNH / THOÁT RA NỀN)
// ============================================================================

class ChargingMonitorService : Service() {
    companion object {
        const val CHANNEL_ID = "battery_power_monitor_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_SERVICE = "com.example.batterypowermonitor.STOP_SERVICE"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null

    @Volatile private var latestBatVolt: Float = 0f
    @Volatile private var latestTempC: Float = 0f
    @Volatile private var latestLevelPct: Int = 0
    @Volatile private var latestIsCharging: Boolean = false
    @Volatile private var latestPlugType: String = "Chưa cắm sạc"
    @Volatile private var latestHealth: String = "Tốt"

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            latestBatVolt = if (voltageMv > 100) voltageMv / 1000f else voltageMv.toFloat()

            val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            latestTempC = tempTenths / 10f

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            latestLevelPct = if (scale > 0) (level * 100) / scale else 0

            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            latestIsCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            latestPlugType = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "Bộ sạc AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "Cổng USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Sạc không dây"
                BatteryManager.BATTERY_PLUGGED_DOCK -> "Đế sạc (Dock)"
                else -> "Đang xả pin"
            }

            val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
            latestHealth = when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Tốt"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Quá nhiệt"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Hỏng"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Quá áp"
                else -> "Bình thường"
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val initialNotification = buildNotification("Đang khởi tạo bộ đo công suất...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startTelemetryLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            TelemetryRepository.stopAndFinalizeSession(applicationContext, latestLevelPct)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startTelemetryLoop() {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            var tickCount = 0
            while (isActive) {
                val rawCurrent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                val currentMa = if (abs(rawCurrent) > 50_000) rawCurrent / 1000f else rawCurrent.toFloat()
                val currentAmp = abs(currentMa) / 1000f

                val cells = TelemetryRepository.cellSeriesCount.value
                val batPowerW = latestBatVolt * currentAmp * cells

                val sysReport = KernelSysfsScanner.probe()
                val vbusPowerW = if (sysReport.vbusVoltageV != null && sysReport.ibusCurrentA != null) {
                    sysReport.vbusVoltageV * sysReport.ibusCurrentA
                } else null

                val snapshot = BatteryTelemetry(
                    batCurrentMa = currentMa,
                    batCurrentAmp = currentAmp,
                    batVoltageVolt = latestBatVolt,
                    cellSeriesCount = cells,
                    effectiveBatPowerW = batPowerW,
                    sysfsReport = sysReport,
                    vbusInputPowerW = vbusPowerW,
                    temperatureC = latestTempC,
                    levelPercent = latestLevelPct,
                    isCharging = latestIsCharging,
                    plugType = latestPlugType,
                    healthStatus = latestHealth
                )

                TelemetryRepository.updateTelemetrySnapshot(applicationContext, snapshot)

                // Cập nhật thanh thông báo mỗi ~2 giây để tiết kiệm pin
                tickCount++
                if (tickCount % 2 == 0) {
                    val statusPrefix = if (latestIsCharging) "⚡ Sạc" else "🔋 Xả"
                    val powerLabel = if (vbusPowerW != null) {
                        "VBUS: ${String.format("%.1fW", vbusPowerW)} | Pin: ${String.format("%.1fW", batPowerW)}"
                    } else {
                        "P_pin (ước tính): ${String.format("%.1fW", batPowerW)} (${String.format("%.2fV", latestBatVolt * cells)} × ${String.format("%.2fA", currentAmp)})"
                    }
                    val notifText = "$statusPrefix • $powerLabel • ${latestLevelPct}% (${String.format("%.1f°C", latestTempC)})"
                    runCatching {
                        notificationManager.notify(NOTIFICATION_ID, buildNotification(notifText))
                    }
                }

                delay(TelemetryRepository.refreshIntervalMs.value)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Giám sát & Ghi nhật ký công suất sạc",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Giữ tiến trình đo và ghi CSV hoạt động khi tắt màn hình"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ChargingMonitorService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("Battery Power Monitor v3.0 (Đang chạy nền)")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dừng chạy nền", pendingStop)
            .build()
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        runCatching { unregisterReceiver(batteryReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// ============================================================================
// 6. MAIN ACTIVITY & TIỆN ÍCH CHIA SẺ FILE CSV / MỞ LINK NGUỒN
// ============================================================================

fun shareOrOpenCsvFile(context: Context, csvPath: String) {
    try {
        val file = File(csvPath)
        if (!file.exists()) return
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Nhật ký phiên sạc ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Chia sẻ hoặc mở file CSV"))
    } catch (_: Exception) {}
}

fun openExternalUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TelemetryRepository.initHistory(applicationContext)

        // Khởi chạy Foreground Service để không mất dữ liệu khi xoay màn hình hoặc tắt màn hình
        startMonitoringForegroundService()

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

    private fun startMonitoringForegroundService() {
        val serviceIntent = Intent(this, ChargingMonitorService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }
}

@Composable
fun BatteryPowerMonitorApp() {
    val context = LocalContext.current

    // Xin quyền POST_NOTIFICATIONS trên Android 13+ để hiển thị Notification của Foreground Service
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {}
    )
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Lắng nghe toàn bộ StateFlow từ Singleton TelemetryRepository (Sống qua xoay màn hình 100%)
    val telemetry by TelemetryRepository.telemetry.collectAsState()
    val powerHistory by TelemetryRepository.powerHistory.collectAsState()
    val recentSamples by TelemetryRepository.recentSamples.collectAsState()
    val isRecording by TelemetryRepository.isRecording.collectAsState()
    val autoRecordEnabled by TelemetryRepository.autoRecordEnabled.collectAsState()
    val cellSeriesCount by TelemetryRepository.cellSeriesCount.collectAsState()
    val refreshIntervalMs by TelemetryRepository.refreshIntervalMs.collectAsState()
    val referenceCharger by TelemetryRepository.referenceCharger.collectAsState()
    val savedSessions by TelemetryRepository.savedSessions.collectAsState()
    val statusBanner by TelemetryRepository.statusBanner.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedBrandFilter by remember { mutableStateOf("Tất cả") }

    val protocolResult = remember(telemetry) {
        ChargerCatalogRepository.analyzeChargingProtocol(telemetry)
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Battery Power Monitor v3.0",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Đo tách bạch P_pin & VBUS • Chạy nền Foreground Service",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isRecording) Color(0xFF00E676).copy(alpha = 0.2f) else Color(0xFF334155)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isRecording) Color(0xFF00E676) else Color(0xFF94A3B8))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isRecording) "GHI CSV (${recentSamples.size})" else "CHẠY NỀN",
                                color = if (isRecording) Color(0xFF00E676) else Color(0xFFCBD5E1),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF1E293B),
                    contentColor = Color(0xFF00E676),
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("⚡ Đo Thực Tế", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("🔌 Tra Cứu Củ Sạc", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("📈 File CSV (${savedSessions.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
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
                    referenceCharger = referenceCharger,
                    cellSeriesCount = cellSeriesCount,
                    onCellSeriesChange = { TelemetryRepository.setCellSeriesCount(it) },
                    powerHistory = powerHistory,
                    refreshIntervalMs = refreshIntervalMs,
                    onIntervalChange = { TelemetryRepository.setRefreshInterval(it) },
                    onOpenChargerCatalog = { selectedTab = 1 }
                )
                1 -> ChargerDatabaseTab(
                    selectedBrand = selectedBrandFilter,
                    onBrandChange = { selectedBrandFilter = it },
                    referenceCharger = referenceCharger,
                    onSelectCharger = { TelemetryRepository.setReferenceCharger(it) }
                )
                2 -> ChargingLogHistoryTab(
                    autoRecordCharging = autoRecordEnabled,
                    onAutoRecordChange = { TelemetryRepository.setAutoRecord(it) },
                    isRecordingSession = isRecording,
                    recentSamples = recentSamples,
                    statusBanner = statusBanner,
                    savedSessions = savedSessions,
                    onManualStartOrStop = {
                        if (isRecording) {
                            TelemetryRepository.stopAndFinalizeSession(context, telemetry.levelPercent)
                        } else {
                            TelemetryRepository.startStreamingSession(
                                context,
                                telemetry,
                                protocolResult.primaryStandard
                            )
                        }
                    },
                    onShareCsv = { path -> shareOrOpenCsvFile(context, path) },
                    onClearHistory = { TelemetryRepository.deleteAllSessionsAndCsvFiles(context) }
                )
            }
        }
    }
}

// ============================================================================
// TAB 1: ĐO CÔNG SUẤT MINH BẠCH (PHÍA CELL PIN vs PHÍA CỔNG VBUS)
// ============================================================================

@Composable
fun RealtimeDashboardTab(
    telemetry: BatteryTelemetry,
    protocolResult: ProtocolDetectionResult,
    referenceCharger: ChargerSpecProfile,
    cellSeriesCount: Int,
    onCellSeriesChange: (Int) -> Unit,
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
        // 1. Thẻ Công suất chính — Ghi nhãn rõ bản chất vật lý (Battery-side vs VBUS-side)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
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

                    val animatedBatPower by animateFloatAsState(targetValue = telemetry.effectiveBatPowerW, label = "bat_power")
                    Text(
                        text = String.format("%.2f W", animatedBatPower),
                        color = Color.White,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Surface(
                        color = Color(0xFF0F172A),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "CÔNG SUẤT ƯỚC TÍNH TỪ PHÍA CELL PIN (P_bat = V_bat × I_bat × ${cellSeriesCount}S)",
                            color = Color(0xFFFBBF24),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Hiển thị trạng thái đọc VBUS đầu vào thật từ sysfs
                    Surface(
                        color = Color(0xFF0F172A),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            if (telemetry.vbusInputPowerW != null) {
                                Text(
                                    text = "✓ Công suất cổng sạc VBUS đo được: ${String.format("%.2f W", telemetry.vbusInputPowerW)} " +
                                            "(${String.format("%.2fV", telemetry.sysfsReport.vbusVoltageV)} × ${String.format("%.2fA", telemetry.sysfsReport.ibusCurrentA)})",
                                    color = Color(0xFF00E676),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Text(
                                    text = "ℹ️ Không đọc được điện áp/dòng cổng sạc VBUS (5V/9V/15V/20V) do SELinux trên máy không Root chặn đọc sysfs.",
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "• Số W ở trên là công suất đi vào cell pin sau mạch hạ áp/Charge Pump (luôn thấp hơn công suất đầu vào củ sạc khoảng 10–20% do hao phí nhiệt, hoặc thấp hơn 50% nếu máy dùng pin kép 2S mà bạn đang để cấu hình 1S). Muốn đo chuẩn công suất đầu ra củ sạc cần dùng USB Tester phần cứng.",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Công tắc chọn cấu hình Pin Đơn (1S ~4.4V) hoặc Pin Kép nối tiếp (2S ~8.8V)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Cấu trúc cell pin của máy:",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = cellSeriesCount == 1,
                                onClick = { onCellSeriesChange(1) },
                                label = { Text("1S (Pin đơn ~4.4V)", fontSize = 11.sp) }
                            )
                            FilterChip(
                                selected = cellSeriesCount == 2,
                                onClick = { onCellSeriesChange(2) },
                                label = { Text("2S (Pin kép ×2)", fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        }

        // 2. Thẻ Nhận diện Chuẩn Sạc (Phân biệt rõ: Xác thực Kernel vs Suy luận từ P_bat)
        item {
            val cardBg = if (protocolResult.isVerifiedFromKernel) Color(0xFF064E3B) else Color(0xFF1E293B)
            val badgeBg = if (protocolResult.isVerifiedFromKernel) Color(0xFF059669) else Color(0xFFD97706)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CHẨN ĐOÁN CƠ CHẾ SẠC",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Surface(
                            color = badgeBg,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = protocolResult.verificationBadge,
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
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = protocolResult.powerBasisNote,
                        color = Color(0xFF7DD3FC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = protocolResult.technicalExplanation,
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // 3. Lưới thông số Điện áp Cell Pin (EXTRA_VOLTAGE) & Dòng điện nạp Pin (CURRENT_NOW)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Dòng nạp tại Pin (I_bat)",
                    mainValue = String.format("%.3f A", telemetry.batCurrentAmp),
                    subValue = String.format("%.0f mA (Sau mạch sạc)", telemetry.batCurrentMa),
                    accentColor = Color(0xFF29B6F6)
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Điện áp Cell Pin (V_bat)",
                    mainValue = String.format("%.3f V", telemetry.batVoltageVolt * cellSeriesCount),
                    subValue = "1 Cell: ${String.format("%.0f mV", telemetry.batVoltageVolt * 1000)} (${cellSeriesCount}S)",
                    accentColor = Color(0xFFAB47BC)
                )
            }
        }

        // 4. Bảng kiểm tra trực tiếp trạng thái đọc Kernel Sysfs (Minh bạch 100%)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "TRẠNG THÁI ĐỌC NODE PHẦN CỨNG KERNEL (/sys/class/power_supply)",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SysfsStatusRow(
                        label = "Điện áp cổng VBUS:",
                        value = telemetry.sysfsReport.vbusVoltageV?.let { String.format("%.2f V", it) } ?: "Bị chặn (SELinux)",
                        path = telemetry.sysfsReport.vbusSourcePath,
                        isOk = telemetry.sysfsReport.vbusVoltageV != null
                    )
                    SysfsStatusRow(
                        label = "Dòng đầu vào IBUS:",
                        value = telemetry.sysfsReport.ibusCurrentA?.let { String.format("%.2f A", it) } ?: "Bị chặn (SELinux)",
                        path = telemetry.sysfsReport.ibusSourcePath,
                        isOk = telemetry.sysfsReport.ibusCurrentA != null
                    )
                    SysfsStatusRow(
                        label = "Cờ giao thức PD/QC:",
                        value = telemetry.sysfsReport.kernelProtocolRaw.ifEmpty { "Bị chặn (SELinux)" },
                        path = telemetry.sysfsReport.kernelProtocolPath,
                        isOk = telemetry.sysfsReport.isKernelProtocolVerified
                    )
                }
            }
        }

        // 5. Thẻ Củ Sạc Đối Chiếu Thủ Công (Không tự gán vòng tròn)
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
                    CommercialChargerImage(
                        charger = referenceCharger,
                        modifier = Modifier.size(80.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "CỦ SẠC ĐỐI CHIẾU THAM KHẢO (CHỌN TAY)",
                                color = Color(0xFFFBBF24),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "Đổi mẫu ➔",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = referenceCharger.productName,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Model: ${referenceCharger.modelCode} • Công bố: ${referenceCharger.maxPowerW}W Max",
                            color = Color(0xFFE2E8F0),
                            fontSize = 11.sp
                        )
                        Text(
                            text = "PDO/PPS của củ: ${referenceCharger.pdoProfiles}",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 6. Lưới thông số phụ: Mức pin, Nhiệt độ, Sức khỏe
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
                    label = "Nhiệt độ Pin",
                    value = String.format("%.1f °C", telemetry.temperatureC)
                )
                SmallMetricCard(
                    modifier = Modifier.weight(1f),
                    label = "Trạng thái Pin",
                    value = telemetry.healthStatus
                )
            }
        }

        // 7. Biểu đồ công suất thời gian thực
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(185.dp),
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
                            text = "Đồ thị biến thiên Công suất (60 mẫu gần nhất)",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Chu kỳ: ${refreshIntervalMs}ms",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
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

        // 8. Tùy chỉnh tốc độ lấy mẫu
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Chu kỳ lấy mẫu:", color = Color(0xFF94A3B8), fontSize = 12.sp)
                listOf(500L to "0.5s", 1000L to "1.0s", 2000L to "2.0s").forEach { (ms, label) ->
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

@Composable
fun SysfsStatusRow(label: String, value: String, path: String, isOk: Boolean) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, color = Color(0xFFCBD5E1), fontSize = 11.sp)
            Text(
                text = value,
                color = if (isOk) Color(0xFF00E676) else Color(0xFFF87171),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = path,
            color = Color(0xFF64748B),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ============================================================================
// TAB 2: THƯ VIỆN TRA CỨU CỦ SẠC & NGUỒN TEARDOWN KIỂM CHỨNG
// ============================================================================

@Composable
fun ChargerDatabaseTab(
    selectedBrand: String,
    onBrandChange: (String) -> Unit,
    referenceCharger: ChargerSpecProfile,
    onSelectCharger: (ChargerSpecProfile) -> Unit
) {
    val context = LocalContext.current
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
        // Ghi chú minh bạch về giới hạn phần cứng USB-C
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF172554))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "ℹ️ Lưu ý kỹ thuật về Thư viện Củ sạc & Mã IC",
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Giao thức USB-C PD không truyền tên thương mại hay mã IC bên trong củ sạc sang điện thoại Android không Root. Danh mục dưới đây là thư viện tra cứu kỹ thuật từ tài liệu hãng & báo cáo mổ xẻ (Teardown) của ChargerLAB để bạn chọn thủ công mẫu củ sạc đang test và đối chiếu thông số PDO/PPS.",
                    color = Color(0xFFCBD5E1),
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(filteredChargers) { item ->
                val isSelected = item.modelCode == referenceCharger.modelCode
                ChargerDetailSpecCard(
                    charger = item,
                    isSelected = isSelected,
                    onClick = { onSelectCharger(item) },
                    onOpenSource = { openExternalUrl(context, item.sourceUrl) }
                )
            }
        }
    }
}

@Composable
fun ChargerDetailSpecCard(
    charger: ChargerSpecProfile,
    isSelected: Boolean,
    onClick: () -> Unit,
    onOpenSource: () -> Unit
) {
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
                CommercialChargerImage(
                    charger = charger,
                    modifier = Modifier.size(82.dp)
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
                                text = "✓ ĐANG ĐỐI CHIẾU",
                                color = Color(0xFF00E676),
                                fontSize = 10.sp,
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
                        text = "Mã Model: ${charger.modelCode}",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Mức độ kiểm chứng: ${charger.verificationStatus}",
                        color = Color(0xFFFBBF24),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = Color.White.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(8.dp))

            SpecRowItem("Cấu hình cổng & Kích thước:", "${charger.portsConfig} (${charger.dimensionsWeight})")
            SpecRowItem("IC Nguồn chính (PWM/PFC):", charger.primaryControllerIc)
            SpecRowItem("IC Giao thức (Protocol IC):", charger.protocolChipIc)
            SpecRowItem("Bán dẫn công suất (GaN/MOS):", charger.ganPowerFet)
            SpecRowItem("Các mức PDO công bố:", charger.pdoProfiles)
            SpecRowItem("Dải PPS / AVS:", charger.ppsOrAvsRange)

            Spacer(modifier = Modifier.height(8.dp))

            // Nút bấm mở trực tiếp bài Teardown / Tài liệu kỹ thuật gốc
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🔗 Nguồn: ${charger.sourceTitle}",
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenSource() }
                )
                TextButton(
                    onClick = onOpenSource,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Mở nguồn ↗", fontSize = 11.sp)
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

@Composable
fun CommercialChargerImage(
    charger: ChargerSpecProfile,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(charger.productImageUrl)
                .crossfade(true)
                .build(),
            contentDescription = charger.productName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            loading = {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = Color(charger.accentColorHex)
                    )
                }
            },
            error = {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFF8FAFC))
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = charger.brand.uppercase(),
                        color = Color(0xFF0F172A),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "${charger.maxPowerW}W",
                        color = Color(charger.accentColorHex),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = charger.modelCode.split(" ").first(),
                        color = Color(0xFF475569),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        )
    }
}

// ============================================================================
// TAB 3: NHẬT KÝ SẠC STREAMING CSV + CHIA SẺ FILE + XÓA SẠCH FILE VẬT LÝ
// ============================================================================

@Composable
fun ChargingLogHistoryTab(
    autoRecordCharging: Boolean,
    onAutoRecordChange: (Boolean) -> Unit,
    isRecordingSession: Boolean,
    recentSamples: List<LogSample>,
    statusBanner: String,
    savedSessions: List<ChargingSessionRecord>,
    onManualStartOrStop: () -> Unit,
    onShareCsv: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
                            text = "Ghi Streaming trực tiếp ra đĩa (Foreground Service)",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Mỗi mẫu đo được ghi nối tiếp ngay lập tức vào file .csv trên bộ nhớ, không lo mất dữ liệu khi tắt màn hình hoặc tràn RAM",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = autoRecordCharging,
                        onCheckedChange = onAutoRecordChange
                    )
                }

                if (statusBanner.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "📁 $statusBanner",
                        color = Color(0xFF00E676),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isRecordingSession) "Trạng thái: Đang ghi trực tiếp vào CSV..." else "Trạng thái: Đang chờ cắm sạc",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Button(
                        onClick = onManualStartOrStop,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = if (isRecordingSession) "Đóng & Lưu phiên ngay" else "Bắt đầu ghi thủ công",
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // Bảng 120 mẫu gần nhất hiển thị trên UI
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.95f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Bộ đệm hiển thị (Tối đa 120 mẫu gần nhất — Toàn bộ mẫu đã nằm trong CSV)",
                    color = Color.White,
                    fontSize = 12.sp,
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
                    Text("V_bat", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("I_bat", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("P_bat", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("Nhiệt/Pin", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1.2f))
                }

                if (recentSamples.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Cắm sạc hoặc bấm 'Bắt đầu ghi thủ công' để ghi mẫu...",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(recentSamples.asReversed()) { s ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp, horizontal = 8.dp)
                            ) {
                                Text(s.timeFormatted, color = Color(0xFFCBD5E1), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1.2f))
                                Text(String.format("%.2fV", s.batVoltageV), color = Color(0xFFAB47BC), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                Text(String.format("%.2fA", s.batCurrentA), color = Color(0xFF29B6F6), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                Text(String.format("%.1fW", s.batPowerW), color = Color(0xFF00E676), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                Text("${String.format("%.1f°", s.tempC)}/${s.levelPct}%", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1.2f))
                            }
                            Divider(color = Color.White.copy(alpha = 0.04f))
                        }
                    }
                }
            }
        }

        // Danh sách các phiên sạc đã lưu kèm nút Chia sẻ / Mở file CSV & Xóa sạch file vật lý
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.05f),
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
                        text = "Các phiên sạc đã lưu (${savedSessions.size})",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (savedSessions.isNotEmpty()) {
                        Text(
                            text = "🗑️ Xóa sạch lịch sử & File CSV",
                            color = Color(0xFFEF4444),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
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
                                        text = "P_bat đỉnh: ${String.format("%.1fW", ses.peakBatPowerW)} • P_bat TB: ${String.format("%.1fW", ses.avgBatPowerW)} • Nhiệt max: ${String.format("%.1f°C", ses.maxTempC)} • ${ses.samplesCount} mẫu",
                                        color = Color(0xFF38BDF8),
                                        fontSize = 11.sp
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "File: ${File(ses.csvFilePath).name}",
                                            color = Color(0xFF94A3B8),
                                            fontSize = 10.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = { onShareCsv(ses.csvFilePath) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("📤 Chia sẻ / Mở CSV", fontSize = 11.sp, color = Color(0xFF00E676))
                                        }
                                    }
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
            Text(text = subValue, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
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
        val stepX = size.width / (60 - 1).coerceAtLeast(1)

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