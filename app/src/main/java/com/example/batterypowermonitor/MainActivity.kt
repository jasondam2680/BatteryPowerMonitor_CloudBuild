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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Mô hình dữ liệu trạng thái pin và công suất sạc theo thời gian thực
 */
data class BatteryTelemetry(
    val currentMa: Float = 0f,       // Dòng điện (mA)
    val currentAmp: Float = 0f,      // Dòng điện (A)
    val voltageVolt: Float = 0f,     // Điện áp (V)
    val powerWatt: Float = 0f,       // Công suất P = U * I (W)
    val temperatureC: Float = 0f,    // Nhiệt độ (°C)
    val levelPercent: Int = 0,       // Phần trăm pin (%)
    val isCharging: Boolean = false, // Trạng thái đang sạc
    val plugType: String = "Chưa cắm sạc",
    val healthStatus: String = "Tốt"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E676),
                    secondary = Color(0xFF29B6F6),
                    background = Color(0xFF0F172A),
                    surface = Color(0xFF1E293B)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BatteryPowerMonitorScreen()
                }
            }
        }
    }
}

@Composable
fun BatteryPowerMonitorScreen() {
    val context = LocalContext.current
    val batteryManager = remember {
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }

    var telemetry by remember { mutableStateOf(BatteryTelemetry()) }
    val powerHistory = remember { mutableStateListOf<Float>() }
    var refreshIntervalMs by remember { mutableLongStateOf(500L) }

    // Lắng nghe BroadcastReceiver cho Điện áp (mV), Nhiệt độ, % Pin và trạng thái cắm sạc
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return

                val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
                // Một số dòng máy trả về V thay vì mV nếu giá trị < 100
                val voltageV = if (voltageMv > 100) voltageMv / 1000f else voltageMv.toFloat()

                val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                val tempC = tempTenths / 10f

                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val percent = if (scale > 0) (level * 100) / scale else 0

                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val plugType = when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "Sạc nhanh AC (Adapter)"
                    BatteryManager.BATTERY_PLUGGED_USB -> "Cổng USB"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Sạc không dây"
                    BatteryManager.BATTERY_PLUGGED_DOCK -> "Đế sạc (Dock)"
                    else -> "Đang dùng pin (Xả)"
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
                    isCharging = isCharging,
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

    // Vòng lặp đọc dòng điện tức thời (BATTERY_PROPERTY_CURRENT_NOW) và tính công suất P = U * I
    LaunchedEffect(refreshIntervalMs) {
        while (true) {
            val rawCurrent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            // Chuẩn hóa đơn vị: Đa số thiết bị trả về microAmpere (µA), một số thiết bị trả về milliAmpere (mA)
            val currentMa = if (abs(rawCurrent) > 50_000) {
                rawCurrent / 1000f
            } else {
                rawCurrent.toFloat()
            }

            val currentAmp = abs(currentMa) / 1000f
            val powerWatt = telemetry.voltageVolt * currentAmp

            telemetry = telemetry.copy(
                currentMa = currentMa,
                currentAmp = currentAmp,
                powerWatt = powerWatt
            )

            if (powerHistory.size >= 40) {
                powerHistory.removeAt(0)
            }
            powerHistory.add(powerWatt)

            delay(refreshIntervalMs)
        }
    }

    // Giao diện hiển thị
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Đo Công Suất Sạc Thời Gian Thực",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )

        // Thẻ hiển thị Công suất chính (Watt)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val statusColor = if (telemetry.isCharging) Color(0xFF00E676) else Color(0xFFFFB74D)
                Text(
                    text = if (telemetry.isCharging) "⚡ ĐANG SẠC (${telemetry.plugType})" else "🔋 ĐANG XẢ PIN",
                    color = statusColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                val animatedPower by animateFloatAsState(targetValue = telemetry.powerWatt, label = "power")
                Text(
                    text = String.format("%.2f W", animatedPower),
                    color = Color.White,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "Công suất thực tế (P = U × I)",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp
                )
            }
        }

        // Lưới thông số Điện áp (Volt) & Cường độ dòng điện (Ampe / mA)
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

        // Lưới thông số phụ: Mức pin, Nhiệt độ, Sức khỏe pin
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                label = "Tình trạng",
                value = telemetry.healthStatus
            )
        }

        // Biểu đồ công suất theo thời gian thực
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Biểu đồ Công suất (W)",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Chu kỳ: ${refreshIntervalMs}ms",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                PowerChart(
                    dataPoints = powerHistory,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Tuỳ chỉnh tần suất lấy mẫu
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Tốc độ cập nhật:", color = Color(0xFF94A3B8), fontSize = 13.sp)
            listOf(250L to "0.25s", 500L to "0.5s", 1000L to "1.0s").forEach { (ms, label) ->
                FilterChip(
                    selected = refreshIntervalMs == ms,
                    onClick = { refreshIntervalMs = ms },
                    label = { Text(label) }
                )
            }
        }
    }
}

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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, color = Color(0xFF94A3B8), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = mainValue,
                color = accentColor,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Text(text = subValue, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, color = Color(0xFF94A3B8), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
        val stepX = size.width / (40 - 1).coerceAtLeast(1)

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

        // Vẽ đường lưới ngang tham chiếu
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