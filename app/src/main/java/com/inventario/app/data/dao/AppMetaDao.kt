package com.inventario.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.inventario.app.data.entity.AppMeta
import kotlinx.coroutines.flow.Flow

@Dao
interface AppMetaDao {
    @Query("SELECT * FROM app_meta WHERE id = 1 LIMIT 1")
    fun observe(): Flow<AppMeta?>

    @Query("SELECT * FROM app_meta WHERE id = 1 LIMIT 1")
    suspend fun get(): AppMeta?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: AppMeta)
}
