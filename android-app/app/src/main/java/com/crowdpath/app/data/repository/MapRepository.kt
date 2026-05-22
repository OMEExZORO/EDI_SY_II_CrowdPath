package com.crowdpath.app.data.repository

import android.util.Log
import com.crowdpath.app.data.api.RetrofitClient
import com.crowdpath.app.data.database.AppDatabase
import com.crowdpath.app.data.database.CachedMapEntity
import com.crowdpath.app.data.models.BuildingCreate
import com.crowdpath.app.data.models.BuildingListItem
import com.crowdpath.app.data.models.BuildingMapData
import com.crowdpath.app.data.models.BuildingResponse
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth — fetches from API, caches in Room, exposes [Flow].
 */
class MapRepository(
    private val db: AppDatabase,
    private val api: com.crowdpath.app.data.api.ApiService = RetrofitClient.api
) {

    // ── Local cache ────────────────────────────────────────────────────

    fun getCachedMaps(): Flow<List<CachedMapEntity>> =
        db.mapDao().getAllMaps()

    suspend fun getCachedMap(mapId: String): CachedMapEntity? =
        db.mapDao().getMapById(mapId)

    suspend fun deleteCachedMap(mapId: String) {
        db.mapDao().deleteMap(mapId)
    }

    // ── Remote → Cache ─────────────────────────────────────────────────

    suspend fun fetchAndCacheMapList(): Result<List<BuildingListItem>> = runCatching {
        val response = api.listMaps()
        if (!response.isSuccessful) error("API error: ${response.code()}")
        response.body() ?: emptyList()
    }

    suspend fun downloadAndCacheMap(buildingId: String): Result<BuildingMapData> = runCatching {
        val response = api.getMap(buildingId)
        if (!response.isSuccessful) error("API error: ${response.code()}")
        val body: BuildingResponse = response.body() ?: error("Empty response")

        // Cache locally
        db.mapDao().insertMap(
            CachedMapEntity(
                id = body.id,
                name = body.name,
                uploadedBy = body.uploadedBy,
                uploadDate = body.uploadDate,
                mapData = body.mapData,
                version = body.version,
                isPublic = body.isPublic
            )
        )
        body.mapData
    }

    // ── Upload ─────────────────────────────────────────────────────────

    suspend fun uploadMap(payload: BuildingCreate): Result<BuildingResponse> = runCatching {
        val response = api.uploadMap(payload)
        if (!response.isSuccessful) error("Upload failed: ${response.code()} ${response.errorBody()?.string()}")
        response.body() ?: error("Empty response")
    }

    // ── Admin Blocks ──────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    suspend fun fetchActiveAdminBlocks(buildingId: String): List<String> {
        return try {
            val response = api.getActiveBlocks(buildingId)
            if (response.isSuccessful) {
                response.body()?.get("blocked_edge_ids") as? List<String> ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.w("MapRepository", "Could not fetch admin blocks: ${e.message}")
            emptyList()
        }
    }
}
