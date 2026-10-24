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
import android.content.res.Configuration
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ============================================================================
// 1. BA CHẾ ĐỘ VẬN HÀNH NGƯỜI DÙNG (NORMAL / ADB SHIZUKU / ROOT)
// ============================================================================

enum class AppPrivilegeMode(
    val id: Int,
    val shortTitle: String,
    val fullTitle: String,
    val description: String,
    val badgeColorHex: Long
) {
    NORMAL_USER(
        id = 0,
        shortTitle = "1. Phổ thông",
        fullTitle = "Chế độ 1: Người dùng phổ thông (Standard API)",
        description = "Không truy cập Node trong Kernel (do SELinux chặn). Chỉ dùng Android BatteryManager API cơ bản: tính công suất ước tính phía cell pin (P_bat) và suy luận chuẩn sạc theo ngưỡng công suất.",
        badgeColorHex = 0xFF64748B
    ),
    ADB_SHIZUKU(
        id = 1,
        shortTitle = "2. ADB (Shizuku)",
        fullTitle = "Chế độ 2: ADB qua Shizuku (Quyền Shell UID 2000)",
        description = "Thông qua Shizuku API để chạy lệnh 'dumpsys battery' và đọc các node sysfs cho phép quyền ADB Shell. Lấy được Max Charging Voltage/Current (VBUS/IBUS đàm phán), Charge Counter và trạng thái cổng USB-C chính xác hơn.",
        badgeColorHex = 0xFF0284C7
    ),
    ROOT_FULL(
        id = 2,
        shortTitle = "3. Root (Full)",
        fullTitle = "Chế độ 3: Root Toàn Quyền (Superuser UID 0)",
        description = "Thực thi qua 'su -c' vượt hoàn toàn rào cản SELinux: Đọc trực tiếp mọi node '/sys/class/power_supply/*' (VBUS/IBUS thực, cờ pd_active, usb_type, real_type, charge_pump, cycle_count) để xác thực 100% chuẩn sạc nhanh và công suất đầu vào thực tế.",
        badgeColorHex = 0xFF059669
    );

    companion object {
        fun fromId(id: Int): AppPrivilegeMode = entries.firstOrNull { it.id == id } ?: NORMAL_USER
    }
}

// ============================================================================
// 2. MÔ HÌNH DỮ LIỆU ĐO LƯỜNG (TÁCH BẠCH PHÍA PIN & PHÍA CỔNG VBUS)
// ============================================================================

data class SysfsProbeReport(
    val activeMode: AppPrivilegeMode = AppPrivilegeMode.NORMAL_USER,
    val privilegeStatusText: String = "Chế độ Phổ thông (Không đọc Kernel Node)",
    val isPrivilegeGranted: Boolean = true,
    val vbusVoltageV: Float? = null,
    val ibusCurrentA: Float? = null,
    val vbusSourcePath: String = "Đã tắt ở chế độ Phổ thông",
    val ibusSourcePath: String = "Đã tắt ở chế độ Phổ thông",
    val kernelProtocolRaw: String = "",
    val kernelProtocolPath: String = "Đã tắt ở chế độ Phổ thông",
    val extraDeveloperMetrics: String = "",
    val isVbusMeasured: Boolean = false,
    val isKernelProtocolVerified: Boolean = false
)

data class BatteryTelemetry(
    val batCurrentMa: Float = 0f,
    val batCurrentAmp: Float = 0f,
    val batVoltageVolt: Float = 0f,
    val cellSeriesCount: Int = 1,
    val effectiveBatPowerW: Float = 0f,
    val sysfsReport: SysfsProbeReport = SysfsProbeReport(),
    val vbusInputPowerW: Float? = null,
    val temperatureC: Float = 0f,
    val levelPercent: Int = 0,
    val isCharging: Boolean = false,
    val plugType: String = "Chưa cắm sạc",
    val healthStatus: String = "Tốt"
)

data class LogSample(
    val timestampMs: Long,
    val timeFormatted: String,
    val modeName: String,
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
    var privilegeModeUsed: String,
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
    val verificationStatus: String,
    val sourceTitle: String,
    val sourceUrl: String
)

data class ProtocolDetectionResult(
    val isVerifiedFromKernel: Boolean,
    val primaryStandard: String,
    val technicalExplanation: String,
    val powerBasisNote: String,
    val verificationBadge: String
)

// ============================================================================
// 3. BỘ THỰC THI ĐA CHẾ ĐỘ: NORMAL API vs ADB (SHIZUKU) vs ROOT (SU)
// ============================================================================

object PrivilegedTelemetryEngine {

    fun checkShizukuStatus(): Pair<Boolean, String> {
        return try {
            if (!Shizuku.pingBinder()) {
                false to "Shizuku chưa chạy (Hãy mở app Shizuku và bật Wireless Debugging / ADB)"
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                false to "Chưa cấp quyền Shizuku (Hãy bấm nút 'Cấp quyền Shizuku' bên dưới)"
            } else {
                val uid = Shizuku.getUid()
                val role = if (uid == 0) "Root-backed Shizuku (UID 0)" else "ADB Shell (UID $uid)"
                true to "Đã kết nối Shizuku: $role"
            }
        } catch (e: Throwable) {
            false to "Shizuku chưa sẵn sàng: ${e.javaClass.simpleName}"
        }
    }

    fun requestShizukuPermission(requestCode: Int = 2026) {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (_: Throwable) {}
    }

