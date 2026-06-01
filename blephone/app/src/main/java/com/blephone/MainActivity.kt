package com.blephone

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.random.Random

// ===== Theme (light) =====
private val Bg = Color(0xFFF6F8FB)
private val BgGrad1 = Color(0xFFFAFBFD)
private val BgGrad2 = Color(0xFFEEF1F6)
private val CardBg = Color(0xFFFFFFFF)
private val CardBorder = Color(0xFFE2E8F0)
private val Accent = Color(0xFF0EA5E9)        // sky blue accent
private val AccentDim = Color(0xFFBAE6FD)
private val TextPrimary = Color(0xFF0F172A)
private val TextSecondary = Color(0xFF475569)
private val TextDim = Color(0xFF94A3B8)
private val ChipBg = Color(0xFFF1F5F9)
private val OutlineColor = Color(0xFF334155)   // foot outline stroke color
private val FootInterior = Color(0xFFF8FAFC)   // foot "skin" fill color (very light)
private val GridColor = Color(0x10000000)
private val DangerColor = Color(0xFFEF4444)
private val OkColor = Color(0xFF10B981)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Accent,
                    onPrimary = Color.White,
                    background = Bg,
                    surface = CardBg,
                    onSurface = TextPrimary
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Bg
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(listOf(BgGrad1, BgGrad2))
                            )
                    ) {
                        val vm: MainViewModel = viewModel()
                        BlePhoneApp(vm)
                    }
                }
            }
        }
    }
}

enum class ConnectionState(val label: String) {
    DISCONNECTED("未连接"),
    SCANNING("扫描中"),
    CONNECTING("连接中"),
    CONNECTED("已连接")
}

enum class MockMode(val label: String) {
    STAND("静态站立"),
    WALK("行走循环"),
    FOREFOOT("前掌重心"),
    HEEL("后跟重心"),
    SINGLE("单点压测")
}

data class PressurePoint(
    val id: Int,
    val x: Float, // 0..1 within foot bbox
    val y: Float,
    val force: Int = 0
)

class MainViewModel : ViewModel() {
    var connectionState by mutableStateOf(ConnectionState.DISCONNECTED)
    var mode by mutableStateOf(MockMode.STAND)
    var selectedPointId by mutableStateOf(1)
    var points by mutableStateOf(defaultPoints())
    var bleMessage by mutableStateOf("目标设备 DDXD_BLUE · FFF1 Notify")
    var scannedDevices by mutableStateOf(emptyList<BleScanDevice>())
    var bleLogs by mutableStateOf(listOf("等待连接"))

    private var bleClient: PressureBleClient? = null
    private var step = 0

    fun toggleBleConnection(context: Context) {
        if (connectionState == ConnectionState.CONNECTED ||
            connectionState == ConnectionState.SCANNING ||
            connectionState == ConnectionState.CONNECTING
        ) {
            disconnectBle()
            return
        }
        connectBle(context)
    }

    fun connectBle(context: Context) {
        val appContext = context.applicationContext
        if (!BleRuntimePermissions.hasRequiredPermissions(appContext)) {
            bleMessage = "需要蓝牙权限"
            connectionState = ConnectionState.DISCONNECTED
            return
        }

        bleClient?.disconnect()
        bleClient = PressureBleClient(
            context = appContext,
            onState = { state -> connectionState = state },
            onFrame = ::applySensorValues,
            onDevice = ::upsertScannedDevice,
            onMessage = { message ->
                bleMessage = message
                addBleLog(message)
            }
        ).also { client ->
            scannedDevices = emptyList()
            addBleLog("开始扫描 DDXD_BLUE")
            client.connect()
        }
    }

    fun disconnectBle() {
        bleClient?.disconnect()
        bleClient = null
        connectionState = ConnectionState.DISCONNECTED
        bleMessage = "已断开"
        addBleLog("手动断开")
    }

    fun onPermissionDenied() {
        bleMessage = "蓝牙权限被拒绝"
        connectionState = ConnectionState.DISCONNECTED
        addBleLog("蓝牙权限被拒绝")
    }

