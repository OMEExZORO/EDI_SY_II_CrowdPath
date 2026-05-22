package com.crowdpath.app.data.database

import androidx.room.TypeConverter
import com.crowdpath.app.data.models.BuildingMapData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Room type-converters for serialising [BuildingMapData] as JSON.
 */
class MapTypeConverters {

    private val gson = Gson()

    @TypeConverter
    fun fromMapData(data: BuildingMapData?): String? =
        data?.let { gson.toJson(it) }

    @TypeConverter
    fun toMapData(json: String?): BuildingMapData? =
        json?.let {
            gson.fromJson(it, object : TypeToken<BuildingMapData>() {}.type)
        }
}
