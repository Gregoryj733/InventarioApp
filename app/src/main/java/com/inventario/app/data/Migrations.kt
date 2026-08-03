package com.inventario.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sale_records` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `totalUsd` REAL NOT NULL
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `products_fts` USING FTS4(`description` TEXT NOT NULL, content=`products`)"
        )
        db.execSQL(
            "INSERT INTO `products_fts`(`docid`, `description`) SELECT `rowid`, `description` FROM `products`"
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `room_fts_content_sync_products_fts_BEFORE_UPDATE`
            BEFORE UPDATE ON `products` BEGIN
              DELETE FROM `products_fts` WHERE `docid`=OLD.`rowid`;
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `room_fts_content_sync_products_fts_BEFORE_DELETE`
            BEFORE DELETE ON `products` BEGIN
              DELETE FROM `products_fts` WHERE `docid`=OLD.`rowid`;
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `room_fts_content_sync_products_fts_AFTER_UPDATE`
            AFTER UPDATE ON `products` BEGIN
              INSERT INTO `products_fts`(`docid`, `description`) VALUES (NEW.`rowid`, NEW.`description`);
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `room_fts_content_sync_products_fts_AFTER_INSERT`
            AFTER INSERT ON `products` BEGIN
              INSERT INTO `products_fts`(`docid`, `description`) VALUES (NEW.`rowid`, NEW.`description`);
            END
            """.trimIndent()
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `products` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `sale_records` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_syncId` ON `products` (`syncId`)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `users` ADD COLUMN `active` INTEGER NOT NULL DEFAULT 1")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sale_line_items` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `saleRecordId` INTEGER NOT NULL,
                `productId` INTEGER NOT NULL,
                `description` TEXT NOT NULL,
                `quantity` REAL NOT NULL,
                `unit` TEXT NOT NULL,
                `unitPriceUsd` REAL NOT NULL,
                `totalUsd` REAL NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sale_line_items_saleRecordId` ON `sale_line_items` (`saleRecordId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sale_line_items_productId` ON `sale_line_items` (`productId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sale_line_items_createdAt` ON `sale_line_items` (`createdAt`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cash_closing_records` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `branchName` TEXT NOT NULL,
                `dateText` TEXT NOT NULL,
                `closedAt` INTEGER NOT NULL,
                `rate` REAL NOT NULL,
                `salesUsd` REAL NOT NULL,
                `salesBs` REAL NOT NULL,
                `grandTotalUsd` REAL NOT NULL,
                `grandTotalBs` REAL NOT NULL,
                `differenceUsd` REAL NOT NULL,
                `hasDifference` INTEGER NOT NULL,
                `username` TEXT NOT NULL,
                `observations` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cash_closing_records_closedAt` ON `cash_closing_records` (`closedAt`)")
    }
}