    fun connectScannedDevice(address: String) {
        val client = bleClient
        if (client == null) {
            bleMessage = "请先点击连接开始扫描"
            addBleLog("没有正在运行的扫描")
            return
        }
        addBleLog("手动连接 $address")
        client.connectTo(address)
    }

    fun updateMode(newMode: MockMode) {
        mode = newMode
        step = 0
    }

    fun setSelectedPoint(id: Int) {
        selectedPointId = id
    }

    fun tick() {
        connectionState = when (connectionState) {
            ConnectionState.SCANNING -> ConnectionState.CONNECTING
            ConnectionState.CONNECTING -> ConnectionState.CONNECTED
            else -> connectionState
        }
        if (connectionState != ConnectionState.CONNECTED) return

        val generated = when (mode) {
            MockMode.STAND -> standPattern()
            MockMode.WALK -> walkPattern()
            MockMode.FOREFOOT -> forefootPattern()
            MockMode.HEEL -> heelPattern()
            MockMode.SINGLE -> singlePattern(selectedPointId)
        }
        points = points.mapIndexed { index, point ->
            val incoming = generated[index]
            val smooth = (point.force * 0.7f + incoming * 0.3f).toInt()
            point.copy(force = smooth.coerceIn(0, 100))
        }
        step++
    }

    fun summary(): Triple<Int, Int, Int> {
        val max = points.maxByOrNull { it.force }?.force ?: 0
        val avg = points.map { it.force }.average().toInt()
        val contacts = points.count { it.force >= 8 }
        return Triple(max, avg, contacts)
    }

    // Patterns are indexed by id 1..16, top of the image -> bottom.
    // Indices 1-6   = heel (wide rounded top)
    // Indices 7-8   = heel/arch transition
    // Indices 9-10  = arch / waist (narrowest)
    // Indices 11-12 = ball-of-foot start (lower bulge)
    // Indices 13-14 = forefoot / metatarsal
    // Indices 15-16 = toe tip
    private fun standPattern(): List<Int> = listOf(
        70, 78, 72,             // heel row 1
        65, 75, 68,              // heel row 2
        45, 48,                  // heel/arch transition
        15, 18,                  // arch (low)
        38, 40,                  // ball start
        58, 62,                  // forefoot
        45, 50                   // toes
    ).withNoise(5)

    private fun forefootPattern(): List<Int> = listOf(
        6, 4, 6,
        10, 8, 10,
        18, 20,
        25, 28,
        60, 62,
        82, 88,
        72, 80
    ).withNoise(6)

    private fun heelPattern(): List<Int> = listOf(
        82, 90, 85,
        78, 88, 80,
        50, 52,
        25, 22,
        18, 18,
        12, 14,
        8, 10
    ).withNoise(6)

    private fun singlePattern(id: Int): List<Int> = List(16) { idx ->
        if (idx == id - 1) 92 else Random.nextInt(0, 12)
    }

    private fun walkPattern(): List<Int> {
        val phase = step % 24
        return when (phase) {
            in 0..5 -> listOf(   // heel strike: heel is at top
                88, 92, 88,
                75, 80, 75,
                18, 20,
                4, 4,
                0, 0,
                0, 0,
                0, 0
            )
            in 6..11 -> listOf(  // foot flat: full contact
                70, 72, 70,
                58, 60, 58,
                40, 42,
                25, 28,
                40, 42,
                50, 52,
                40, 45
            )
            in 12..17 -> listOf( // toe off: forefoot/toes are at bottom
                4, 0, 4,
                10, 8, 10,
                15, 15,
                25, 25,
                60, 62,
                85, 92,
                78, 88
            )
            else -> listOf(      // swing: foot off ground
                0, 0, 0,
                0, 0, 0,
                0, 0, 0, 0,
                4, 4,
                4, 4, 0, 0
            )
        }.withNoise(5)
    }

    private fun List<Int>.withNoise(noise: Int): List<Int> =
        map { (it + Random.nextInt(-noise, noise + 1)).coerceIn(0, 100) }

    private fun applySensorValues(values: List<Int>) {
        if (values.size != points.size) return
        points = points.mapIndexed { index, point ->
            val normalized = ((values[index].coerceIn(0, 255) / 255f) * 100f).roundToInt()
            point.copy(force = normalized.coerceIn(0, 100))
        }
    }

