package com.robot.simulator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.robot.simulator.RobotSimulatorApplication
import com.robot.simulator.core.PointM
import com.robot.simulator.core.RoutePointCodec
import com.robot.simulator.data.local.RouteEntity
import java.util.UUID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RoutesViewModel(application: Application) : AndroidViewModel(application) {
    private val routeDao = (application as RobotSimulatorApplication).database.routeDao()

    val routes = routeDao.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun saveRoute(name: String, points: List<PointM>, onSaved: () -> Unit) {
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            routeDao.upsert(
                RouteEntity(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    controlPointsJson = RoutePointCodec.encode(points),
                    lengthM = points.lastOrNull()?.cumulativeDistanceM ?: 0.0,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                ),
            )
            onSaved()
        }
    }

    fun deleteRoute(id: String) {
        viewModelScope.launch { routeDao.deleteById(id) }
    }
}
