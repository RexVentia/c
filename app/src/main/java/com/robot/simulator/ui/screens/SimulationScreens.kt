package com.robot.simulator.ui.screens

import android.os.Bundle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
    onStart: (RouteEntity, Int, Double) -> Unit,
) {
    var selectedRouteId by rememberSaveable { mutableStateOf<String?>(null) }
    var totalStepsText by rememberSaveable { mutableStateOf("") }
    var cadenceText by rememberSaveable { mutableStateOf("140") }
    LaunchedEffect(routes) {
        if (routes.none { it.id == selectedRouteId }) selectedRouteId = routes.firstOrNull()?.id
    }
    val selectedRoute = routes.firstOrNull { it.id == selectedRouteId }
    val totalSteps = totalStepsText.toIntOrNull()
    val cadence = cadenceText.toDoubleOrNull()
    val routeDefinition = selectedRoute?.let {
        RouteDefinition(it.id, it.name, RoutePointCodec.decode(it.controlPointsJson), it.lengthM)
    }
    val validation = SimulationRules.validate(routeDefinition, totalSteps, cadence)
    val durationMs = if (validation.valid) totalSteps!! * 60_000.0 / cadence!! else null

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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = cadenceText,
            onValueChange = { cadenceText = it.filter { char -> char.isDigit() || char == '.' } },
            label = { Text("步频（120–151 步/分钟）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        validation.speedMps?.let { Text("平均速度：%.2f m/s".format(it)) }
        durationMs?.let { Text("预计时长：${formatDuration(it.toLong())}") }
        validation.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("传感器：程序化运动模型 v1，50 Hz（加速度、陀螺仪、重力与姿态）")
        Button(
            onClick = { onStart(selectedRoute!!, totalSteps!!, cadence!!) },
            enabled = validation.valid,
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
    Row(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SimulationMap(route, snapshot, Modifier.weight(1.2f).fillMaxHeight())
        Column(
            modifier = Modifier.width(520.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
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