    private fun upsertScannedDevice(device: BleScanDevice) {
        val updated = scannedDevices
            .filterNot { it.address == device.address }
            .plus(device)
            .sortedWith(compareByDescending<BleScanDevice> { it.isTarget }.thenByDescending { it.rssi })
            .take(8)
        scannedDevices = updated
        if (device.isTarget) {
            addBleLog("发现目标 ${device.name} ${device.address}")
        }
    }

    private fun addBleLog(message: String) {
        bleLogs = (listOf(message) + bleLogs).take(6)
    }

    override fun onCleared() {
        bleClient?.disconnect()
        super.onCleared()
    }
}

@Composable
fun BlePhoneApp(vm: MainViewModel) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = BleRuntimePermissions.requiredPermissions().all { permission ->
            grants[permission] == true
        }
        if (granted) {
            vm.connectBle(context)
        } else {
            vm.onPermissionDenied()
        }
    }

    fun handleConnectClick() {
        if (vm.connectionState == ConnectionState.CONNECTED ||
            vm.connectionState == ConnectionState.SCANNING ||
            vm.connectionState == ConnectionState.CONNECTING
        ) {
            vm.disconnectBle()
            return
        }
        if (BleRuntimePermissions.hasRequiredPermissions(context)) {
            vm.connectBle(context)
        } else {
            permissionLauncher.launch(BleRuntimePermissions.requiredPermissions())
        }
    }

    val (maxValue, avgValue, contacts) = vm.summary()
    val selected = vm.points.first { it.id == vm.selectedPointId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HeaderBar()
        StatusCard(vm.connectionState, ::handleConnectClick)
        DeviceCard(
            message = vm.bleMessage,
            devices = vm.scannedDevices,
            logs = vm.bleLogs,
            onDeviceClick = vm::connectScannedDevice
        )
        FootCard(
            points = vm.points,
            selectedId = vm.selectedPointId,
            onPointClick = vm::setSelectedPoint
        )
        SummaryCard(maxValue, avgValue, contacts, selected)
    }
}

@Composable
private fun HeaderBar() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Accent, CircleShape)
            )
            Text(
                text = "  BLEPHONE",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp
            )
        }
        Text(
            text = "FOOT PRESSURE · 16CH",
            color = TextDim,
            fontSize = 10.sp,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, CardBorder),
        shape = RoundedCornerShape(14.dp)
    ) { content() }
}

