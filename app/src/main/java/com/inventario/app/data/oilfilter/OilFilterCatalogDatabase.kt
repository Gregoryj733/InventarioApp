package com.inventario.app.data.oilfilter

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Base SQLite exclusiva del validador de filtro de aceite. Se siembra desde
 * assets y no participa de la sincronización en la nube.
 */
class OilFilterCatalogDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DB_NAME,
    null,
    DB_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE meta (
                key TEXT PRIMARY KEY NOT NULL,
                value TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE oil_filter_apps (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                marca TEXT NOT NULL,
                modelo TEXT NOT NULL,
                motor TEXT NOT NULL,
                cilindrada TEXT NOT NULL,
                anio TEXT NOT NULL,
                categoria TEXT NOT NULL,
                filtro_codigo TEXT NOT NULL,
                filtro_rol TEXT NOT NULL,
                tipo_filtro TEXT NOT NULL,
                aceites TEXT NOT NULL,
                alternativas TEXT NOT NULL,
                equivalencias TEXT NOT NULL,
                observaciones TEXT NOT NULL,
                search_text TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_oil_filter_search ON oil_filter_apps(search_text)"
        )
        db.execSQL(
            "CREATE INDEX idx_oil_filter_modelo ON oil_filter_apps(modelo)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS oil_filter_apps")
        db.execSQL("DROP TABLE IF EXISTS meta")
        onCreate(db)
    }

    fun catalogVersion(): Int {
        readableDatabase.rawQuery(
            "SELECT value FROM meta WHERE key = ?",
            arrayOf(META_CATALOG_VERSION)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return 0
            return cursor.getString(0).toIntOrNull() ?: 0
        }
    }

    fun rowCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM oil_filter_apps", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    companion object {
        const val DB_NAME = "oil_filter_catalog.db"
        const val DB_VERSION = 1
        const val META_CATALOG_VERSION = "catalog_version"
        const val TABLE = "oil_filter_apps"
    }
}
