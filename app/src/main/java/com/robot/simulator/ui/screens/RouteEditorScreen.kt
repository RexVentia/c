package com.robot.simulator.ui.screens

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Point
import android.os.Bundle
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.robot.simulator.core.GeoMath
import com.robot.simulator.core.PointM
import java.util.Locale
import kotlin.math.roundToInt

private val RoutePointsSaver = listSaver<List<PointM>, Double>(
    save = { points ->
        points.flatMap { listOf(it.latitude, it.longitude, it.cumulativeDistanceM) }
    },
    restore = { values ->
        values.chunked(3).map { PointM(it[0], it[1], it[2]) }
    },
)

@Composable
fun RouteEditorScreen(
    contentPadding: PaddingValues,
    onCancel: () -> Unit,
    onSave: (String, List<PointM>) -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("amap-consent", android.content.Context.MODE_PRIVATE)
    }
    var consentGranted by rememberSaveable {
        mutableStateOf(preferences.getBoolean("accepted", false))
    }
    val keyConfigured = runCatching {
        context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        ).metaData?.getString("com.amap.api.v2.apikey").orEmpty().isNotBlank()
    }.getOrDefault(false)

    val privacyInitialized = remember(consentGranted) {
        if (consentGranted) {
            MapsInitializer.updatePrivacyShow(context, true, true)
            MapsInitializer.updatePrivacyAgree(context, true)
        }
        consentGranted
    }

    if (!privacyInitialized) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("启用高德地图") },
            text = { Text("路线编辑器需要加载高德地图瓦片。地图 SDK 可能处理设备和网络信息；同意后才会初始化地图。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        preferences.edit { putBoolean("accepted", true) }
                        consentGranted = true
                    },
                ) { Text("同意并继续") }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text("返回") } },
        )
        return
    }

    if (!keyConfigured) {
        PlaceholderScreen(
            title = "缺少高德地图 Key",
            description = "请在 local.properties 中配置 amap.api.key 后重新构建。",
            contentPadding = contentPadding,
            actionLabel = "返回",
            onAction = onCancel,
        )
        return
    }

    RouteEditor(
        contentPadding = contentPadding,
        onSave = onSave,
    )
}

@Composable
private fun RouteEditor(
    contentPadding: PaddingValues,
    onSave: (String, List<PointM>) -> Unit,
) {
    var routeName by rememberSaveable { mutableStateOf("") }
    var points by rememberSaveable(stateSaver = RoutePointsSaver) { mutableStateOf(emptyList()) }
    var drawing by rememberSaveable { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var map by remember { mutableStateOf<AMap?>(null) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val mapPane: @Composable (Modifier) -> Unit = { modifier ->
        MapDrawingPane(
            modifier = modifier,
            map = map,
            points = points,
            drawing = drawing,
            onMapReady = { map = it },
            onPointsChanged = {
                points = it
                errorMessage = null
            },
            onDrawingFinished = { drawing = false },
        )
    }
    val controls: @Composable (Modifier) -> Unit = { modifier ->
        RouteControls(
            modifier = modifier,
            routeName = routeName,
            points = points,
            drawing = drawing,
            errorMessage = errorMessage,
            onNameChanged = { routeName = it },
            onStartDrawing = {
                points = emptyList()
                drawing = true
                errorMessage = null
            },
            onUndo = {
                points = GeoMath.withCumulativeDistances(points.dropLast(1))
                errorMessage = null
            },
            onClear = {
                points = emptyList()
                drawing = false
                errorMessage = null
            },
            onSave = {
                when {
                    routeName.isBlank() -> errorMessage = "请输入路线名称"
                    points.size < 2 -> errorMessage = "请至少绘制两个有效点"
                    points.last().cumulativeDistanceM <= 5.0 -> errorMessage = "路线长度必须大于 5 米"
                    else -> onSave(routeName.trim(), points)
                }
            },
        )
    }

    if (landscape) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            mapPane(Modifier.weight(1f).fillMaxHeight())
            controls(Modifier.width(360.dp).fillMaxHeight())
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            mapPane(Modifier.weight(1f).fillMaxWidth())
            controls(Modifier.fillMaxWidth().heightIn(max = 420.dp))
        }
    }
}

