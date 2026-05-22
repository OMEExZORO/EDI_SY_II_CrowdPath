package com.crowdpath.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room database for CrowdPath.
 */
@Database(entities = [CachedMapEntity::class], version = 1, exportSchema = false)
@TypeConverters(MapTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mapDao(): MapDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "crowdpath_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