    private fun execShizukuCommand(cmd: String): String {
        return try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return ""
            }
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(1500, TimeUnit.MILLISECONDS)
            output.trim()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun execRootCommand(cmd: String): Pair<Boolean, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(1800, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                false to ""
            } else if (process.exitValue() == 0 && output.isNotBlank()) {
                true to output.trim()
            } else {
                false to ""
            }
        } catch (_: Throwable) {
            false to ""
        }
    }

    private fun normalizeUnitToVolt(raw: Float?): Float? {
        if (raw == null) return null
        val v = abs(raw)
        return when {
            v <= 0.1f -> null
            v > 100_000f -> v / 1_000_000f
            v > 100f -> v / 1_000f
            else -> v
        }
    }

    private fun normalizeUnitToAmp(raw: Float?): Float? {
        if (raw == null) return null
        val i = abs(raw)
        return when {
            i <= 0.001f -> null
            i > 50_000f -> i / 1_000_000f
            i > 50f -> i / 1_000f
            else -> i
        }
    }

    fun probeByMode(mode: AppPrivilegeMode): SysfsProbeReport {
        return when (mode) {
            AppPrivilegeMode.NORMAL_USER -> probeNormalMode()
            AppPrivilegeMode.ADB_SHIZUKU -> probeShizukuAdbMode()
            AppPrivilegeMode.ROOT_FULL -> probeRootMode()
        }
    }

    private fun probeNormalMode(): SysfsProbeReport {
        return SysfsProbeReport(
            activeMode = AppPrivilegeMode.NORMAL_USER,
            privilegeStatusText = "Đang chạy Chế độ Phổ thông: Chỉ dùng Android BatteryManager API",
            isPrivilegeGranted = true,
            vbusVoltageV = null,
            ibusCurrentA = null,
            vbusSourcePath = "Bị vô hiệu ở Chế độ Phổ thông (Chuyển sang ADB/Root để mở khóa)",
            ibusSourcePath = "Bị vô hiệu ở Chế độ Phổ thông (Chuyển sang ADB/Root để mở khóa)",
            kernelProtocolRaw = "",
            kernelProtocolPath = "Bị vô hiệu ở Chế độ Phổ thông",
            extraDeveloperMetrics = "Chế độ cơ bản: Không đọc sysfs Kernel. Công suất hiển thị là ước tính phía cell pin (P_bat).",
            isVbusMeasured = false,
            isKernelProtocolVerified = false
        )
    }

    private fun probeShizukuAdbMode(): SysfsProbeReport {
        val (ok, statusMsg) = checkShizukuStatus()
        if (!ok) {
            return SysfsProbeReport(
                activeMode = AppPrivilegeMode.ADB_SHIZUKU,
                privilegeStatusText = statusMsg,
                isPrivilegeGranted = false,
                vbusSourcePath = "Chờ kết nối và cấp quyền Shizuku...",
                ibusSourcePath = "Chờ kết nối và cấp quyền Shizuku...",
                kernelProtocolPath = "Chờ kết nối và cấp quyền Shizuku...",
                extraDeveloperMetrics = "Hướng dẫn: Cài đặt ứng dụng Shizuku, kích hoạt qua Wireless Debugging và bấm nút 'Cấp quyền Shizuku'."
            )
        }

        val script = """
            dumpsys battery
            echo "===SYSFS==="
            for p in /sys/class/power_supply/usb/voltage_now /sys/class/power_supply/main-charger/voltage_now /sys/class/power_supply/ac/voltage_now; do
              if [ -r "${'$'}p" ]; then echo "VBUS:${'$'}p:${'$'}(cat ${'$'}p 2>/dev/null)"; break; fi
            done
            for p in /sys/class/power_supply/usb/current_now /sys/class/power_supply/usb/input_current_now /sys/class/power_supply/main-charger/current_now; do
              if [ -r "${'$'}p" ]; then echo "IBUS:${'$'}p:${'$'}(cat ${'$'}p 2>/dev/null)"; break; fi
            done
            for p in /sys/class/power_supply/usb/usb_type /sys/class/power_supply/usb/real_type /sys/class/power_supply/usb/pd_active /sys/class/power_supply/bms/charge_type; do
              if [ -r "${'$'}p" ]; then echo "PROTO:${'$'}p:${'$'}(cat ${'$'}p 2>/dev/null)"; fi
            done
        """.trimIndent()

        val rawOut = execShizukuCommand(script)
        if (rawOut.isEmpty()) {
            return SysfsProbeReport(
                activeMode = AppPrivilegeMode.ADB_SHIZUKU,
                privilegeStatusText = "$statusMsg (Không nhận được phản hồi từ tiến trình shell)",
                isPrivilegeGranted = true
            )
        }

        var maxChargingMicroVolt: Float? = null
        var maxChargingMicroAmp: Float? = null
        var chargeCounterUah: Long? = null
        var sysVbus: Float? = null
        var sysVbusPath = ""
        var sysIbus: Float? = null
        var sysIbusPath = ""
        val protoList = mutableListOf<String>()
        var protoPath = ""

        rawOut.lineSequence().forEach { line ->
            val t = line.trim()
            when {
                t.startsWith("Max charging voltage:", ignoreCase = true) -> {
                    maxChargingMicroVolt = t.substringAfter(":").trim().toFloatOrNull()
                }
                t.startsWith("Max charging current:", ignoreCase = true) -> {
                    maxChargingMicroAmp = t.substringAfter(":").trim().toFloatOrNull()
                }
                t.startsWith("Charge counter:", ignoreCase = true) -> {
                    chargeCounterUah = t.substringAfter(":").trim().toLongOrNull()
                }
                t.startsWith("VBUS:") -> {
                    val parts = t.split(":")
                    if (parts.size >= 3) {
                        sysVbusPath = parts[1]
                        sysVbus = normalizeUnitToVolt(parts[2].toFloatOrNull())
                    }
                }
                t.startsWith("IBUS:") -> {
                    val parts = t.split(":")
                    if (parts.size >= 3) {
                        sysIbusPath = parts[1]
                        sysIbus = normalizeUnitToAmp(parts[2].toFloatOrNull())
                    }
                }
                t.startsWith("PROTO:") -> {
                    val parts = t.split(":")
                    if (parts.size >= 3) {
                        val v = parts.subList(2, parts.size).joinToString(":").trim()
                        if (v.isNotEmpty() && v != "0" && !v.equals("Unknown", true)) {
                            protoList.add("${File(parts[1]).name}=$v")
                            if (protoPath.isEmpty()) protoPath = parts[1]
                        }
                    }
                }
            }
        }

        val negotiatedVbus = sysVbus ?: normalizeUnitToVolt(maxChargingMicroVolt)
        val negotiatedIbus = sysIbus ?: normalizeUnitToAmp(maxChargingMicroAmp)

        val vbusLabel = when {
            sysVbus != null -> "$sysVbusPath (Đọc trực tiếp qua Shizuku Shell)"
            negotiatedVbus != null -> "dumpsys battery -> Max charging voltage (Điện áp thương lượng)"
            else -> "ROM ẩn thông số VBUS với quyền ADB Shell (Cần chế độ Root)"
        }

        val ibusLabel = when {
            sysIbus != null -> "$sysIbusPath (Đọc trực tiếp qua Shizuku Shell)"
            negotiatedIbus != null -> "dumpsys battery -> Max charging current (Dòng thương lượng tối đa)"
            else -> "ROM ẩn thông số IBUS với quyền ADB Shell (Cần chế độ Root)"
        }

        if (protoList.isEmpty() && negotiatedVbus != null && negotiatedVbus >= 4.5f) {
            val pdTag = when {
                negotiatedVbus >= 19.0f -> "dumpsys_negotiated=HV_20V (${String.format("%.1fV", negotiatedVbus)})"
                negotiatedVbus >= 14.0f -> "dumpsys_negotiated=HV_15V (${String.format("%.1fV", negotiatedVbus)})"
                negotiatedVbus >= 8.5f -> "dumpsys_negotiated=FastCharge_9V (${String.format("%.1fV", negotiatedVbus)})"
                else -> "dumpsys_negotiated=Standard_5V (${String.format("%.1fV", negotiatedVbus)})"
            }
            protoList.add(pdTag)
            protoPath = "dumpsys battery (Shizuku ADB)"
        }

        val counterMah = chargeCounterUah?.let { it / 1000 } ?: 0L
        val devExtra = buildString {
            append("Dữ liệu ADB (dumpsys): ")
            append("Dung lượng thực trong pin = ${if (counterMah > 0) "${counterMah} mAh" else "N/A"}")
            if (negotiatedVbus != null) append(" • Áp đàm phán = ${String.format("%.2fV", negotiatedVbus)}")
            if (negotiatedIbus != null) append(" • Dòng đàm phán = ${String.format("%.2fA", negotiatedIbus)}")
        }

        val protoJoined = protoList.joinToString(" | ")
        return SysfsProbeReport(
            activeMode = AppPrivilegeMode.ADB_SHIZUKU,
            privilegeStatusText = statusMsg,
            isPrivilegeGranted = true,
            vbusVoltageV = negotiatedVbus,
            ibusCurrentA = negotiatedIbus,
            vbusSourcePath = vbusLabel,
            ibusSourcePath = ibusLabel,
            kernelProtocolRaw = protoJoined,
            kernelProtocolPath = if (protoPath.isNotEmpty()) protoPath else "SELinux chặn usb_type đối với UID 2000",
            extraDeveloperMetrics = devExtra,
            isVbusMeasured = (negotiatedVbus != null && negotiatedVbus >= 3.5f),
            isKernelProtocolVerified = protoJoined.isNotEmpty()
        )
    }

    private fun probeRootMode(): SysfsProbeReport {
        val script = """
            for p in /sys/class/power_supply/usb/voltage_now /sys/class/power_supply/main-charger/voltage_now /sys/class/power_supply/ac/voltage_now /sys/class/power_supply/charge_pump_master/cp_vbus /sys/class/power_supply/wireless/voltage_now; do
              if [ -f "${'$'}p" ]; then echo "VBUS:${'$'}p:${'$'}(cat ${'$'}p 2>/dev/null)"; break; fi
            done
            for p in /sys/class/power_supply/usb/current_now /sys/class/power_supply/usb/input_current_now /sys/class/power_supply/main-charger/current_now /sys/class/power_supply/charge_pump_master/cp_ibus; do
              if [ -f "${'$'}p" ]; then echo "IBUS:${'$'}p:${'$'}(cat ${'$'}p 2>/dev/null)"; break; fi
            done
            for p in /sys/class/power_supply/usb/usb_type /sys/class/power_supply/usb/real_type /sys/class/power_supply/usb/pd_active /sys/class/power_supply/bms/charge_type /sys/class/power_supply/usb/typec_mode; do
              if [ -f "${'$'}p" ]; then echo "PROTO:${'$'}p:${'$'}(cat ${'$'}p 2>/dev/null)"; fi
            done
            for p in /sys/class/power_supply/battery/cycle_count /sys/class/power_supply/bms/cycle_count; do
              if [ -f "${'$'}p" ]; then echo "CYCLE:${'$'}(cat ${'$'}p 2>/dev/null)"; break; fi
            done
            for p in /sys/class/power_supply/battery/charge_full /sys/class/power_supply/bms/charge_full; do
              if [ -f "${'$'}p" ]; then echo "FULL:${'$'}(cat ${'$'}p 2>/dev/null)"; break; fi
            done
            for p in /sys/class/power_supply/battery/charge_full_design /sys/class/power_supply/bms/charge_full_design; do
              if [ -f "${'$'}p" ]; then echo "DESIGN:${'$'}(cat ${'$'}p 2>/dev/null)"; break; fi
            done
        """.trimIndent()

        val (rootOk, out) = execRootCommand(script)
        if (!rootOk) {
            return SysfsProbeReport(
                activeMode = AppPrivilegeMode.ROOT_FULL,
                privilegeStatusText = "Không lấy được quyền Root (Thiết bị chưa Root hoặc bạn đã từ chối quyền su)",
                isPrivilegeGranted = false,
                vbusSourcePath = "Cần cấp quyền Superuser (su) để đọc",
                ibusSourcePath = "Cần cấp quyền Superuser (su) để đọc",
                kernelProtocolPath = "Cần cấp quyền Superuser (su) để đọc",
                extraDeveloperMetrics = "Nếu máy chưa Root, hãy chuyển sang 'Chế độ 2: ADB (Shizuku)' hoặc 'Chế độ 1: Phổ thông'."
            )
        }

        var vbusV: Float? = null
        var vbusPath = "Không tìm thấy node VBUS trên phần cứng này"
        var ibusA: Float? = null
        var ibusPath = "Không tìm thấy node IBUS trên phần cứng này"
        val protoItems = mutableListOf<String>()
        var protoPath = ""
        var cycleCount = ""
        var chargeFull = 0f
        var chargeDesign = 0f

        out.lineSequence().forEach { line ->
            val t = line.trim()
            when {
                t.startsWith("VBUS:") -> {
                    val parts = t.split(":")
                    if (parts.size >= 3) {
                        val candidate = normalizeUnitToVolt(parts[2].toFloatOrNull())
                        if (candidate != null) {
                            vbusV = candidate
                            vbusPath = "${parts[1]} (Root UID 0)"
                        }
                    }
                }
                t.startsWith("IBUS:") -> {
                    val parts = t.split(":")
                    if (parts.size >= 3) {
                        val candidate = normalizeUnitToAmp(parts[2].toFloatOrNull())
                        if (candidate != null) {
                            ibusA = candidate
                            ibusPath = "${parts[1]} (Root UID 0)"
                        }
                    }
                }
                t.startsWith("PROTO:") -> {
                    val parts = t.split(":")
                    if (parts.size >= 3) {
                        val v = parts.subList(2, parts.size).joinToString(":").trim()
                        if (v.isNotEmpty() && v != "0" && !v.equals("Unknown", true)) {
                            protoItems.add("${File(parts[1]).name}=$v")
                            if (protoPath.isEmpty()) protoPath = "${parts[1]} (Root UID 0)"
                        }
                    }
                }
                t.startsWith("CYCLE:") -> cycleCount = t.substringAfter(":").trim()
                t.startsWith("FULL:") -> chargeFull = t.substringAfter(":").trim().toFloatOrNull() ?: 0f
                t.startsWith("DESIGN:") -> chargeDesign = t.substringAfter(":").trim().toFloatOrNull() ?: 0f
            }
        }

        val healthPctStr = if (chargeFull > 0f && chargeDesign > 0f) {
            val pct = (chargeFull / chargeDesign) * 100f
            val fullMah = if (chargeFull > 100_000f) chargeFull / 1000f else chargeFull
            val desMah = if (chargeDesign > 100_000f) chargeDesign / 1000f else chargeDesign
            "Độ chai pin phần cứng: Còn ${String.format("%.1f%%", pct)} (${String.format("%.0f", fullMah)} / ${String.format("%.0f mAh", desMah)})"
        } else "Độ chai pin: ROM không xuất node charge_full"

        val rootExtra = "Root BMS Telemetry: Chu kỳ sạc (Cycle Count) = ${cycleCount.ifEmpty { "N/A" }} • $healthPctStr"
        val protoJoined = protoItems.joinToString(" | ")

        return SysfsProbeReport(
            activeMode = AppPrivilegeMode.ROOT_FULL,
            privilegeStatusText = "✓ Đã kích hoạt Root Toàn Quyền (Superuser UID 0)",
            isPrivilegeGranted = true,
            vbusVoltageV = vbusV,
            ibusCurrentA = ibusA,
            vbusSourcePath = vbusPath,
            ibusSourcePath = ibusPath,
            kernelProtocolRaw = protoJoined,
            kernelProtocolPath = if (protoPath.isNotEmpty()) protoPath else "Đã đọc với quyền Root",
            extraDeveloperMetrics = rootExtra,
            // FIX: vbusV là var bị lambda forEach capture nên Kotlin không smart-cast được
            // sau null-check ("smart cast to 'Float' is impossible"). Dùng Elvis để giữ
            // đúng hành vi cũ: null -> 0f -> false, >= 3.5f -> true.
            isVbusMeasured = (vbusV ?: 0f) >= 3.5f,
            isKernelProtocolVerified = protoJoined.isNotEmpty()
        )
    }
}

// ============================================================================
// 4. DANH MỤC CỦ SẠC THAM KHẢO & ĐỐI CHIẾU THỦ CÔNG (CÓ LINK NGUỒN RÕ RÀNG)
// ============================================================================