@Composable
private fun MapDrawingPane(
    modifier: Modifier,
    map: AMap?,
    points: List<PointM>,
    drawing: Boolean,
    onMapReady: (AMap) -> Unit,
    onPointsChanged: (List<PointM>) -> Unit,
    onDrawingFinished: () -> Unit,
) {
    val currentPoints by rememberUpdatedState(points)
    LaunchedEffect(map, drawing) {
        map?.uiSettings?.apply {
            isScrollGesturesEnabled = !drawing
            isZoomGesturesEnabled = !drawing
            isRotateGesturesEnabled = !drawing
            isTiltGesturesEnabled = !drawing
            isZoomControlsEnabled = !drawing
        }
    }
    LaunchedEffect(map, points) {
        val activeMap = map ?: return@LaunchedEffect
        activeMap.clear()
        if (points.size >= 2) {
            activeMap.addPolyline(
                PolylineOptions()
                    .addAll(points.map { LatLng(it.latitude, it.longitude) })
                    .width(10f)
                    .color(0xFF007F5F.toInt()),
            )
        }
        points.firstOrNull()?.let {
            activeMap.addMarker(
                MarkerOptions()
                    .position(LatLng(it.latitude, it.longitude))
                    .title("起点")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)),
            )
        }
        points.lastOrNull()?.takeIf { points.size > 1 }?.let {
            activeMap.addMarker(
                MarkerOptions()
                    .position(LatLng(it.latitude, it.longitude))
                    .title("终点")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)),
            )
        }
    }

    val gestureModifier = Modifier.drawingGestureModifier(
        drawing = drawing,
        map = map,
        currentPoints = { currentPoints },
        onPointsChanged = onPointsChanged,
        onDrawingFinished = onDrawingFinished,
    )

    Box(modifier = modifier) {
        AMapHost(
            modifier = Modifier.fillMaxSize(),
            onMapReady = onMapReady,
        )
        Canvas(modifier = Modifier.fillMaxSize().then(gestureModifier)) {}
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(10.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = if (drawing) "按住并拖动鼠标，松开后结束" else "可缩放和移动地图",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Modifier.drawingGestureModifier(
    drawing: Boolean,
    map: AMap?,
    currentPoints: () -> List<PointM>,
    onPointsChanged: (List<PointM>) -> Unit,
    onDrawingFinished: () -> Unit,
): Modifier {
    var strokeStarted by remember { mutableStateOf(false) }
    if (!drawing) return this

    return pointerInteropFilter { event ->
        val activeMap = map
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                strokeStarted = true
                activeMap?.projection
                    ?.fromScreenLocation(Point(event.x.roundToInt(), event.y.roundToInt()))
                    ?.let { onPointsChanged(listOf(PointM(it.latitude, it.longitude))) }
            }

            MotionEvent.ACTION_MOVE -> if (strokeStarted) {
                activeMap?.projection
                    ?.fromScreenLocation(Point(event.x.roundToInt(), event.y.roundToInt()))
                    ?.let { latLng ->
                        val points = currentPoints()
                        val next = PointM(latLng.latitude, latLng.longitude)
                        val last = points.lastOrNull()
                        if (last == null || GeoMath.distanceM(last, next) >= 2.0) {
                            onPointsChanged(GeoMath.withCumulativeDistances(points + next))
                        }
                    }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                strokeStarted = false
                onDrawingFinished()
            }
        }
        true
    }
}

@Composable
private fun AMapHost(modifier: Modifier, onMapReady: (AMap) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember(context) { MapView(context) }

    DisposableEffect(mapView, lifecycle) {
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
        onMapReady(mapView.map.apply {
            moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(23.1291, 113.2644), 16f))
            uiSettings.isZoomControlsEnabled = true
        })
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

@Composable
private fun RouteControls(
    modifier: Modifier,
    routeName: String,
    points: List<PointM>,
    drawing: Boolean,
    errorMessage: String?,
    onNameChanged: (String) -> Unit,
    onStartDrawing: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = routeName,
            onValueChange = onNameChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("路线名称") },
            singleLine = true,
        )
        Text("有效点：${points.size}")
        Text(
            "路线长度：${String.format(Locale.CHINA, "%.1f", points.lastOrNull()?.cumulativeDistanceM ?: 0.0)} 米",
        )
        Button(
            onClick = onStartDrawing,
            modifier = Modifier.fillMaxWidth(),
            enabled = !drawing,
        ) { Text(if (points.isEmpty()) "开始绘制" else "重新绘制") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onUndo,
                enabled = points.isNotEmpty() && !drawing,
                modifier = Modifier.weight(1f),
            ) { Text("撤销一点") }
            OutlinedButton(
                onClick = onClear,
                enabled = points.isNotEmpty() || drawing,
                modifier = Modifier.weight(1f),
            ) { Text("清空") }
        }
        Button(
            onClick = onSave,
            enabled = !drawing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存路线") }
        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
