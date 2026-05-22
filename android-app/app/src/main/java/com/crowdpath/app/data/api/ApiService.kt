package com.crowdpath.app.data.api

import com.crowdpath.app.data.models.BuildingCreate
import com.crowdpath.app.data.models.BuildingListItem
import com.crowdpath.app.data.models.BuildingResponse
import com.crowdpath.app.data.models.ObstacleReport
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * Retrofit service interface that mirrors the backend REST API.
 */
interface ApiService {

    @POST("api/maps/upload")
    suspend fun uploadMap(@Body body: BuildingCreate): Response<BuildingResponse>

    @GET("api/maps/list")
    suspend fun listMaps(): Response<List<BuildingListItem>>

    @GET("api/maps/{buildingId}")
    suspend fun getMap(@Path("buildingId") buildingId: String): Response<BuildingResponse>

    @PUT("api/maps/{buildingId}")
    suspend fun updateMap(
        @Path("buildingId") buildingId: String,
        @Body body: Map<String, Any?>
    ): Response<BuildingResponse>

    @DELETE("api/maps/{buildingId}")
    suspend fun deleteMap(@Path("buildingId") buildingId: String): Response<Unit>

    @POST("api/maps/obstacle-report")
    suspend fun reportObstacle(@Body report: ObstacleReport): Response<Unit>

    @GET("api/maps/{building_id}/active-blocks")
    suspend fun getActiveBlocks(
        @Path("building_id") buildingId: String
    ): Response<Map<String, Any>>
}