object ChargerCatalogRepository {
    val chargers = listOf(
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
        ),
        // =====================================================================
        // v3.3 — BỔ SUNG 2023–2026: Anker / Samsung / Cuktech / Ugreen /
        // CMF by Nothing + ngoại lệ Baseus GaN2 Lite CGAN65US.
        // Quy tắc ghi chú (đừng sửa lén): IC nào chưa có teardown thì ghi
        // "Chưa công bố teardown", PPS nào chưa có dải APDO thì ghi
        // "Chưa xác minh", kích thước nào khác nhau theo vùng thì ghi rõ
        // biến thể đã chọn. Ảnh nào chưa có URL chính thức xác minh thì để
        // productImageUrl = "" để Coil rơi vào fallback hiển thị hãng + W.
        // =====================================================================
        ChargerSpecProfile(
            brand = "Anker",
            productName = "Anker Nano Charger (45W, Smart Display, 180° Foldable)",
            modelCode = "A121D",
            maxPowerW = 45,
            portsConfig = "1x USB-C",
            primaryControllerIc = "Chưa công bố teardown",
            protocolChipIc = "Chưa công bố teardown",
            ganPowerFet = "GaN (chi tiết IC chưa công bố)",
            supportedProtocols = listOf("USB PD 3.0", "PPS 3A (không hỗ trợ PPS 5A)"),
            pdoProfiles = "5V/3A, 9V/3A, 15V/3A, 20V/2,25A (45W)",
            ppsOrAvsRange = "PPS đã đo (LTT Labs): 5–16V/3A; tài liệu hãng nói tới 21V nhưng test 02/2026 chỉ đo tối đa 16V",
            dimensionsWeight = "34 × 35,5 × 40 mm • 75g (bản US chân gập; bản UK 3 chân khác kích thước, đừng trộn)",
            accentColorHex = 0xFF00A9E0,
            productImageUrl = "https://cdn.shopify.com/s/files/1/0493/7636/2660/files/2_1f6f102b-a779-46f8-a8b2-9c962ebf784c.png?v=1775547735",
            verificationStatus = "Thông số công bố của hãng (2025) + đo PPS của LTT Labs (chưa có teardown)",
            sourceTitle = "Anker A121D Official + LTT Labs Testing",
            sourceUrl = "https://www.anker.com/products/a121d-45w-usb-c-fast-charger-foldable-plug-compact"
        ),
        ChargerSpecProfile(
            brand = "Anker",
            productName = "Anker Prime Charger (100W, 3 Ports, GaN)",
            modelCode = "A2688",
            maxPowerW = 100,
            portsConfig = "2x USB-C + 1x USB-A (3 cổng dùng chung tối đa 89W theo hãng)",
            primaryControllerIc = "Chưa công bố teardown",
            protocolChipIc = "Chưa công bố teardown",
            ganPowerFet = "GaN (chi tiết IC chưa công bố)",
            supportedProtocols = listOf("USB PD 3.0 (suy ra từ PDO; QC/PPS chưa xác minh đầy đủ, đừng ghi thêm)"),
            pdoProfiles = "C1/C2 riêng: 5V/3A, 9V/3A, 15V/3A, 20V/5A (100W) • A: 5V/3A, 9V/2A, 10V/2,25A, 12V/1,5A (22,5W) • Chia: C1+C2 = 65W+35W; C1/C2+A = 65W+22,5W",
            ppsOrAvsRange = "Chưa xác minh (trang chính hãng không công bố dải PPS cho A2688)",
            dimensionsWeight = "43,5 × 29 × 67,8 mm • 170g (không tính chân cắm)",
            accentColorHex = 0xFF00A9E0,
            productImageUrl = "https://cdn.shopify.com/s/files/1/0493/7636/2660/files/A2688141_Rich_image_ND01_no_copy_5000x6258px_V1_1.png?v=1722951931",
            verificationStatus = "Thông số công bố của hãng (2024, dòng Prime 07/08/2024; chưa có teardown)",
            sourceTitle = "Anker A2688 Official + MacRumors Prime Launch 07/08/2024",
            sourceUrl = "https://www.anker.com/products/a2688-anker-prime-charger-100w-3-ports-gan"
        ),
        ChargerSpecProfile(
            brand = "Anker",
            productName = "Anker Charger (140W, 4-Port, PD 3.1)",
            modelCode = "A2697 (B2697 là mã bundle củ + cáp)",
            maxPowerW = 140,
            portsConfig = "3x USB-C + 1x USB-A, màn hình hiển thị công suất/nhiệt độ từng cổng (4 cổng cùng lúc 134W)",
            primaryControllerIc = "Chưa công bố teardown",
            protocolChipIc = "Chưa công bố teardown",
            ganPowerFet = "GaN (chi tiết IC chưa công bố)",
            supportedProtocols = listOf("USB PD 3.1", "PPS", "BC1.2, Apple, QC 3.0, FCP, AFC (nguồn thứ cấp Amchoi, không phải bảng chính hãng)"),
            pdoProfiles = "C1/C2 riêng: 9V/3A, 15V/3A, 20V/5A, 28V/5A (140W; chính hãng không liệt kê 5V cho C1/C2 một cổng) • C3: 5V/3A, 9V/3A, 15V/2,65A, 20V/2A (40W) • A1: 5V/3A, 9V/2A, 12V/1,5A, 10V/2,25A, 11V/3A (33W) • Chia: C1+C2 = 70W+70W; C1+C2+C3 = 65W+45W+30W",
            ppsOrAvsRange = "PPS: 4,5–20V/5A (100W) cho C1/C2 (trang chính hãng Anker CA)",
            dimensionsWeight = "69 × 69 × 36 mm • củ 275g, kèm cáp 395g (chính hãng; Amchoi ghi 295g — ưu tiên chính hãng)",
            accentColorHex = 0xFF00A9E0,
            productImageUrl = "https://us.anker.com/cdn/shop/files/A2697141_B2697141_Richimage_TD01_US_V1.png?v=1775123136&width=550",
            verificationStatus = "Thông số công bố của hãng (CES 2025; chưa có teardown)",
            sourceTitle = "Anker A2697 Official (CA) + Notebookcheck CES 2025",
            sourceUrl = "https://www.anker.com/ca/products/a2697-anker-charger-140w-4-port"
        ),
        ChargerSpecProfile(
            brand = "Samsung",
            productName = "Samsung 25W PD Power Adapter",
            modelCode = "EP-T2510 (kèm cáp US: EP-T2510XBEGUS; chỉ củ: EP-T2510NBEGUS)",
            maxPowerW = 25,
            portsConfig = "1x USB-C",
            primaryControllerIc = "Chưa công bố teardown",
            protocolChipIc = "Chưa công bố teardown",
            ganPowerFet = "GaN Tech (Samsung ZA ghi; có thể khác theo vùng)",
            supportedProtocols = listOf("USB PD 3.0", "PPS", "Samsung Super Fast Charging 25W"),
            pdoProfiles = "5V/3A, 9V/2,77A",
            ppsOrAvsRange = "PPS: 3,3–5,9V/3A hoặc 3,3–11,0V/2,25A",
            dimensionsWeight = "46,0 × 26,2 × 68,9 mm • 73g (bản kèm cáp; bản EU leaflet 38 × 66,9 × 22 mm / 51,6g, bản US đại lý 49,0 × 68,74 × 27,1 mm / 87,7g — đã chọn 1 biến thể)",
            accentColorHex = 0xFF1428A0,
            productImageUrl = "https://images.samsung.com/is/image/samsung/p6pim/us/ep-t2510xbegus/gallery/us-25w-power-adapter-ep-t2510-579798-ep-t2510xbegus-553272381?\$product-details-jpg\$",
            verificationStatus = "Thông số công bố của Samsung (2023: Amazon US Date First Available 11/10/2023; chưa có teardown)",
            sourceTitle = "Samsung US: 25W PD Power Adapter EP-T2510",
            sourceUrl = "https://www.samsung.com/us/mobile-accessories/25w-pd-power-adapter-with-usb-c-cable-black-sku-ep-t2510xbegus/"
        ),
        ChargerSpecProfile(
            brand = "Samsung",
            productName = "Samsung 45W PD Power Adapter",
            modelCode = "EP-T4511 (kèm cáp 5A US: EP-T4511XBEGUS; chỉ củ: EP-T4511NBEGUS)",
            maxPowerW = 45,
            portsConfig = "1x USB-C",
            primaryControllerIc = "Chưa công bố teardown",
            protocolChipIc = "Chưa công bố teardown",
            ganPowerFet = "Chưa xác minh hãng có ghi GaN cho mẫu này hay không — đừng ghi GaN",
            supportedProtocols = listOf("USB PD 3.0 PDO/PPS", "Samsung Super Fast Charging 2.0 45W"),
            pdoProfiles = "5V/3A, 9V/3A, 15V/3A, 20V/2,25A",
            ppsOrAvsRange = "PPS: 5,0–20,0V/2,25A (TUYỆT ĐỐI không dùng PPS 3,3–21V của mẫu cũ EP-T4510)",
            dimensionsWeight = "48 × 75,6 × 28 mm • 100,1g (Samsung HK, chân HK/UK; biến thể EU 43,5 × 81,8 × 28 mm / 92,5g — đã chọn 1 biến thể)",
            accentColorHex = 0xFF1428A0,
            productImageUrl = "https://images.samsung.com/is/image/samsung/p6pim/us/ep-t4511xbegus/gallery/us-45w-power-adapter-ep-t4511-ep-t4511xbegus-549253526?\$product-details-jpg\$",
            verificationStatus = "Thông số công bố của Samsung (năm ra mắt CHƯA XÁC MINH chính thức; chưa có teardown)",
            sourceTitle = "Samsung HK: 45W Power Adapter EP-T4511",
            sourceUrl = "https://www.samsung.com/hk_en/mobile-accessories/45w-power-adapter-black-ep-t4511xbeggb/"
        ),
        ChargerSpecProfile(
            brand = "Samsung",
            productName = "Samsung 60W Power Adapter",
            modelCode = "EP-T6010 (bản US: EP-T6010NBEGUS)",
            maxPowerW = 60,
            portsConfig = "1x USB-C",
            primaryControllerIc = "Chưa công bố teardown",
            protocolChipIc = "Chưa công bố teardown",
            ganPowerFet = "Chưa xác minh GaN — đừng ghi GaN",
            supportedProtocols = listOf("USB PD 3.1 PDO/PPS", "Samsung Super Fast Charging 3.0 60W"),
            pdoProfiles = "5V/3A, 9V/3A, 15V/3A, 20V/3A",
            ppsOrAvsRange = "PPS: 5–20V/3A",
            dimensionsWeight = "48,0 × 73,7 × 28,0 mm • 121,0g (Samsung UAE; biến thể LB 46,0 × 85,3 × 28 mm / 114,59g, IN 60 × 78,4 × 34 mm / 167g — đã chọn 1 biến thể)",
            accentColorHex = 0xFF1428A0,
            productImageUrl = "https://images.samsung.com/is/image/samsung/p6pim/us/ep-t6010nbegus/gallery/us-60w-power-adapter-ep-t6010-black-ep-t6010nbegus-553353279?\$product-details-jpg\$",
            verificationStatus = "Thông số công bố của Samsung (2026: Samsung Ấn Độ Launch date 25/02/2026; chưa có teardown)",
            sourceTitle = "Samsung US: 60W PD Power Adapter EP-T6010",
            sourceUrl = "https://www.samsung.com/us/mobile-accessories/60w-pd-power-adapter-black-sku-ep-t6010nbegus/"
        ),
        ChargerSpecProfile(
            brand = "Cuktech",
            productName = "CUKTECH 15 — 140W GaN Charger (4-Port)",
            modelCode = "AD1404U (bản US: AD1404US)",
            maxPowerW = 140,
            portsConfig = "3x USB-C + 1x USB-A (hướng dẫn: C1/C2 140W, C3 100W, USB-A 33W; đa cổng ví dụ C1+C2 = 100W+33W, 4 cổng = 65W+45W+5V/1,5A+5V/1,5A)",
            primaryControllerIc = "Chưa công bố teardown",
            protocolChipIc = "Chưa công bố teardown",
            ganPowerFet = "GaN (chi tiết IC chưa công bố)",
            supportedProtocols = listOf("USB PD 3.1", "PPS", "QC", "FCP", "SCP", "AFC (theo hướng dẫn)"),
            pdoProfiles = "Chưa xác minh (tài liệu tìm được không liệt kê bảng PDO cố định đầy đủ; chỉ biết điện áp tối đa 28V theo listing và chuẩn PD 3.1)",
            ppsOrAvsRange = "Chưa xác minh (chỉ biết hỗ trợ PPS, không có dải APDO)",
            dimensionsWeight = "79,9 × 65 × 32 mm • 335g (trang chính thức; Notebookcheck ghi ~331g và C3 33W / USB-A 18W — mâu thuẫn, cần đối chiếu nhãn máy thật)",
            accentColorHex = 0xFFFF6D00,
            productImageUrl = "https://cuktech.com/cdn/shop/files/44b91ce6d41dd0b0dace3ce9bbfc99fc_b666a930-54b8-4fe1-b5e0-d998af1e37a7.jpg?v=1767667815",
            verificationStatus = "Thông số công bố của hãng (2025, Notebookcheck 24/04/2025; chưa có teardown)",
            sourceTitle = "CUKTECH Official 140W + Notebookcheck 2025",
            sourceUrl = "https://cuktech.com/products/1406"
        ),
        ChargerSpecProfile(
            brand = "Cuktech",
            productName = "CUKTECH 10 Ultra Charger (120W, màn hình TFT)",
            modelCode = "AD1204U",
            maxPowerW = 120,
            portsConfig = "3x USB-C + 1x USB-A, màn hình TFT 1,57 inch, Bluetooth, Mi Home (C1/C2 120W chỉ khi input 200–240V; input 100–130V tối đa 100W; tổng đa cổng 120W với 2 đầu vào / 110W với 1 đầu vào)",
            primaryControllerIc = "Chưa công bố teardown",
            protocolChipIc = "Chưa công bố teardown",
            ganPowerFet = "GaN (chi tiết IC chưa công bố)",
            supportedProtocols = listOf("USB PD", "PPS", "UFCS", "Xiaomi HyperCharge 120W", "Huawei SCP/FCP", "QC (công nghệ ADC2.0)"),
            pdoProfiles = "C1/C2: 5V/3A, 9V/3A, 12V/3A, 15V/3A, 20V/5A, 11V/6A (100W); 20V/6A (120W, chỉ Input 2) • C3: 5V/3A, 9V/3A, 12V/2,5A, 15V/2A, 11V/4A (44W) • USB-A: 5V/3A, 9V/2A, 12V/1,5A, 11V/3A (33W)",
            ppsOrAvsRange = "Chưa xác minh (hướng dẫn chỉ ghi hỗ trợ PPS trong nhóm ADC2.0, không có dải APDO)",
            dimensionsWeight = "67 × 76 × 33 mm • ~234g (TaiwanNews)",
            accentColorHex = 0xFFFF6D00,
            productImageUrl = "https://manuals.plus/ae/1005009872196070/images/S42e1e8deac4949dcb6207bd51906b887X.jpg",
            verificationStatus = "Thông số từ hướng dẫn + báo chí (2026, Philippines; chưa tìm thấy trang chính thức cuktech.com cho mẫu này, chưa có teardown — ảnh tạm từ manuals.plus)",
            sourceTitle = "CUKTECH 10 Ultra — Manuals.plus + TaiwanNews 01/06/2026",
            sourceUrl = "https://manuals.plus/ae/1005009872196070"
        ),
        ChargerSpecProfile(
            brand = "Cuktech",
            productName = "CUKTECH Multi-ports GaN Charger 65W (2C1A, A15C)",
            modelCode = "A15CEU (hướng dẫn/FCC: AD653N)",
            maxPowerW = 65,
            portsConfig = "2x USB-C + 1x USB-A (C1/C2 65W, USB-A 5V/3A, 9V/2,5A, 10V/2,25A, 12V/1,87A)",
            primaryControllerIc = "Chưa công bố teardown",
            protocolChipIc = "Chưa công bố teardown",
            ganPowerFet = "GaN (chi tiết IC chưa công bố)",
            supportedProtocols = listOf("USB PD 3.0", "PPS", "QC 4+/3.0", "Samsung Super Fast Charging 2.0", "AFC", "FCP", "SCP"),
            pdoProfiles = "C1/C2: 5V/3A, 9V/3A, 12V/3A, 15V/3A, 20V/3,25A (65W)",
            ppsOrAvsRange = "Chưa xác minh (trang chính hãng chỉ ghi hỗ trợ PPS, không có dải APDO)",
            dimensionsWeight = "53 × 28 × 53,9 mm • 143g (cân nặng theo Digitec, không phải trang chính hãng)",
            accentColorHex = 0xFFFF6D00,
            productImageUrl = "https://cuktech.com/cdn/shop/files/A1501.jpg?v=1740972614",
            verificationStatus = "Thông số công bố của hãng (bằng chứng sớm nhất: file hướng dẫn 07/09/2023, chưa có thông cáo ra mắt chính thức; chưa có teardown)",
            sourceTitle = "CUKTECH Official A15C 65W GaN Charger 2C1A",
            sourceUrl = "https://cuktech.com/products/a15c-cuktech-65w-gan-charger-2c1a"
        ),
        ChargerSpecProfile(
            brand = "Ugreen",
            productName = "UGREEN Nexode Pro 100W 3-Port GaN Mini Fast Charger",
            modelCode = "X757 (SKU EU: 15203)",
            maxPowerW = 100,
            portsConfig = "2x USB-C + 1x USB-A",
            primaryControllerIc = "Chưa công bố teardown",
            protocolChipIc = "Chưa công bố teardown",
            ganPowerFet = "GaN (chi tiết IC chưa công bố)",
            supportedProtocols = listOf("USB PD 3.0", "PPS", "QC 4+/3.0/2.0", "SCP (10V/2,25A)", "FCP", "AFC", "Apple 5V/2,4A", "Samsung 5V/2A", "BC1.2"),
            pdoProfiles = "C1: 5V/3A, 9V/3A, 12V/3A, 15V/3A, 20V/5A (100W) • C2: 5V/3A, 9V/3A, 12V/2,5A (30W) • A: 5V/3A, 9V/2A, 12V/1,5A, 10V/2,25A (22,5W)",
            ppsOrAvsRange = "Chưa xác minh (trang chính hãng chỉ ghi có PPS, không công bố dải PPS)",
            dimensionsWeight = "71 × 43 × 33 mm • cân nặng chưa xác minh (trang chính hãng không ghi)",
            accentColorHex = 0xFF2EBD85,
            productImageUrl = "https://eu.ugreen.com/cdn/shop/products/nexode-pro-100w-3-port-gan-mini-fast-charger-993773.png?v=1702514271&width=400",
            verificationStatus = "Thông số công bố của hãng (dòng Nexode Pro công bố 20/12/2023; chưa có teardown)",
            sourceTitle = "UGREEN EU: Nexode Pro 100W + Mighty Gadget",
            sourceUrl = "https://eu.ugreen.com/products/ugreen-nexode-pro-100w-gan-fast-charger"
        ),
        ChargerSpecProfile(
            brand = "Ugreen",
            productName = "UGREEN Nexode Pro 160W 4-Port GaN Mini Fast Charger",
            modelCode = "X763 (SKU EU: 25095; bản Ấn Độ: 25877)",
            maxPowerW = 160,
            portsConfig = "3x USB-C + 1x USB-A (tổng 160W, cổng đơn tối đa 140W)",
            primaryControllerIc = "Chưa công bố teardown",
            protocolChipIc = "Chưa công bố teardown",
            ganPowerFet = "GaN (chi tiết IC chưa công bố)",
            supportedProtocols = listOf("UFCS", "USB PD 3.1/3.0", "PPS", "QC 5/QC 4+/QC 3+", "SCP (10V/2,25A)", "FCP", "AFC", "Apple 5V/2,4A", "Samsung 5V/2A", "BC1.2"),
            pdoProfiles = "C1: 5V/3A, 9V/3A, 12V/3A, 15V/3A, 20V/5A, 28V/5A (140W) • C2: 5V/3A, 9V/3A, 12V/3A, 15V/3A, 20V/5A (100W) • C3: 5V/3A, 9V/3A, 12V/2,5A (30W) • A: 5V/3A, 9V/2A, 12V/1,5A, 10V/2,25A (22,5W)",
            ppsOrAvsRange = "Chưa xác minh (trang chính hãng chỉ ghi có PPS, không công bố dải PPS)",
            dimensionsWeight = "70 × 70 × 33 mm • 443g (trang chính hãng EU)",
            accentColorHex = 0xFF2EBD85,
            productImageUrl = "https://eu.ugreen.com/cdn/shop/products/nexode-pro-160w-4-port-gan-mini-fast-charger-339786.png?v=1702514272&width=400",
            verificationStatus = "Thông số công bố của hãng (dòng Nexode Pro công bố 20/12/2023; chưa có teardown; Nexode 140W CD289 bị loại vì ra mắt 2022)",
            sourceTitle = "UGREEN EU: Nexode Pro 160W + Mighty Gadget",
            sourceUrl = "https://eu.ugreen.com/de/collections/3c-products/products/ugreen-nexode-pro-160w-gan-fast-charger"
        ),
        ChargerSpecProfile(
            brand = "Ugreen",
            productName = "UGREEN Nexode Air 65W USB-C Charger (GaN, Foldable Plug)",
            modelCode = "X860 (SKU toàn cầu: 95169; SKU US: 75813)",
            maxPowerW = 65,
            portsConfig = "1x USB-C (kèm cáp USB-C 1m 100W trong hộp theo Notebookcheck)",
            primaryControllerIc = "Chưa công bố teardown",
            protocolChipIc = "Chưa công bố teardown",
            ganPowerFet = "GaN (chi tiết IC chưa công bố)",
            supportedProtocols = listOf("USB PD 3.0", "PD 3.2 65W (đo TinkerVault)", "QC 3.0/2.0", "AFC", "FCP", "Apple 5V/2,4A", "BC1.2", "AVS SPR 9–20V/3A (đo TinkerVault)"),
            pdoProfiles = "5V/3A, 9V/3A, 12V/3A, 15V/3A, 20V/3,25A (65W)",
            ppsOrAvsRange = "PPS: 5–11V/5A (đo bằng KM003C, TinkerVault; hãng chỉ ghi chung là có PPS)",
            dimensionsWeight = "~40,5 × 33 × 31 mm • ~73g (Notebookcheck; bản EU TinkerVault: 33 × 31 × 40 mm / 76g — chênh do biến thể phích cắm)",
            accentColorHex = 0xFF2EBD85,
            productImageUrl = "https://us.ugreen.com/cdn/shop/files/ugreen-nexode-air-usb-c-charger-65w-gan-foldable-plug-3874335.webp?v=1778877729&width=100",
            verificationStatus = "Thông số công bố của hãng + đo kiểm (2026, dòng Nexode Air; chưa có teardown)",
            sourceTitle = "UGREEN US: Nexode Air 65W + Notebookcheck 23/06/2026",
            sourceUrl = "https://us.ugreen.com/products/nexode-air-usb-c-charger-65w-gan-foldable-plug"
        ),
        ChargerSpecProfile(
            brand = "CMF by Nothing",
            productName = "CMF Power 65W GaN (thương hiệu con của Nothing)",
            modelCode = "Chưa xác minh (mã model) — trang hỗ trợ CMF không ghi mã; các shop ghi mâu thuẫn C332 / A10800007",
            maxPowerW = 65,
            portsConfig = "2x USB-C + 1x USB-A (C1+C2 = 45W+20W; C1+A = 45W+18W; C1+C2+A = 45W+15W)",
            primaryControllerIc = "Chưa công bố teardown",
            protocolChipIc = "Chưa công bố teardown",
            ganPowerFet = "GaN",
            supportedProtocols = listOf("USB PD 3.0", "QC 4.0+/3.0/2.0", "SCP", "FCP", "PPS", "AFC", "Apple 2.4A", "Samsung 9V2A", "DCP"),
            pdoProfiles = "C1/C2: 5V/3A, 9V/3A, 12V/3A, 15V/3A, 20V/3,25A (65W) • A: 5V/3A, 9V/3A, 12V/3A (36W)",
            ppsOrAvsRange = "PPS: 3,3–11V/5A (16,5–55W) / 5–20V/3,25A (16,25–65W)",
            dimensionsWeight = "56,5 × 55 × 30 mm • 146,1g (bản US; bản UK 65,8 × 55 × 30 mm / 162g)",
            accentColorHex = 0xFFFF6A00,
            productImageUrl = "",
            verificationStatus = "Thông số từ bảng hãng trích trên trang bán lẻ (2023, công bố 26/09/2023; chưa có teardown; chưa có URL ảnh chính thức xác minh nên app hiển thị fallback hãng + W)",
            sourceTitle = "CMF Power 65W GaN — bảng thông số (Taham Tech) + Business Standard 26/09/2023",
            sourceUrl = "https://tahamtech.com.bd/product/cmf-by-nothing-power-65w-gan-fast-charger-dual-usb-c-usb-a/"
        ),
        ChargerSpecProfile(
            brand = "Nothing",
            productName = "Nothing Power (45W) — củ sạc chính hãng duy nhất của Nothing",
            modelCode = "C347 / C304 (theo trang bán lẻ; chưa xác minh chính thức)",
            maxPowerW = 45,
            portsConfig = "1x USB-C (không kèm cáp theo một số listing)",
            primaryControllerIc = "Chưa công bố teardown",
            protocolChipIc = "Chưa công bố teardown",
            ganPowerFet = "Chưa xác minh GaN — đừng ghi GaN",
            supportedProtocols = listOf("USB PD 3.0", "PPS", "QC 4.0+", "QC 3.0", "QC 2.0"),
            pdoProfiles = "5V/3A, 9V/3A, 12V/3A, 15V/3A, 20V/2,25A (45W)",
            ppsOrAvsRange = "PPS: 3,3–11V/4,05A và 3,3–21V/2,14A",
            dimensionsWeight = "33,4 × 44,8 × 89,5 mm (EU) • 101g (EU; US 96g, UK 113g, IN 121g, IN 33,4 × 44,8 × 79,6 mm — nguồn bán lẻ, chưa thống nhất)",
            accentColorHex = 0xFFF5F5F5,
            productImageUrl = "",
            verificationStatus = "Thông số từ trang bán lẻ (2022 — NGOÀI khung 3 năm, giữ vì là củ Nothing duy nhất; chưa có teardown; chưa có URL ảnh chính thức xác minh nên app hiển thị fallback hãng + W)",
            sourceTitle = "Nothing 45W Power Adapter — bảng thông số (CMF Bangladesh, bán lẻ)",
            sourceUrl = "https://cmf.com.bd/product/nothing-45w-power-adapter/"
        ),
        ChargerSpecProfile(
            brand = "Baseus",
            productName = "Baseus GaN2 Lite Quick Charger C+U 65W (ngoại lệ theo yêu cầu)",
            modelCode = "CGAN65US (theo yêu cầu — CHƯA XÁC MINH công khai; alias đã xác minh: CCGAN2L-B02 trắng EU / CCGAN2L-B01 đen EU)",
            maxPowerW = 65,
            portsConfig = "1x USB-C + 1x USB-A (KHÔNG phải single-port; C riêng 65W, A riêng 45W, đồng thời 45W+18W = 63W)",
            primaryControllerIc = "Chưa công bố teardown",
            protocolChipIc = "Chưa công bố teardown",
            ganPowerFet = "GaN2 (chi tiết IC chưa công bố)",
            supportedProtocols = listOf("USB PD 3.0", "QC 3.0", "PPS", "SCP", "FCP", "AFC", "QC 4.0/4.0+ cho cổng USB-A (Baltrade B01)"),
            pdoProfiles = "USB-C: 5V/3A, 9V/3A, 12V/3A, 15V/3A, 20V/3,25A • USB-A: 4,5V/5A, 5V/4,5A, 5V/3A, 9V/3A, 12V/3A, 20V/2,25A (Digitec; Baltrade B01 không liệt kê 4,5V/5A)",
            ppsOrAvsRange = "Chưa xác minh (các nguồn chỉ ghi tên PPS, không có dải APDO cho bản C+U)",
            dimensionsWeight = "100 × 35 × 30 mm • 115g (Digitec B02; B01 ghi 35 × 30 × 99 mm / 110g — cùng thân máy, chênh do cách đo)",
            accentColorHex = 0xFF00E5FF,
            productImageUrl = "https://www.shop.baltrade.eu/img/product/medium/baseus-gan2-lite-ccgan2l-b01-65w-szybka-ladowarka-sieciowa-z-gniazdem-usb-c-pd-3-0-oraz-usb-qc4-0-8.jpg",
            verificationStatus = "Một phần xác minh — mã CGAN65US và trang chính thức riêng chưa xác minh; thông số theo dòng CCGAN2L-B01/B02; 2022 (ngoài khung 3 năm, ngoại lệ theo yêu cầu); chưa có teardown; 'Lite' không có định nghĩa chính thức (chỉ ghi nhỏ/nhẹ hơn ~30% theo Baltrade)",
            sourceTitle = "Baseus GaN2 Lite C+U 65W — Digitec + Baltrade (CCGAN2L-B01/B02)",
            sourceUrl = "https://www.digitec.ch/en/s1/product/baseus-gan2-lite-quick-chargercu-65w-eu-white-65-w-1-portion-usb-chargers-21090035"
        )
    )

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
            "Đo từ VBUS×IBUS (${String.format("%.2fW", pUsed)}) [${sys.activeMode.shortTitle}]"
        } else {
            "Ước tính từ phía cell pin P_bat (${String.format("%.2fW", pUsed)}) [${sys.activeMode.shortTitle}]"
        }

        if (sys.isKernelProtocolVerified) {
            val rawUpper = sys.kernelProtocolRaw.uppercase()
            val parsedName = when {
                rawUpper.contains("PD_PPS") || rawUpper.contains("APDO") ->
                    "USB PD 3.0 / 3.1 — Chế độ PPS (APDO)"
                rawUpper.contains("PD_DRP") || rawUpper.contains("PD_ACTIVE=1") || rawUpper.contains("USB_TYPE=[C] PD") || rawUpper.contains("USB_PD") ->
                    "USB Power Delivery (USB PD Fixed PDO)"
                rawUpper.contains("HVDCP_3P5") -> "Qualcomm Quick Charge 3+ (QC 3.5)"
                rawUpper.contains("HVDCP_3") -> "Qualcomm Quick Charge 3.0"
                rawUpper.contains("HVDCP") -> "Qualcomm Quick Charge 2.0 (HVDCP)"
                rawUpper.contains("DUMPSYS_NEGOTIATED=HV_20V") -> "Sạc nhanh cao áp 20V (Đọc từ ADB dumpsys battery)"
                rawUpper.contains("DUMPSYS_NEGOTIATED=HV_15V") -> "Sạc nhanh 15V PD (Đọc từ ADB dumpsys battery)"
                rawUpper.contains("DUMPSYS_NEGOTIATED=FASTCHARGE_9V") -> "Sạc nhanh 9V PD / QC (Đọc từ ADB dumpsys battery)"
                rawUpper.contains("CDP") -> "USB Charging Downstream Port (CDP 5V/1.5A)"
                rawUpper.contains("DCP") -> "USB Dedicated Charging Port (DCP / Adapter)"
                else -> "Chuẩn báo từ hệ thống: ${sys.kernelProtocolRaw}"
            }

            val badgeText = if (sys.activeMode == AppPrivilegeMode.ROOT_FULL) {
                "✓ XÁC THỰC KERNEL (ROOT)"
            } else {
                "✓ XÁC THỰC QUA ADB (SHIZUKU)"
            }

            return ProtocolDetectionResult(
                isVerifiedFromKernel = true,
                primaryStandard = parsedName,
                technicalExplanation = "Nguồn dữ liệu (${sys.kernelProtocolPath}): [${sys.kernelProtocolRaw}].",
                powerBasisNote = powerSourceStr,
                verificationBadge = badgeText
            )
        }

        val modeHint = when (sys.activeMode) {
            AppPrivilegeMode.NORMAL_USER -> "Đang ở Chế độ 1 (Phổ thông): Không truy cập Kernel Node. Hãy chuyển sang Chế độ 2 (Shizuku) hoặc Chế độ 3 (Root) để đọc VBUS và chuẩn sạc chính xác hơn."
            AppPrivilegeMode.ADB_SHIZUKU -> "Đang ở Chế độ 2 (ADB Shizuku): Hãy đảm bảo Shizuku đang chạy và đã cấp quyền cho ứng dụng."
            AppPrivilegeMode.ROOT_FULL -> "Đang ở Chế độ 3 (Root): Thiết bị chưa cấp quyền 'su' hoặc ROM không xuất cờ giao thức."
        }

        return when {
            pUsed >= 50f -> ProtocolDetectionResult(
                isVerifiedFromKernel = false,
                primaryStandard = "Sạc siêu nhanh công suất cao (>50W tại Pin)",
                technicalExplanation = "$modeHint Với công suất nạp vào cell pin đạt ${String.format("%.1fW", pUsed)}, mạch sạc đang chạy chế độ Charge Pump dòng cao.",
                powerBasisNote = powerSourceStr,
                verificationBadge = "⚠️ ƯỚC TÍNH TỪ P_BAT (${sys.activeMode.shortTitle})"
            )
            pUsed >= 22f -> ProtocolDetectionResult(
                isVerifiedFromKernel = false,
                primaryStandard = "Sạc nhanh USB-PD / PPS (Ngưỡng 22W–50W tại Pin)",
                technicalExplanation = "$modeHint Công suất nạp phía pin đạt ${String.format("%.1fW", pUsed)} (tương đương đầu vào VBUS khoảng ${String.format("%.0fW–%.0fW", pUsed * 1.1f, pUsed * 1.2f)}).",
                powerBasisNote = powerSourceStr,
                verificationBadge = "⚠️ ƯỚC TÍNH TỪ P_BAT (${sys.activeMode.shortTitle})"
            )
            pUsed >= 12f -> ProtocolDetectionResult(
                isVerifiedFromKernel = false,
                primaryStandard = "Sạc nhanh cơ bản 12W–22W (PD 9V / QC 2.0–3.0 / PPS)",
                technicalExplanation = "$modeHint Công suất phía pin (${String.format("%.1fW", pUsed)}) tương ứng cấu hình 9V/2A hoặc giai đoạn giảm dòng khi pin >60%.",
                powerBasisNote = powerSourceStr,
                verificationBadge = "⚠️ ƯỚC TÍNH TỪ P_BAT (${sys.activeMode.shortTitle})"
            )
            else -> {
                val isHighSoc = telemetry.levelPercent >= 75
                ProtocolDetectionResult(
                    isVerifiedFromKernel = false,
                    primaryStandard = if (isHighSoc) "Giai đoạn sạc bão hòa CV Stage (>75% Pin)" else "Sạc tiêu chuẩn 5V (<12W tại Pin)",
                    technicalExplanation = if (isHighSoc) {
                        "Pin đã đạt ${telemetry.levelPercent}%, IC nguồn chủ động hạ dòng nạp xuống ${String.format("%.2fA", telemetry.batCurrentAmp)} để bảo vệ cell pin."
                    } else {
                        "$modeHint Công suất nạp hiện tại là ${String.format("%.1fW", pUsed)}."
                    },
                    powerBasisNote = powerSourceStr,
                    verificationBadge = "⚠️ ƯỚC TÍNH TỪ P_BAT"
                )
            }
        }
    }
}

