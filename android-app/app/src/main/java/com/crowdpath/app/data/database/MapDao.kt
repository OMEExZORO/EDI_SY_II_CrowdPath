package com.crowdpath.app.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for cached building maps.
 */
@Dao
interface MapDao {

    @Query("SELECT * FROM cached_maps ORDER BY name ASC")
    fun getAllMaps(): Flow<List<CachedMapEntity>>

    @Query("SELECT * FROM cached_maps WHERE id = :mapId LIMIT 1")
    suspend fun getMapById(mapId: String): CachedMapEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMap(map: CachedMapEntity)

    @Query("DELETE FROM cached_maps WHERE id = :mapId")
    suspend fun deleteMap(mapId: String)

    @Query("DELETE FROM cached_maps")
    suspend fun deleteAllMaps()
}
