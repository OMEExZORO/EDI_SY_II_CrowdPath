package com.crowdpath.app.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.crowdpath.app.data.models.BuildingMapData

/**
 * Room entity representing a cached building map.
 */
@Entity(tableName = "cached_maps")
data class CachedMapEntity(
    @PrimaryKey val id: String,
    val name: String,
    val uploadedBy: String,
    val uploadDate: String,
    val mapData: BuildingMapData,
    val version: Int,
    val isPublic: Boolean
)