// ============================================================================
// 5. SINGLETON REPOSITORY (GIỮ TRẠNG THÁI QUA XOAY MÀN HÌNH + STREAMING CSV)
// ============================================================================

object TelemetryRepository {
    private const val PREFS_NAME = "battery_monitor_v3_prefs"
    private const val KEY_SESSIONS = "saved_sessions_v3_json"
    private const val KEY_PRIVILEGE_MODE = "selected_privilege_mode_id"
    private const val MAX_UI_SAMPLES = 120

    private val _privilegeMode = MutableStateFlow(AppPrivilegeMode.NORMAL_USER)
    val privilegeMode: StateFlow<AppPrivilegeMode> = _privilegeMode.asStateFlow()

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

    private val _referenceCharger = MutableStateFlow(ChargerCatalogRepository.chargers[8])
    val referenceCharger: StateFlow<ChargerSpecProfile> = _referenceCharger.asStateFlow()

    private val _savedSessions = MutableStateFlow<List<ChargingSessionRecord>>(emptyList())
    val savedSessions: StateFlow<List<ChargingSessionRecord>> = _savedSessions.asStateFlow()

    private val _statusBanner = MutableStateFlow("")
    val statusBanner: StateFlow<String> = _statusBanner.asStateFlow()

    private var activeCsvFile: File? = null
    private var activeSessionRecord: ChargingSessionRecord? = null
    private var powerSumW: Double = 0.0
    private var lastSampleTimestampMs: Long = 0L

