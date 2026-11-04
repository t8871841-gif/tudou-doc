package com.iris.localai.ui.home

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.iris.localai.ui.components.FloatingIconButton
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Orilo 式主页（第 1 层：UI 壳）：标题 + 一颗水滴 + 日期，下边两个圆按钮进聊天。
 * 水滴是整颗动态本身——接陀螺仪（加速度计），倾斜手机水滴跟着滑，像真水珠一样。
 * 晨报内容（第 2 层：新闻源 + Agnes 现写摘要）还没做，这里的日期行下面只放一句提示，
 * 不编假新闻。
 *
 * 导航：点水滴 / 左下对话气泡 / 右下麦克风都进聊天；左上菜单进设置，右上灯泡进模型列表。
 */
@Composable
fun HomeScreen(
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val c = Iris.colors
    val tilt = rememberTilt()
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val title = when (hour) {
        in 5..11 -> "Morning Coffee"
        in 12..17 -> "Afternoon Tea"
        else -> "Good Evening"
    }
    val dateText = SimpleDateFormat("MMM d", Locale.ENGLISH).format(Date())
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(c.surface, c.background)
                )
            )
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：菜单 | 标题 | 灯泡
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingIconButton(
                    Icons.Outlined.Menu, "设置",
                    size = IrisDimens.topBarControl,
                ) { onOpenSettings() }
                Spacer(Modifier.weight(1f))
                Text(title, style = IrisType.title, color = c.text, textAlign = TextAlign.Center)
                Spacer(Modifier.weight(1f))
                FloatingIconButton(
                    Icons.Outlined.Lightbulb, "模型",
                    size = IrisDimens.topBarControl,
                ) { onOpenModels() }
            }
            Spacer(Modifier.height(IrisDimens.space32))
            // 水滴：点一下进聊天
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Droplet(
                    tilt = tilt.value,
                    modifier = Modifier
                        .size(280.dp)
                        .clickable { onOpenChat() },
                )
            }
            // 日期行 + 提示（不编假新闻）
            Text(
                "$dateText · hero",
                style = IrisType.title,
                color = c.text,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(IrisDimens.space8))
            Text(
                "Tap the droplet to start chatting",
                style = IrisType.secondary,
                color = c.textSecondary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(IrisDimens.space32))
            // 底部：对话气泡（左）| 麦克风（右，大一号，主操作）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = IrisDimens.space32, vertical = IrisDimens.space24),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingIconButton(
                    Icons.Outlined.ChatBubbleOutline, "聊天",
                    size = 56.dp,
                ) { onOpenChat() }
                Spacer(Modifier.weight(1f))
                FloatingIconButton(
                    Icons.Outlined.Mic, "语音",
                    size = 72.dp,
                ) { onOpenChat() }
            }
        }
    }
}

/**
 * 水滴：Canvas 画的水滴形（尖顶圆底）+ 径向渐变 + 两处高光 + 底部软阴影。
 * tilt 是归一化倾斜（-1..1），水滴按它平移，阴影不动——倾斜时水滴在影子上方滑动。
 */
@Composable
private fun Droplet(tilt: Offset, modifier: Modifier = Modifier) {
    val maxOffsetPx = with(LocalDensity.current) { 20.dp.toPx() }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // 底部软阴影（不动）
        drawOval(
            color = Color.Black.copy(alpha = 0.08f),
            topLeft = Offset(w * 0.20f, h * 0.90f),
            size = Size(w * 0.60f, h * 0.07f),
        )
        translate(tilt.x * maxOffsetPx, tilt.y * maxOffsetPx) {
            // 水滴形：尖顶圆底
            val path = Path().apply {
                moveTo(0.50f * w, 0.04f * h)
                cubicTo(0.58f * w, 0.22f * h, 0.84f * w, 0.38f * h, 0.84f * w, 0.64f * h)
                cubicTo(0.84f * w, 0.84f * h, 0.68f * w, 0.96f * h, 0.50f * w, 0.96f * h)
                cubicTo(0.32f * w, 0.96f * h, 0.16f * w, 0.84f * h, 0.16f * w, 0.64f * h)
                cubicTo(0.16f * w, 0.38f * h, 0.42f * w, 0.22f * h, 0.50f * w, 0.04f * h)
                close()
            }
            drawPath(
                path,
                brush = Brush.radialGradient(
                    0.0f to Color(0xFFFFFFFF),
                    0.55f to Color(0xFFE4ECF8),
                    1.0f to Color(0xFFB7C9E6),
                    center = Offset(w * 0.42f, h * 0.32f),
                    radius = w * 0.55f,
                ),
            )
            // 主高光：左上斜椭圆
            rotate(-25f, pivot = Offset(w * 0.36f, h * 0.28f)) {
                drawOval(
                    color = Color.White.copy(alpha = 0.85f),
                    topLeft = Offset(w * 0.30f, h * 0.16f),
                    size = Size(w * 0.12f, h * 0.24f),
                )
            }
            // 次高光：右下小点
            drawOval(
                color = Color.White.copy(alpha = 0.50f),
                topLeft = Offset(w * 0.60f, h * 0.68f),
                size = Size(w * 0.07f, h * 0.10f),
            )
        }
    }
}

/**
 * 倾斜：加速度计读重力向量，低通滤波平滑，归一化到 -1..1。
 * 没有加速度计的设备返回全零，水滴不动，不崩。
 */
@Composable
private fun rememberTilt(): State<Offset> {
    val context = LocalContext.current
    val tilt = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var sx = 0f
        var sy = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val alpha = 0.15f
                sx += alpha * (event.values[0] - sx)
                sy += alpha * (event.values[1] - sy)
                tilt.value = Offset(
                    (-sx / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f),
                    (sy / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sensorManager.unregisterListener(listener) }
    }
    return tilt
}
