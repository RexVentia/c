package com.robot.simulator.ui.screens

import android.os.Bundle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.robot.simulator.core.MotionSensorSample
import com.robot.simulator.core.RouteDefinition
import com.robot.simulator.core.RoutePointCodec
import com.robot.simulator.core.SimulationRules
import com.robot.simulator.core.SimulationConfig
import com.robot.simulator.core.DeterministicSimulationEngine
import com.robot.simulator.core.SimulationSnapshot
import com.robot.simulator.core.SimulationStatus
import com.robot.simulator.data.local.RouteEntity
import com.robot.simulator.data.local.SimulationSessionEntity
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SimulationSetupScreen(
    routes: List<RouteEntity>,
    contentPadding: PaddingValues,
    onStart: (RouteEntity, Int, Double, Double, Double, Long) -> Unit,
) {
    var selectedRouteId by rememberSaveable { mutableStateOf<String?>(null) }
    var totalStepsText by rememberSaveable { mutableStateOf("") }
    var cadenceText by rememberSaveable { mutableStateOf("140") }
    var variationText by rememberSaveable { mutableStateOf("0") }
    var periodText by rememberSaveable { mutableStateOf("10") }
    var seedText by rememberSaveable { mutableStateOf("1") }
    var presetMessage by remember { mutableStateOf("") }
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("simulation_preset", 0) }
    LaunchedEffect(routes) {
        if (routes.none { it.id == selectedRouteId }) selectedRouteId = routes.firstOrNull()?.id
    }
    val selectedRoute = routes.firstOrNull { it.id == selectedRouteId }
    val totalSteps = totalStepsText.toIntOrNull()
    val cadence = cadenceText.toDoubleOrNull()
    val routeDefinition = remember(selectedRoute) {
        selectedRoute?.let { RouteDefinition(it.id, it.name, RoutePointCodec.decode(it.controlPointsJson), it.lengthM) }
    }
    val validation = SimulationRules.validate(routeDefinition, totalSteps, cadence)
    val variation = variationText.toDoubleOrNull()
    val period = periodText.toDoubleOrNull()
    val seed = seedText.toLongOrNull()
    val variationLimit = cadence?.let { minOf(it - 120.0, 151.0 - it).coerceAtLeast(0.0) } ?: 0.0
    val motionValid = variation != null && variation in 0.0..variationLimit &&
        period != null && period in 2.0..120.0 && seed != null
    val preview = remember(routeDefinition, totalSteps, cadence, variation, period, seed) {
        if (validation.valid && motionValid) {
            val config = SimulationConfig(routeDefinition!!, totalSteps!!, cadence!!, seed!!, variation!!, period!!)
            val engine = DeterministicSimulationEngine()
            List(161) { engine.cadenceAt(config, config.durationMs * it / 160) }
        } else emptyList()
    }
    val cadenceValid = cadence != null && cadence in SimulationRules.MIN_CADENCE_SPM..SimulationRules.MAX_CADENCE_SPM
    val suggestedSteps = if (selectedRoute != null && cadenceValid)
        SimulationRules.suggestedSteps(selectedRoute.lengthM, cadence!!) else null
    val durationMs = if (totalSteps != null && totalSteps > 0 && cadenceValid)
        totalSteps * 60_000.0 / cadence!! else null

    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("选择路线", style = MaterialTheme.typography.titleLarge)
        if (routes.isEmpty()) Text("请先在路线页创建并保存路线。")
        routes.forEach { route ->
            Card(onClick = { selectedRouteId = route.id }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = route.id == selectedRouteId, onClick = { selectedRouteId = route.id })
                    Column {
                        Text(route.name, style = MaterialTheme.typography.titleMedium)
                        Text("长度 %.1f 米".format(route.lengthM))
                    }
                }
            }
        }
        OutlinedTextField(
            value = totalStepsText,
            onValueChange = { totalStepsText = it.filter(Char::isDigit) },
            label = { Text("总步数") },
            singleLine = true,
            isError = totalStepsText.isNotEmpty() && (totalSteps == null || totalSteps <= 0),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = cadenceText,
            onValueChange = { cadenceText = it.filter { char -> char.isDigit() || char == '.' } },
            label = { Text("平均步频（120–151 步/分钟）") },
            singleLine = true,
            isError = cadenceText.isNotEmpty() && !cadenceValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("可复现步频扰动", style = MaterialTheme.typography.titleMedium)
        Text("合成测试数据；幅度为 0 时关闭扰动。周期会对齐到全程整数周期，保持总步数和时长。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = variationText, onValueChange = { variationText = it },
            label = { Text("扰动幅度 ±步/分钟（最大 %.1f）".format(variationLimit)) },
            singleLine = true, isError = variation == null || variation !in 0.0..variationLimit,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = periodText, onValueChange = { periodText = it },
            label = { Text("期望周期（2–120 秒）") }, singleLine = true,
            isError = period == null || period !in 2.0..120.0,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = seedText, onValueChange = { seedText = it },
            label = { Text("随机种子（整数，相同参数可复现）") }, singleLine = true,
            isError = seed == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                cadenceText = "140"; variationText = "0"; periodText = "10"; seedText = "1"
                totalStepsText = ""; presetMessage = "已恢复默认参数"
            }) { Text("重置") }
            OutlinedButton(enabled = validation.valid && motionValid, onClick = {
                preferences.edit().putString("steps", totalStepsText).putString("cadence", cadenceText)
                    .putString("variation", variationText).putString("period", periodText)
                    .putString("seed", seedText).apply()
                presetMessage = "已保存参数预设（不含路线）"
            }) { Text("保存预设") }
            OutlinedButton(onClick = {
                if (preferences.contains("steps")) {
                    totalStepsText = preferences.getString("steps", "")!!
                    cadenceText = preferences.getString("cadence", "140")!!
                    variationText = preferences.getString("variation", "0")!!
                    periodText = preferences.getString("period", "10")!!
                    seedText = preferences.getString("seed", "1")!!
                    presetMessage = "已载入，请核对当前路线的速度"
                } else presetMessage = "尚未保存预设"
            }) { Text("载入预设") }
        }
        if (presetMessage.isNotEmpty()) Text(presetMessage)
        suggestedSteps?.let { range ->
            Text("建议总步数：${range.minimum}–${range.maximum} 步")
            OutlinedButton(onClick = {
                totalStepsText = (range.minimum + (range.maximum - range.minimum) / 2).toString()
            }) { Text("填入建议步数") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("模拟预览", style = MaterialTheme.typography.titleMedium)
                Text("允许平均速度：2–5 m/s（7.2–18 km/h）")
                validation.speedMps?.let { Text("平均速度：%.2f m/s · %.1f km/h".format(it, it * 3.6)) }
                durationMs?.let { Text("预计时长：${formatDuration(it.toLong())}") }
                if (preview.isNotEmpty()) {
                    Text("步频范围：%.1f–%.1f 步/分钟".format(cadence!! - variation!!, cadence + variation))
                    val lineColor = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxWidth().height(100.dp)) {
                        val path = Path()
                        preview.forEachIndexed { index, value ->
                            val x = size.width * index / preview.lastIndex
                            val y = size.height * (1 - (value - 120) / 31).toFloat()
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
                    }
                    Text("全程步频预览 · 纵轴 120–151", style = MaterialTheme.typography.bodySmall)
                }
                Text("保留速度起伏，瞬时速度可能短暂超出平均速度范围。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (totalStepsText.isNotEmpty() || !cadenceValid) {
            validation.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Text("传感器：程序化运动模型 v1，50 Hz（加速度、陀螺仪、重力与姿态）")
        Button(
            onClick = { onStart(selectedRoute!!, totalSteps!!, cadence!!, variation!!, period!!, seed!!) },
            enabled = validation.valid && motionValid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("开始模拟") }
    }
}

@Composable
fun SimulationRunningScreen(
    snapshot: SimulationSnapshot?,
    sensorHistory: List<MotionSensorSample>,
    route: RouteDefinition?,
    contentPadding: PaddingValues,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    if (snapshot == null || route == null) {
        PlaceholderScreen("尚未开始模拟", "请先在参数页选择路线并填写参数。", contentPadding)
        return
    }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(12.dp),
    ) {
        val details: @Composable (Modifier) -> Unit = { detailsModifier ->
        Column(
            modifier = detailsModifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(statusLabel(snapshot.status), style = MaterialTheme.typography.titleLarge)
            LinearProgressIndicator(progress = { snapshot.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text("进度 %.1f%% · %.1f / %.1f 米".format(snapshot.progress * 100, snapshot.distanceM, route.lengthM))
            Text("步数 ${snapshot.stepCount} / ${snapshot.totalSteps} · 步频 %.1f".format(snapshot.cadenceSpm))
            Text("瞬时速度 %.2f m/s · 剩余 ${formatDuration(snapshot.remainingMs)}".format(snapshot.speedMps))
            Text("位置 %.6f, %.6f · 方向 %.1f°".format(snapshot.position.latitude, snapshot.position.longitude, snapshot.headingDeg))
            Text("加速度 m/s²：X %.2f  Y %.2f  Z %.2f".format(
                snapshot.sensor.accelerometerX, snapshot.sensor.accelerometerY, snapshot.sensor.accelerometerZ,
            ))
            Text("陀螺仪 rad/s：X %.3f  Y %.3f  Z %.3f".format(
                snapshot.sensor.gyroscopeX, snapshot.sensor.gyroscopeY, snapshot.sensor.gyroscopeZ,
            ))
            Text("姿态：横滚 %.1f°  俯仰 %.1f°  航向 %.1f°".format(
                snapshot.sensor.rollDeg, snapshot.sensor.pitchDeg, snapshot.sensor.yawDeg,
            ))
            SensorChart(sensorHistory, Modifier.fillMaxWidth().height(180.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (snapshot.status == SimulationStatus.Running) {
                    Button(onClick = onPause, modifier = Modifier.weight(1f)) { Text("暂停") }
                } else if (snapshot.status == SimulationStatus.Paused) {
                    Button(onClick = onResume, modifier = Modifier.weight(1f)) { Text("继续") }
                }
                OutlinedButton(
                    onClick = onStop,
                    enabled = snapshot.status in listOf(SimulationStatus.Running, SimulationStatus.Paused),
                    modifier = Modifier.weight(1f),
                ) { Text("提前停止") }
            }
        }
        }
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SimulationMap(route, snapshot, Modifier.weight(1f).fillMaxHeight())
                details(Modifier.width(360.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SimulationMap(route, snapshot, Modifier.fillMaxWidth().weight(1f))
                details(Modifier.fillMaxWidth().weight(1.2f))
            }
        }
    }
}

@Composable
private fun SensorChart(samples: List<MotionSensorSample>, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Box(Modifier.fillMaxSize().padding(10.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                if (samples.size < 2) return@Canvas
                fun path(values: List<Double>, scale: Double): Path = Path().apply {
                    values.forEachIndexed { index, value ->
                        val x = size.width * index / (values.lastIndex.coerceAtLeast(1))
                        val y = size.height / 2f - (value / scale * size.height / 2f).toFloat()
                        if (index == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                drawLine(Color.Gray, start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f), end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f))
                drawPath(path(samples.map { it.accelerometerZ - 9.80665 }, 2.5), Color(0xFF00A67D), style = Stroke(3f))
                drawPath(path(samples.map { it.gyroscopeY }, 0.5), Color(0xFFFF7043), style = Stroke(2f))
            }
            Text("绿色：垂直加速度　橙色：Y 轴角速度", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SimulationMap(route: RouteDefinition, snapshot: SimulationSnapshot, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember(context) { MapView(context) }
    var map by remember { mutableStateOf<AMap?>(null) }
    var marker by remember { mutableStateOf<Marker?>(null) }
    DisposableEffect(mapView, lifecycle, route.id) {
        mapView.onCreate(Bundle())
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        map = mapView.map.apply {
            addPolyline(PolylineOptions().addAll(route.controlPoints.map { LatLng(it.latitude, it.longitude) }).width(9f).color(0xFF007F5F.toInt()))
            val bounds = LatLngBounds.builder().also { builder ->
                route.controlPoints.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
            }.build()
            moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
            marker = addMarker(
                MarkerOptions().position(LatLng(snapshot.position.latitude, snapshot.position.longitude))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)),
            )
        }
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    LaunchedEffect(snapshot.position, snapshot.headingDeg) {
        marker?.position = LatLng(snapshot.position.latitude, snapshot.position.longitude)
        marker?.rotateAngle = -snapshot.headingDeg.toFloat()
    }
    AndroidView(factory = { mapView }, modifier = modifier)
}

@Composable
fun SimulationHistoryScreen(sessions: List<SimulationSessionEntity>, contentPadding: PaddingValues) {
    if (sessions.isEmpty()) {
        PlaceholderScreen("暂无运行记录", "完成或提前停止的模拟会保存在这里。", contentPadding)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(sessions, key = SimulationSessionEntity::id) { session ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(session.routeName, style = MaterialTheme.typography.titleMedium)
                    Text("${if (session.status == SimulationStatus.Completed.name) "已完成" else "已中止"} · ${DateFormat.getDateTimeInstance().format(Date(session.updatedAtEpochMs))}")
                    Text("步数 ${session.finalStepCount}/${session.totalSteps} · 步频 %.1f · 用时 %s".format(
                        session.cadenceSpm, formatDuration(session.actualDurationMs),
                    ))
                    Text("终点 %.6f, %.6f · ${session.sensorProfile}".format(session.finalLatitude, session.finalLongitude))
                }
            }
        }
    }
}

private fun statusLabel(status: SimulationStatus): String = when (status) {
    SimulationStatus.Ready -> "准备"
    SimulationStatus.Running -> "模拟运行中"
    SimulationStatus.Paused -> "已暂停"
    SimulationStatus.Stopped -> "已提前停止"
    SimulationStatus.Completed -> "已到达终点"
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}