    fun initHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedModeId = prefs.getInt(KEY_PRIVILEGE_MODE, AppPrivilegeMode.NORMAL_USER.id)
        _privilegeMode.value = AppPrivilegeMode.fromId(savedModeId)
        _savedSessions.value = loadSessionsFromDisk(context)
    }

    fun setPrivilegeMode(context: Context, mode: AppPrivilegeMode) {
        _privilegeMode.value = mode
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PRIVILEGE_MODE, mode.id).apply()
        if (mode == AppPrivilegeMode.ADB_SHIZUKU) {
            PrivilegedTelemetryEngine.requestShizukuPermission()
        }
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

        val displayPower = newSnap.vbusInputPowerW ?: newSnap.effectiveBatPowerW
        val updatedHist = (_powerHistory.value + displayPower).takeLast(60)
        _powerHistory.value = updatedHist

        val proto = ChargerCatalogRepository.analyzeChargingProtocol(newSnap)

        if (newSnap.isCharging && _autoRecordEnabled.value && !_isRecording.value) {
            startStreamingSession(context, newSnap, proto.primaryStandard)
        } else if (!newSnap.isCharging && _isRecording.value) {
            stopAndFinalizeSession(context, newSnap.levelPercent)
        }

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
                writer.write("Thoi_Gian,Che_Do,V_Bat(V),I_Bat(A),P_Bat_Est(W),So_Cell,V_Bus(V),I_Bus(A),P_Vbus(W),Nhiet_Do(C),Pin(%),Chuan_Sac\n")
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
            privilegeModeUsed = _privilegeMode.value.shortTitle,
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
        rec.privilegeModeUsed = _privilegeMode.value.shortTitle

        if (snap.effectiveBatPowerW > rec.peakBatPowerW) rec.peakBatPowerW = snap.effectiveBatPowerW
        if (snap.vbusInputPowerW != null) {
            val curPeakVbus = rec.peakVbusPowerW ?: 0f
            if (snap.vbusInputPowerW > curPeakVbus) rec.peakVbusPowerW = snap.vbusInputPowerW
        }
        if (snap.temperatureC > rec.maxTempC) rec.maxTempC = snap.temperatureC
        rec.detectedProtocol = protocolLabel

        val vbusStr = snap.sysfsReport.vbusVoltageV?.let { String.format(Locale.US, "%.3f", it) } ?: "N/A"
        val ibusStr = snap.sysfsReport.ibusCurrentA?.let { String.format(Locale.US, "%.3f", it) } ?: "N/A"
        val pvbusStr = snap.vbusInputPowerW?.let { String.format(Locale.US, "%.2f", it) } ?: "N/A"
        val modeTag = snap.sysfsReport.activeMode.shortTitle

        runCatching {
            FileOutputStream(csvFile, true).bufferedWriter().use { writer ->
                writer.write(
                    "$timeStr,\"$modeTag\",${String.format(Locale.US, "%.3f", snap.batVoltageVolt)}," +
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
            modeName = modeTag,
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
            put("privilegeModeUsed", session.privilegeModeUsed)
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
                        privilegeModeUsed = o.optString("privilegeModeUsed", "1. Phổ thông"),
                        samplesCount = o.optInt("samplesCount"),
                        csvFilePath = o.optString("csvFilePath")
                    )
                )
            }
        }
        return list
    }

    fun deleteAllSessionsAndCsvFiles(context: Context) {
        val currentList = loadSessionsFromDisk(context)
        var deletedFilesCount = 0
        currentList.forEach { ses ->
            runCatching {
                val f = File(ses.csvFilePath)
                if (f.exists() && f.delete()) deletedFilesCount++
            }
        }
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
// 6. FOREGROUND SERVICE (GHI LOG BỀN BỈ KHI TẮT MÀN HÌNH / THOÁT RA NỀN)
// ============================================================================

class ChargingMonitorService : Service() {
    companion object {
        const val CHANNEL_ID = "battery_power_monitor_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_SERVICE = "com.example.batterypowermonitor.STOP_SERVICE"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

                val currentMode = TelemetryRepository.privilegeMode.value
                val sysReport = PrivilegedTelemetryEngine.probeByMode(currentMode)

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

                tickCount++
                if (tickCount % 2 == 0) {
                    val statusPrefix = if (latestIsCharging) "⚡ Sạc" else "🔋 Xả"
                    val powerLabel = if (vbusPowerW != null) {
                        "[${currentMode.shortTitle}] VBUS: ${String.format("%.1fW", vbusPowerW)} | Pin: ${String.format("%.1fW", batPowerW)}"
                    } else {
                        "[${currentMode.shortTitle}] P_pin: ${String.format("%.1fW", batPowerW)} (${String.format("%.2fV", latestBatVolt * cells)} × ${String.format("%.2fA", currentAmp)})"
                    }
                    val notifText = "$statusPrefix • $powerLabel • ${latestLevelPct}%"
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
            .setContentTitle("Battery Power Monitor (Skeuomorphism + Aqua v3.3)")
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
// 7. HỆ THỐNG DESIGN ENGINE "SKEUOMORPHISM + AQUA" (KIM LOẠI CHẢI XƯỚC, NÚT GEL, Ô LCD LÕM)
// ============================================================================

/**
 * Nền graphite chải xước tĩnh + thanh công cụ Aqua sáng, thay cho nền orb chuyển động cũ
 */
@Composable
fun AquaBrushedBackground(
    isCharging: Boolean,
    modifier: Modifier = Modifier
) {
    // Nền "graphite chải xước" tĩnh kiểu Skeuomorphism: không còn orb chuyển động
    // kiểu kính mờ cũ, vừa tiết kiệm pin vừa giống mặt panel của một thiết bị đo thật.
    // Lớp chrome Aqua sáng chỉ nằm ở thanh công cụ phía trên, còn vùng nội dung là
    // panel graphite tối để toàn bộ chữ sáng sẵn có trong app vẫn đọc được.
    val glowColor = if (isCharging) Color(0xFF00E676) else Color(0xFF38BDF8)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF1B2436), Color(0xFF0D1220), Color(0xFF080C16))
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Vân chải xước ngang: xen kẽ sợi sáng / sợi tối, mỗi sợi cách nhau 4px.
            var y = 0f
            var light = true
            while (y < size.height) {
                drawLine(
                    color = if (light) Color.White.copy(alpha = 0.025f) else Color.Black.copy(alpha = 0.05f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
                y += 4f
                light = !light
            }
            // Ánh sáng trạng thái tĩnh ở mép trên, không nhấp nháy, không animation.
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(glowColor.copy(alpha = 0.10f), Color.Transparent),
                    startY = 0f,
                    endY = size.height * 0.22f
                )
            )
        }
    }
}