@Composable
fun StatusCard(state: ConnectionState, onConnectClick: () -> Unit) {
    val dotColor = when (state) {
        ConnectionState.DISCONNECTED -> DangerColor
        ConnectionState.SCANNING, ConnectionState.CONNECTING -> Color(0xFFF59E0B)
        ConnectionState.CONNECTED -> OkColor
    }
    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).background(dotColor, CircleShape))
                Text(
                    text = "  蓝牙状态  ",
                    color = TextDim,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp
                )
                Text(
                    text = state.label,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Button(
                onClick = onConnectClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state == ConnectionState.CONNECTED) ChipBg else Accent,
                    contentColor = if (state == ConnectionState.CONNECTED) TextPrimary else Color.White
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = if (state == ConnectionState.CONNECTED) "断开" else "连接",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun DeviceCard(
    message: String,
    devices: List<BleScanDevice>,
    logs: List<String>,
    onDeviceClick: (String) -> Unit
) {
    PanelCard {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "BLE 设备",
                color = TextDim,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = message,
                color = TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProtocolChip("DDXD_BLUE")
                ProtocolChip("FFF0")
                ProtocolChip("FFF1 Notify")
            }
            Text(
                "扫描设备",
                color = TextDim,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Medium
            )
            if (devices.isEmpty()) {
                Text(
                    text = "点击连接后显示附近 BLE 设备",
                    color = TextDim,
                    fontSize = 12.sp
                )
            } else {
                devices.take(4).forEach { device ->
                    ScanDeviceRow(device, onDeviceClick)
                }
            }
            Text(
                "连接日志",
                color = TextDim,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Medium
            )
            logs.take(4).forEach { log ->
                Text(
                    text = log,
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun ScanDeviceRow(device: BleScanDevice, onClick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (device.isTarget) AccentDim else ChipBg)
            .clickable { onClick(device.address) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = device.address,
                color = TextSecondary,
                fontSize = 10.sp
            )
        }
        Text(
            text = "${device.rssi} dBm",
            color = if (device.isTarget) Accent else TextDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.widthIn(min = 52.dp)
        )
    }
}

@Composable
private fun ProtocolChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ChipBg)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun ModeCard(current: MockMode, onChange: (MockMode) -> Unit) {
    PanelCard {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "MOCK 场景",
                color = TextDim,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Medium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MockMode.entries.forEach { mode ->
                    val selected = current == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) Accent else ChipBg)
                            .clickable { onChange(mode) }
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = mode.label,
                            color = if (selected) Color.White else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FootCard(
    points: List<PressurePoint>,
    selectedId: Int,
    onPointClick: (Int) -> Unit
) {
    val outlineImg = ImageBitmap.imageResource(R.drawable.foot)
    val fillImg = ImageBitmap.imageResource(R.drawable.foot_fill)

    PanelCard {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().height(540.dp).padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            // Fit the foot image preserving aspect ratio, centered in the card.
            val ratio = minOf(wPx / outlineImg.width, hPx / outlineImg.height)
            val footW = outlineImg.width * ratio
            val footH = outlineImg.height * ratio
            val footLeft = (wPx - footW) / 2f
            val footTop = (hPx - footH) / 2f
            val dst = IntOffset(footLeft.roundToInt(), footTop.roundToInt())
            val dstSize = IntSize(footW.roundToInt(), footH.roundToInt())
            val footRect = Rect(footLeft, footTop, footLeft + footW, footTop + footH)
            val footCenter = Offset(footLeft + footW / 2f, footTop + footH / 2f)

            Canvas(modifier = Modifier.fillMaxSize()) {
                // Subtle grid background
                val step = size.width / 14f
                var gx = 0f
                while (gx < size.width) {
                    drawLine(GridColor, Offset(gx, 0f), Offset(gx, size.height), 1f); gx += step
                }
                var gy = 0f
                while (gy < size.height) {
                    drawLine(GridColor, Offset(0f, gy), Offset(size.width, gy), 1f); gy += step
                }

                // 1) Foot interior body (very light skin color)
                rotate(degrees = 180f, pivot = footCenter) {
                    drawImage(
                        image = fillImg,
                        dstOffset = dst,
                        dstSize = dstSize,
                        colorFilter = ColorFilter.tint(FootInterior)
                    )
                }

                // 2) Heatmap on its own layer, clipped to the foot fill via DstIn mask
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(footRect, Paint())

                    points.forEach { pt ->
                        if (pt.force < 4) return@forEach
                        val cx = footLeft + (1f - pt.x) * footW
                        val cy = footTop + (1f - pt.y) * footH
                        val radius = footW * (0.22f + pt.force / 100f * 0.40f)
                        val color = forceToColor(pt.force)
                        val centerAlpha = (0.35f + pt.force / 100f * 0.55f).coerceIn(0f, 0.95f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0.0f to color.copy(alpha = centerAlpha),
                                    0.45f to color.copy(alpha = centerAlpha * 0.45f),
                                    0.8f to color.copy(alpha = centerAlpha * 0.12f),
                                    1.0f to color.copy(alpha = 0f)
                                ),
                                center = Offset(cx, cy),
                                radius = radius
                            ),
                            radius = radius,
                            center = Offset(cx, cy)
                        )
                    }

                    // Mask: keep only what overlaps the foot fill alpha
                    rotate(degrees = 180f, pivot = footCenter) {
                        drawImage(
                            image = fillImg,
                            dstOffset = dst,
                            dstSize = dstSize,
                            blendMode = BlendMode.DstIn
                        )
                    }

                    canvas.restore()
                }

                // 3) Foot outline on top, dark stroke
                rotate(degrees = 180f, pivot = footCenter) {
                    drawImage(
                        image = outlineImg,
                        dstOffset = dst,
                        dstSize = dstSize,
                        colorFilter = ColorFilter.tint(OutlineColor)
                    )
                }
            }

            // Sensor markers (clickable, IDs visible)
            val markerSize = 22.dp
            points.forEach { pt ->
                val selected = pt.id == selectedId
                val markerOuter = if (selected) markerSize + 6.dp else markerSize
                val markerOuterPx = with(density) { markerOuter.toPx() }
                val cxPx = footLeft + (1f - pt.x) * footW
                val cyPx = footTop + (1f - pt.y) * footH
                val offX = with(density) { (cxPx - markerOuterPx / 2f).toDp() }
                val offY = with(density) { (cyPx - markerOuterPx / 2f).toDp() }
                SensorMarker(
                    id = pt.id,
                    selected = selected,
                    size = markerSize,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = offX, y = offY),
                    onClick = { onPointClick(pt.id) }
                )
            }
        }
    }
}

