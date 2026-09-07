package com.robot.simulator.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val controlPointsJson: String,
    val lengthM: Double,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "simulation_sessions")
data class SimulationSessionEntity(
    @PrimaryKey val id: String,
    val routeId: String,
    val routeName: String,
    val status: String,
    val configJson: String,
    val totalSteps: Int,
    val cadenceSpm: Double,
    val plannedDurationMs: Long,
    val actualDurationMs: Long,
    val finalStepCount: Int,
    val finalLatitude: Double,
    val finalLongitude: Double,
    val sensorProfile: String,
    val startedAtEpochMs: Long?,
    val endedAtEpochMs: Long?,
    val elapsedBeforePauseMs: Long,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Dao
interface RouteDao {
    @Query("SELECT * FROM routes ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): RouteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(route: RouteEntity)

    @Update
    suspend fun update(route: RouteEntity)

    @Query("DELETE FROM routes WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface SimulationSessionDao {
    @Query("SELECT * FROM simulation_sessions ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<SimulationSessionEntity>>

    @Query("SELECT * FROM simulation_sessions WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): SimulationSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SimulationSessionEntity)
}

@Database(
    entities = [RouteEntity::class, SimulationSessionEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class RobotSimulatorDatabase : RoomDatabase() {
    abstract fun routeDao(): RouteDao
    abstract fun simulationSessionDao(): SimulationSessionDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE routes ADD COLUMN lengthM REAL NOT NULL DEFAULT 0.0",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE simulation_sessions ADD COLUMN routeName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE simulation_sessions ADD COLUMN totalSteps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE simulation_sessions ADD COLUMN cadenceSpm REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE simulation_sessions ADD COLUMN plannedDurationMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE simulation_sessions ADD COLUMN actualDurationMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE simulation_sessions ADD COLUMN finalStepCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE simulation_sessions ADD COLUMN finalLatitude REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE simulation_sessions ADD COLUMN finalLongitude REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE simulation_sessions ADD COLUMN sensorProfile TEXT NOT NULL DEFAULT 'PROCEDURAL_V1'")
                db.execSQL("ALTER TABLE simulation_sessions ADD COLUMN endedAtEpochMs INTEGER DEFAULT NULL")
            }
        }

        fun create(context: Context): RobotSimulatorDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                RobotSimulatorDatabase::class.java,
                "robot-simulator.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}