/**
 * Tấm panel Skeuomorphism + Aqua: kim loại graphite có viền bevel (sáng trên, tối dưới),
 * bóng đổ thật và một dải gloss Aqua ở mép trên — thay cho lớp kính lông mờ cũ.
 * Giữ nguyên chữ ký hàm cũ (tintColor / glassAlpha / specularAccent) để mọi nơi gọi
 * AquaSkeuoCard trước đây không phải sửa lại tham số; glassAlpha nay hiểu là độ gloss.
 */
@Composable
fun AquaSkeuoCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    tintColor: Color = Color(0xFF1E293B),
    glassAlpha: Float = 0.52f,
    specularAccent: Color = Color.White,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val gloss = (glassAlpha * 0.45f).coerceIn(0.10f, 0.35f)
    val baseMod = modifier
        .shadow(
            elevation = 8.dp,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = 0.45f),
            spotColor = Color.Black.copy(alpha = 0.55f)
        )
        .clip(shape)
        .background(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = gloss),
                    tintColor,
                    tintColor,
                    Color.Black.copy(alpha = 0.28f)
                )
            )
        )
        // Bevel: viền ngoài tối + viền sáng trong mô phỏng mép kim loại được vát.
        .border(BorderStroke(1.dp, Color.Black.copy(alpha = 0.65f)), shape = shape)
        .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), shape = shape)
        .let { m -> if (onClick != null) m.clickable { onClick() } else m }

    Box(modifier = baseMod) {
        // Dải gloss Aqua ở mép trên: sáng nhất ở đỉnh rồi mờ dần xuống, như nút gel macOS X.
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .align(Alignment.TopCenter)
        ) {
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.28f),
                        Color.White.copy(alpha = 0.06f),
                        Color.Transparent
                    )
                ),
                cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
            )
            // Đường viền sáng 1px ngay mép trên của panel.
            drawLine(
                color = specularAccent.copy(alpha = 0.45f),
                start = Offset(8f, 1f),
                end = Offset(size.width - 8f, 1f),
                strokeWidth = 1f,
                cap = StrokeCap.Round
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

/**
 * Thanh công cụ Aqua kiểu macOS X: chrome sáng, segmented control gel, đèn LED trạng thái.
 * Dấu ấn Bé chọn: thanh chrome sáng + panel nội dung graphite tối (như một máy đo để bàn:
 * vỏ chrome, mặt máy tối), thay vì toàn bộ app sáng hay toàn bộ app kính mờ.
 */
@Composable
fun AquaTopBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    isTabletLandscape: Boolean,
    splitLeftPane: Int,
    splitRightPane: Int,
    onSplitLeftChange: (Int) -> Unit,
    onSplitRightChange: (Int) -> Unit,
    privilegeMode: AppPrivilegeMode,
    isRecording: Boolean,
    samplesCount: Int,
    savedSessionsCount: Int,
    isCharging: Boolean,
    modifier: Modifier = Modifier
) {
    val glowColor = if (isCharging) Color(0xFF00E676) else Color(0xFF38BDF8)
    val menuShape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .shadow(
                elevation = 6.dp,
                shape = menuShape,
                ambientColor = Color.Black.copy(alpha = 0.35f),
                spotColor = Color.Black.copy(alpha = 0.45f)
            )
            .clip(menuShape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFF8FAFC),
                        Color(0xFFDCE6F2),
                        Color(0xFFB9C9DE)
                    )
                )
            )
            .border(BorderStroke(1.dp, Color.Black.copy(alpha = 0.35f)), shape = menuShape)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)), shape = menuShape)
    ) {
        // Dải gloss Aqua trên cùng của thanh công cụ.
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .align(Alignment.TopCenter)
        ) {
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.65f),
                        Color.White.copy(alpha = 0.10f),
                        Color.Transparent
                    )
                ),
                cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx())
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.55f),
                                        glowColor,
                                        glowColor.copy(alpha = 0.75f)
                                    )
                                )
                            )
                            .border(1.dp, Color.Black.copy(alpha = 0.30f), RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "⚡", fontSize = 17.sp)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isTabletLandscape) {
                                "Battery Power Monitor • Skeuomorphism + Aqua"
                            } else {
                                "Battery Power Monitor • Skeuomorphism + Aqua"
                            },
                            color = Color(0xFF0F172A),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isTabletLandscape) {
                                "Chrome Aqua • Panel graphite • Nút gel • Ô LCD lõm"
                            } else {
                                "3 Chế độ: Phổ thông • ADB (Shizuku) • Root Toàn Quyền"
                            },
                            color = Color(0xFF475569),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Đèn LED trạng thái kiểu thiết bị thật: xanh lá khi đang REC, xanh dương khi chờ.
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.70f))
                        .border(
                            1.dp,
                            Color.Black.copy(alpha = 0.25f),
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .shadow(4.dp, CircleShape, ambientColor = if (isRecording) Color(0xFF00C853) else Color(0xFF38BDF8))
                                .clip(CircleShape)
                                .background(if (isRecording) Color(0xFF00C853) else Color(0xFF38BDF8))
                                .border(1.dp, Color.Black.copy(alpha = 0.25f), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isRecording) "REC CSV ($samplesCount)" else privilegeMode.shortTitle,
                            color = Color(0xFF0F172A),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Điều hướng kiểu Aqua segmented control (Bé chọn thay cho pill kính mờ):
            // - Điện thoại dọc: 1 segmented control chọn 1 trong 3 Tab.
            // - Tablet xoay ngang: 2 segmented control độc lập cho Cửa sổ Trái / Phải.
            val paneTitles = listOf(
                0 to "⚡ Đo & Chế Độ",
                1 to "🔌 Tra Cứu Củ Sạc",
                2 to "📈 Nhật Ký CSV ($savedSessionsCount)"
            )

            if (!isTabletLandscape) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    paneTitles.forEach { (idx, title) ->
                        val active = selectedTab == idx
                        AquaSegmentButton(
                            text = title,
                            isSelected = active,
                            accentColor = glowColor,
                            onClick = { onSelectTab(idx) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Cửa sổ Trái:",
                            color = Color(0xFF475569),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        paneTitles.forEach { (idx, title) ->
                            AquaSegmentButton(
                                text = title,
                                isSelected = splitLeftPane == idx,
                                accentColor = Color(0xFF00C853),
                                onClick = { onSplitLeftChange(idx) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Cửa sổ Phải:",
                            color = Color(0xFF475569),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        paneTitles.forEach { (idx, title) ->
                            AquaSegmentButton(
                                text = title,
                                isSelected = splitRightPane == idx,
                                accentColor = Color(0xFF38BDF8),
                                onClick = { onSplitRightChange(idx) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AquaSegmentButton(
    text: String,
    isSelected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Nút gel Aqua: khi chọn thì là gel xanh bóng, khi không chọn thì là nút kim loại
    // sáng với chữ tối; khi nhấn (đang chọn) nút trông như bị lõm xuống nhờ viền tối hơn.
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .shadow(
                elevation = if (isSelected) 2.dp else 4.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.30f),
                spotColor = Color.Black.copy(alpha = 0.35f)
            )
            .clip(shape)
            .background(
                brush = if (isSelected) {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.45f),
                            accentColor,
                            accentColor.copy(alpha = 0.70f)
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White,
                            Color(0xFFD7DEE8),
                            Color(0xFFB8C4D4)
                        )
                    )
                }
            )
            .border(
                width = 1.dp,
                color = if (isSelected) Color.Black.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.30f),
                shape = shape
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = if (isSelected) 0.35f else 0.65f),
                shape = shape
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) Color.White else Color(0xFF334155),
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ============================================================================
// 8. MAIN ACTIVITY & BỐ CỤC ĐÁP ỨNG (PORTRAIT vs TABLET PARALLEL WINDOWS)
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
        startMonitoringForegroundService()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E676),
                    secondary = Color(0xFF38BDF8),
                    background = Color(0xFF050814),
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
    val configuration = LocalConfiguration.current

    // Nhận diện chế độ Máy tính bảng hoặc màn hình xoay ngang (Parallel Split-Window Mode)
    val isTabletOrLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            configuration.screenWidthDp >= 600

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

    val privilegeMode by TelemetryRepository.privilegeMode.collectAsState()
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
    var splitLeftPane by remember { mutableIntStateOf(0) }  // Mặc định Cửa sổ Trái: Đo & Chế độ
    var splitRightPane by remember { mutableIntStateOf(1) } // Mặc định Cửa sổ Phải: Tra cứu Củ sạc / Nhật ký CSV
    var selectedBrandFilter by remember { mutableStateOf("Tất cả") }

    val protocolResult = remember(telemetry) {
        ChargerCatalogRepository.analyzeChargingProtocol(telemetry)
    }

    val topOverlayClearance = if (isTabletOrLandscape) 118.dp else 122.dp

    @Composable
    fun RenderPaneByIndex(paneIndex: Int, topPadding: Dp) {
        when (paneIndex) {
            0 -> RealtimeDashboardTab(
                privilegeMode = privilegeMode,
                onPrivilegeModeChange = { TelemetryRepository.setPrivilegeMode(context, it) },
                telemetry = telemetry,
                protocolResult = protocolResult,
                referenceCharger = referenceCharger,
                cellSeriesCount = cellSeriesCount,
                onCellSeriesChange = { TelemetryRepository.setCellSeriesCount(it) },
                powerHistory = powerHistory,
                refreshIntervalMs = refreshIntervalMs,
                onIntervalChange = { TelemetryRepository.setRefreshInterval(it) },
                onOpenChargerCatalog = {
                    if (isTabletOrLandscape) splitRightPane = 1 else selectedTab = 1
                },
                topOverlayPadding = topPadding
            )
            1 -> ChargerDatabaseTab(
                selectedBrand = selectedBrandFilter,
                onBrandChange = { selectedBrandFilter = it },
                referenceCharger = referenceCharger,
                onSelectCharger = { TelemetryRepository.setReferenceCharger(it) },
                topOverlayPadding = topPadding
            )
            else -> ChargingLogHistoryTab(
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
                onClearHistory = { TelemetryRepository.deleteAllSessionsAndCsvFiles(context) },
                topOverlayPadding = topPadding
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // LỚP 0 (DƯỚI CÙNG): Nền graphite chải xước tĩnh kiểu Skeuomorphism
        AquaBrushedBackground(isCharging = telemetry.isCharging)

        // LỚP 1 (GIỮA): Nội dung ứng dụng (1 cửa sổ ở Portrait hoặc 2 cửa sổ song song ở Tablet Landscape)
        if (!isTabletOrLandscape) {
            Box(modifier = Modifier.fillMaxSize()) {
                RenderPaneByIndex(paneIndex = selectedTab, topPadding = topOverlayClearance)
            }
        } else {
            // BỐ CỤC CỬA SỔ SONG SONG DÀNH CHO MÁY TÍNH BẢNG / MÀN HÌNH XOAY NGANG
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Cửa sổ Song Song Bên Trái
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    RenderPaneByIndex(paneIndex = splitLeftPane, topPadding = topOverlayClearance)
                }

                // Rãnh kim loại (groove) phân tách 2 cửa sổ song song
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .fillMaxHeight()
                        .padding(top = topOverlayClearance, bottom = 16.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.65f),
                                    Color.White.copy(alpha = 0.18f),
                                    Color.Black.copy(alpha = 0.65f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                // Cửa sổ Song Song Bên Phải
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    RenderPaneByIndex(paneIndex = splitRightPane, topPadding = topOverlayClearance)
                }
            }
        }

        // LỚP 2 (TRÊN CÙNG - Z-INDEX CAO NHẤT): Thanh công cụ Aqua
        AquaTopBar(
            selectedTab = selectedTab,
            onSelectTab = { selectedTab = it },
            isTabletLandscape = isTabletOrLandscape,
            splitLeftPane = splitLeftPane,
            splitRightPane = splitRightPane,
            onSplitLeftChange = { splitLeftPane = it },
            onSplitRightChange = { splitRightPane = it },
            privilegeMode = privilegeMode,
            isRecording = isRecording,
            samplesCount = recentSamples.size,
            savedSessionsCount = savedSessions.size,
            isCharging = telemetry.isCharging,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(10f)
        )
    }
}

// ============================================================================
// TAB 1: BỘ CHỌN 3 CHẾ ĐỘ + BẢNG ĐO CÔNG SUẤT THỜI GIAN THỰC (SKEUOMORPHISM + AQUA)
// ============================================================================

@Composable
fun RealtimeDashboardTab(
    privilegeMode: AppPrivilegeMode,
    onPrivilegeModeChange: (AppPrivilegeMode) -> Unit,
    telemetry: BatteryTelemetry,
    protocolResult: ProtocolDetectionResult,
    referenceCharger: ChargerSpecProfile,
    cellSeriesCount: Int,
    onCellSeriesChange: (Int) -> Unit,
    powerHistory: List<Float>,
    refreshIntervalMs: Long,
    onIntervalChange: (Long) -> Unit,
    onOpenChargerCatalog: () -> Unit,
    topOverlayPadding: Dp = 120.dp
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = topOverlayPadding, bottom = 20.dp)
    ) {
        // 1. Thẻ Chọn 3 Chế Độ Hoạt Động (Panel Skeuo)
        item {
            AquaSkeuoCard(
                specularAccent = Color(privilegeMode.badgeColorHex)
            ) {
                Column(modifier = Modifier.padding(15.dp)) {
                    Text(
                        text = "CHỌN CHẾ ĐỘ QUYỀN TRUY CẬP HỆ THỐNG (3 CHẾ ĐỘ)",
                        color = Color(0xFF7DD3FC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppPrivilegeMode.entries.forEach { mode ->
                            AquaSegmentButton(
                                text = mode.shortTitle,
                                isSelected = privilegeMode == mode,
                                accentColor = Color(mode.badgeColorHex),
                                onClick = { onPrivilegeModeChange(mode) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF090D1A).copy(alpha = 0.58f))
                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                text = privilegeMode.fullTitle,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = privilegeMode.description,
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Trạng thái: ${telemetry.sysfsReport.privilegeStatusText}",
                                color = if (telemetry.sysfsReport.isPrivilegeGranted) Color(0xFF00E676) else Color(0xFFFBBF24),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )

                            if (telemetry.sysfsReport.extraDeveloperMetrics.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "🛠️ ${telemetry.sysfsReport.extraDeveloperMetrics}",
                                    color = Color(0xFF7DD3FC),
                                    fontSize = 11.sp
                                )
                            }

                            if (privilegeMode == AppPrivilegeMode.ADB_SHIZUKU && !telemetry.sysfsReport.isPrivilegeGranted) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AquaSegmentButton(
                                        text = "Cấp quyền Shizuku",
                                        isSelected = true,
                                        accentColor = Color(0xFF0284C7),
                                        onClick = { PrivilegedTelemetryEngine.requestShizukuPermission() }
                                    )
                                    AquaSegmentButton(
                                        text = "Tải Shizuku ↗",
                                        isSelected = false,
                                        accentColor = Color(0xFF38BDF8),
                                        onClick = { openExternalUrl(context, "https://shizuku.rikka.app/download/") }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Thẻ Công suất chính — Panel Skeuo
        item {
            val statusColor = if (telemetry.isCharging) Color(0xFF00E676) else Color(0xFFFFB74D)
            AquaSkeuoCard(
                cornerRadius = 26.dp,
                specularAccent = statusColor,
                glassAlpha = 0.56f
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (telemetry.isCharging) "⚡ ĐANG SẠC (${telemetry.plugType})" else "🔋 ĐANG XẢ PIN",
                        color = statusColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    val animatedBatPower by animateFloatAsState(targetValue = telemetry.effectiveBatPowerW, label = "bat_power")
                    Text(
                        text = String.format("%.2f W", animatedBatPower),
                        color = Color.White,
                        fontSize = 46.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.10f))
                            .border(1.dp, Color(0xFFFBBF24).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "CÔNG SUẤT ƯỚC TÍNH TỪ PHÍA CELL PIN (P_bat = V_bat × I_bat × ${cellSeriesCount}S)",
                            color = Color(0xFFFDE68A),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF090D1A).copy(alpha = 0.58f))
                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    ) {
                        Column {
                            if (telemetry.vbusInputPowerW != null) {
                                Text(
                                    text = "✓ Công suất cổng sạc VBUS (${privilegeMode.shortTitle}): ${String.format("%.2f W", telemetry.vbusInputPowerW)} " +
                                            "(${String.format("%.2fV", telemetry.sysfsReport.vbusVoltageV)} × ${String.format("%.2fA", telemetry.sysfsReport.ibusCurrentA)})",
                                    color = Color(0xFF00E676),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                val reasonText = when (privilegeMode) {
                                    AppPrivilegeMode.NORMAL_USER ->
                                        "ℹ️ Bạn đang ở Chế độ 1 (Phổ thông): Ứng dụng chỉ tính công suất từ phía cell pin (luôn thấp hơn công suất đầu ra củ sạc khoảng 10–20% do hao phí mạch sạc). Hãy chọn Chế độ 2 (Shizuku) hoặc Chế độ 3 (Root) để đọc VBUS."
                                    AppPrivilegeMode.ADB_SHIZUKU ->
                                        "ℹ️ Chế độ 2 (ADB Shizuku): Chưa lấy được VBUS/IBUS (Hãy bật Shizuku hoặc cắm sạc để đọc Max Charging Voltage/Current từ dumpsys)."
                                    AppPrivilegeMode.ROOT_FULL ->
                                        "ℹ️ Chế độ 3 (Root): Thiết bị chưa cấp quyền su hoặc mạch PMIC không xuất thông số IBUS."
                                }
                                Text(
                                    text = reasonText,
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Cấu trúc cell pin của máy:",
                            color = Color(0xFFBAE6FD),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AquaSegmentButton(
                                text = "1S (Pin đơn ~4.4V)",
                                isSelected = cellSeriesCount == 1,
                                accentColor = Color(0xFF38BDF8),
                                onClick = { onCellSeriesChange(1) }
                            )
                            AquaSegmentButton(
                                text = "2S (Pin kép ×2)",
                                isSelected = cellSeriesCount == 2,
                                accentColor = Color(0xFF00E676),
                                onClick = { onCellSeriesChange(2) }
                            )
                        }
                    }
                }
            }
        }

        // 3. Thẻ Nhận diện Chuẩn Sạc (Panel Skeuo)
        item {
            val accent = if (protocolResult.isVerifiedFromKernel) Color(0xFF00E676) else Color(0xFFF59E0B)
            AquaSkeuoCard(
                tintColor = if (protocolResult.isVerifiedFromKernel) Color(0xFF064E3B) else Color(0xFF1E293B),
                specularAccent = accent
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CHẨN ĐOÁN CƠ CHẾ SẠC",
                            color = Color(0xFF7DD3FC),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(accent.copy(alpha = 0.28f))
                                .border(1.dp, accent.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = protocolResult.verificationBadge,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
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
                        color = Color(0xFFE2E8F0),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // 4. Lưới thông số Điện áp Cell Pin & Dòng điện nạp Pin
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
                    accentColor = Color(0xFF38BDF8)
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Điện áp Cell Pin (V_bat)",
                    mainValue = String.format("%.3f V", telemetry.batVoltageVolt * cellSeriesCount),
                    subValue = "1 Cell: ${String.format("%.0f mV", telemetry.batVoltageVolt * 1000)} (${cellSeriesCount}S)",
                    accentColor = Color(0xFFC084FC)
                )
            }
        }

        // 5. Bảng kiểm tra trực tiếp trạng thái đọc Node theo Chế độ
        item {
            AquaSkeuoCard(specularAccent = Color(0xFF38BDF8)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "THÔNG SỐ TRUY XUẤT THEO CHẾ ĐỘ: ${privilegeMode.shortTitle.uppercase()}",
                        color = Color(0xFFBAE6FD),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SysfsStatusRow(
                        label = "Điện áp cổng VBUS:",
                        value = telemetry.sysfsReport.vbusVoltageV?.let { String.format("%.2f V", it) } ?: "Không có (Hạn chế quyền)",
                        path = telemetry.sysfsReport.vbusSourcePath,
                        isOk = telemetry.sysfsReport.vbusVoltageV != null
                    )
                    SysfsStatusRow(
                        label = "Dòng đầu vào IBUS:",
                        value = telemetry.sysfsReport.ibusCurrentA?.let { String.format("%.2f A", it) } ?: "Không có (Hạn chế quyền)",
                        path = telemetry.sysfsReport.ibusSourcePath,
                        isOk = telemetry.sysfsReport.ibusCurrentA != null
                    )
                    SysfsStatusRow(
                        label = "Cờ giao thức PD/QC:",
                        value = telemetry.sysfsReport.kernelProtocolRaw.ifEmpty { "Không có (Hạn chế quyền)" },
                        path = telemetry.sysfsReport.kernelProtocolPath,
                        isOk = telemetry.sysfsReport.isKernelProtocolVerified
                    )
                }
            }
        }

        // 6. Thẻ Củ Sạc Đối Chiếu Thủ Công
        item {
            AquaSkeuoCard(
                specularAccent = Color(referenceCharger.accentColorHex),
                onClick = onOpenChargerCatalog
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
                                color = Color(0xFFFDE68A),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "Đổi mẫu ➔",
                                color = Color(0xFF7DD3FC),
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
                            color = Color(0xFFCBD5E1),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 7. Lưới thông số phụ: Mức pin, Nhiệt độ, Sức khỏe
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

        // 8. Biểu đồ công suất thời gian thực
        item {
            AquaSkeuoCard(
                specularAccent = Color(0xFF00E676),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
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
                            color = Color(0xFFBAE6FD),
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

        // 9. Tùy chỉnh tốc độ lấy mẫu
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Chu kỳ lấy mẫu:", color = Color(0xFFBAE6FD), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                listOf(500L to "0.5s", 1000L to "1.0s", 2000L to "2.0s").forEach { (ms, label) ->
                    AquaSegmentButton(
                        text = label,
                        isSelected = refreshIntervalMs == ms,
                        accentColor = Color(0xFF00E676),
                        onClick = { onIntervalChange(ms) }
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
            Text(text = label, color = Color(0xFFE2E8F0), fontSize = 11.sp)
            Text(
                text = value,
                color = if (isOk) Color(0xFF00E676) else Color(0xFFFCA5A5),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = path,
            color = Color(0xFF94A3B8),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ============================================================================
// TAB 2: THƯ VIỆN TRA CỨU CỦ SẠC & NGUỒN TEARDOWN KIỂM CHỨNG (SKEUOMORPHISM + AQUA)
// ============================================================================

@Composable
fun ChargerDatabaseTab(
    selectedBrand: String,
    onBrandChange: (String) -> Unit,
    referenceCharger: ChargerSpecProfile,
    onSelectCharger: (ChargerSpecProfile) -> Unit,
    topOverlayPadding: Dp = 120.dp
) {
    val context = LocalContext.current
    val brands = listOf("Tất cả", "Apple", "Baseus", "Belkin", "Xiaomi", "Anker", "Samsung", "Cuktech", "Ugreen", "CMF by Nothing", "Nothing")
    val filteredChargers = remember(selectedBrand) {
        if (selectedBrand == "Tất cả") ChargerCatalogRepository.chargers
        else ChargerCatalogRepository.chargers.filter { it.brand == selectedBrand }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = topOverlayPadding, bottom = 20.dp)
    ) {
        item {
            AquaSkeuoCard(
                tintColor = Color(0xFF172554),
                specularAccent = Color(0xFF38BDF8)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "ℹ️ Lưu ý kỹ thuật về Thư viện Củ sạc & Mã IC",
                        color = Color(0xFF7DD3FC),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Giao thức USB-C PD không truyền tên thương mại hay mã IC bên trong củ sạc sang điện thoại. Danh mục dưới đây là thư viện tra cứu kỹ thuật từ tài liệu hãng & báo cáo mổ xẻ (Teardown) của ChargerLAB để bạn chọn thủ công mẫu củ sạc đang test và đối chiếu thông số PDO/PPS.",
                        color = Color(0xFFE2E8F0),
                        fontSize = 11.sp
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                brands.forEach { brand ->
                    AquaSegmentButton(
                        text = brand,
                        isSelected = selectedBrand == brand,
                        accentColor = Color(0xFF38BDF8),
                        onClick = { onBrandChange(brand) }
                    )
                }
            }
        }

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

@Composable
fun ChargerDetailSpecCard(
    charger: ChargerSpecProfile,
    isSelected: Boolean,
    onClick: () -> Unit,
    onOpenSource: () -> Unit
) {
    AquaSkeuoCard(
        tintColor = if (isSelected) Color(0xFF064E3B) else Color(0xFF1E293B),
        specularAccent = if (isSelected) Color(0xFF00E676) else Color(charger.accentColorHex),
        onClick = onClick
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
                        color = Color(0xFF7DD3FC),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Kiểm chứng: ${charger.verificationStatus}",
                        color = Color(0xFFFDE68A),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = Color.White.copy(alpha = 0.12f))
            Spacer(modifier = Modifier.height(8.dp))

            SpecRowItem("Cấu hình cổng & Kích thước:", "${charger.portsConfig} (${charger.dimensionsWeight})")
            SpecRowItem("IC Nguồn chính (PWM/PFC):", charger.primaryControllerIc)
            SpecRowItem("IC Giao thức (Protocol IC):", charger.protocolChipIc)
            SpecRowItem("Bán dẫn công suất (GaN/MOS):", charger.ganPowerFet)
            SpecRowItem("Các mức PDO công bố:", charger.pdoProfiles)
            SpecRowItem("Dải PPS / AVS:", charger.ppsOrAvsRange)

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🔗 Nguồn: ${charger.sourceTitle}",
                    color = Color(0xFF7DD3FC),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenSource() }
                )
                Spacer(modifier = Modifier.width(8.dp))
                AquaSegmentButton(
                    text = "Mở nguồn ↗",
                    isSelected = false,
                    accentColor = Color(0xFF38BDF8),
                    onClick = onOpenSource
                )
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
            color = Color(0xFFBAE6FD),
            fontSize = 11.sp,
            modifier = Modifier.width(165.dp)
        )
        Text(
            text = value,
            color = Color.White,
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
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.5.dp, Color(charger.accentColorHex).copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
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
// TAB 3: NHẬT KÝ SẠC STREAMING CSV + CHIA SẺ FILE (SKEUOMORPHISM + AQUA)
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
    onClearHistory: () -> Unit,
    topOverlayPadding: Dp = 120.dp
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = topOverlayPadding, bottom = 20.dp)
    ) {
        item {
            AquaSkeuoCard(specularAccent = Color(0xFF00E676)) {
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
                                text = "Mỗi mẫu đo (kèm Chế độ Phổ thông / Shizuku / Root) được ghi nối tiếp ngay vào file .csv trên bộ nhớ",
                                color = Color(0xFFCBD5E1),
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
                            color = Color(0xFF7DD3FC),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        AquaSegmentButton(
                            text = if (isRecordingSession) "Đóng & Lưu phiên ngay" else "Bắt đầu ghi thủ công",
                            isSelected = true,
                            accentColor = if (isRecordingSession) Color(0xFFF59E0B) else Color(0xFF00E676),
                            onClick = onManualStartOrStop
                        )
                    }
                }
            }
        }

        // Bảng mẫu đo gần nhất
        item {
            AquaSkeuoCard(specularAccent = Color(0xFF38BDF8)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Dòng dữ liệu trực tiếp (${recentSamples.size}/120 mẫu hiển thị — Toàn bộ mẫu đã nằm trong CSV)",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF090D1A).copy(alpha = 0.65f))
                            .padding(vertical = 6.dp, horizontal = 8.dp)
                    ) {
                        Text("Giờ", color = Color(0xFFBAE6FD), fontSize = 11.sp, modifier = Modifier.weight(1.2f))
                        Text("V_bat", color = Color(0xFFBAE6FD), fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("I_bat", color = Color(0xFFBAE6FD), fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("P_bat", color = Color(0xFFBAE6FD), fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("Nhiệt/Pin", color = Color(0xFFBAE6FD), fontSize = 11.sp, modifier = Modifier.weight(1.2f))
                    }

                    if (recentSamples.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Cắm sạc hoặc bấm 'Bắt đầu ghi thủ công' để ghi mẫu...",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            recentSamples.asReversed().take(15).forEach { s ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp, horizontal = 8.dp)
                                ) {
                                    Text(s.timeFormatted, color = Color(0xFFE2E8F0), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1.2f))
                                    Text(String.format("%.2fV", s.batVoltageV), color = Color(0xFFC084FC), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                    Text(String.format("%.2fA", s.batCurrentA), color = Color(0xFF38BDF8), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                    Text(String.format("%.1fW", s.batPowerW), color = Color(0xFF00E676), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                    Text("${String.format("%.1f°", s.tempC)}/${s.levelPct}%", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1.2f))
                                }
                                Divider(color = Color.White.copy(alpha = 0.06f))
                            }
                        }
                    }
                }
            }
        }

        // Danh sách các phiên sạc đã lưu
        item {
            AquaSkeuoCard(specularAccent = Color(0xFF8B5CF6)) {
                Column(modifier = Modifier.padding(14.dp)) {
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
                                color = Color(0xFFFCA5A5),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { onClearHistory() }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (savedSessions.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Chưa có phiên sạc nào được lưu.",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            savedSessions.forEach { ses ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF090D1A).copy(alpha = 0.62f))
                                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "[${ses.privilegeModeUsed}] ${ses.startTime} ➔ ${ses.endTime}",
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
                                            color = Color(0xFF7DD3FC),
                                            fontSize = 11.sp
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "File: ${File(ses.csvFilePath).name}",
                                                color = Color(0xFFCBD5E1),
                                                fontSize = 10.sp,
                                                modifier = Modifier.weight(1f)
                                            )
                                            AquaSegmentButton(
                                                text = "📤 Chia sẻ / Mở CSV",
                                                isSelected = true,
                                                accentColor = Color(0xFF00E676),
                                                onClick = { onShareCsv(ses.csvFilePath) }
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
    }
}

// ============================================================================
// CÁC COMPONENT DÙNG CHUNG (SKEUOMORPHISM + AQUA)
// ============================================================================

@Composable
fun MetricCard(
    modifier: Modifier = Modifier,
    title: String,
    mainValue: String,
    subValue: String,
    accentColor: Color
) {
    AquaSkeuoCard(
        modifier = modifier,
        cornerRadius = 18.dp,
        specularAccent = accentColor
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = title, color = Color(0xFFBAE6FD), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = mainValue,
                color = accentColor,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(text = subValue, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
        }
    }
}

@Composable
fun SmallMetricCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String
) {
    AquaSkeuoCard(
        modifier = modifier,
        cornerRadius = 14.dp,
        specularAccent = Color(0xFF7DD3FC)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, color = Color(0xFFBAE6FD), fontSize = 11.sp)
            Spacer(modifier = Modifier.height(3.dp))
            Text(text = value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
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
                colors = listOf(
                    Color(0xFF00E676).copy(alpha = 0.42f),
                    Color(0xFF38BDF8).copy(alpha = 0.15f),
                    Color.Transparent
                )
            )
        )

        drawPath(
            path = strokePath,
            brush = Brush.horizontalGradient(
                colors = listOf(Color(0xFF38BDF8), Color(0xFF00E676))
            ),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )

        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(
                color = Color.White.copy(alpha = 0.12f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}