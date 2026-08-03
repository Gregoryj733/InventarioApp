package com.inventario.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.inventario.app.data.dao.AppMetaDao
import com.inventario.app.data.dao.ProductDao
import com.inventario.app.data.dao.SaleRecordDao
import com.inventario.app.data.dao.UserDao
import com.inventario.app.data.entity.AppMeta
import com.inventario.app.data.entity.Product
import com.inventario.app.data.entity.ProductFts
import com.inventario.app.data.entity.SaleRecord
import com.inventario.app.data.entity.User

@Database(
    entities = [Product::class, ProductFts::class, User::class, AppMeta::class, SaleRecord::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun userDao(): UserDao
    abstract fun appMetaDao(): AppMetaDao
    abstract fun saleRecordDao(): SaleRecordDao
}