@Composable
private fun SensorMarker(
    id: Int,
    selected: Boolean,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val outer = if (selected) size + 6.dp else size
    Box(
        modifier = modifier
            .size(outer)
            .clip(CircleShape)
            .background(
                color = if (selected) Accent else Color.White,
                shape = CircleShape
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Accent else Color(0x66334155),
                shape = CircleShape
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "$id",
            color = if (selected) Color.White else OutlineColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SummaryCard(maxValue: Int, avgValue: Int, contacts: Int, selected: PressurePoint) {
    PanelCard {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "实时摘要",
                color = TextDim,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Medium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricBox("接触", "$contacts/16", Modifier.weight(1f))
                MetricBox("最大", "$maxValue", Modifier.weight(1f))
                MetricBox("平均", "$avgValue", Modifier.weight(1f))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ChipBg)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(forceToColor(selected.force), CircleShape)
                )
                Text(
                    text = "  P${selected.id}",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "   ${selected.force}",
                    color = Accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "   ${forceLabel(selected.force)}",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun MetricBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ChipBg)
            .padding(vertical = 10.dp, horizontal = 12.dp)
    ) {
        Text(label, color = TextDim, fontSize = 10.sp, letterSpacing = 1.sp)
        Text(
            value,
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

fun forceLabel(force: Int): String = when {
    force < 8 -> "未接触"
    force <= 20 -> "极轻压"
    force <= 40 -> "轻压"
    force <= 60 -> "中压"
    force <= 80 -> "重压"
    else -> "极重压"
}

// Heatmap palette: light → blue → cyan → green → yellow → orange → red.
// Tuned for visibility on a light foot interior.
fun forceToColor(force: Int): Color = when {
    force < 8 -> Color(0xFFE5E7EB)     // no contact (very light gray)
    force <= 20 -> Color(0xFF60A5FA)   // light blue
    force <= 40 -> Color(0xFF22D3EE)   // cyan
    force <= 55 -> Color(0xFF34D399)   // green
    force <= 70 -> Color(0xFFFACC15)   // yellow
    force <= 85 -> Color(0xFFF97316)   // orange
    else -> Color(0xFFEF4444)          // red
}

// 16 sensor points laid out for the user-supplied insole image. The image has a wider
// rounded top (toe/forefoot end), narrowing through the arch, slight bulge, then narrowing
// again to a smaller rounded bottom (heel end). Coords are normalized within the cropped
// image bbox (0..1).
fun defaultPoints(): List<PressurePoint> {
    val coords = listOf(
        // Rows 1-2: wide top (toe/forefoot) — 3 dots each
        0.30f to 0.07f, 0.50f to 0.05f, 0.70f to 0.07f,
        0.27f to 0.18f, 0.50f to 0.18f, 0.73f to 0.18f,
        // Row 3: narrowing (mid-forefoot)
        0.34f to 0.32f, 0.66f to 0.32f,
        // Row 4: arch / waist (narrowest)
        0.40f to 0.45f, 0.60f to 0.45f,
        // Row 5: lower bulge (heel transition)
        0.34f to 0.60f, 0.66f to 0.60f,
        // Row 6: heel
        0.36f to 0.75f, 0.64f to 0.75f,
        // Row 7: heel tip (rounded bottom)
        0.42f to 0.88f, 0.58f to 0.92f
    )
    return coords.mapIndexed { index, pair ->
        PressurePoint(id = index + 1, x = pair.first, y = pair.second)
    }
}
