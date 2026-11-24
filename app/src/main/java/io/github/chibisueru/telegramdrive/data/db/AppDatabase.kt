package io.github.chibisueru.telegramdrive.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FolderEntity::class, FileEntity::class, TransferEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): DriveDao
    abstract fun transfers(): TransferDao

    companion object {
        /** v4 adds trash, favorites and "recently opened". */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("folders", "files")) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `deletedAt` INTEGER")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `trashedRoot` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `starred` INTEGER NOT NULL DEFAULT 0")
                }
                db.execSQL("ALTER TABLE `files` ADD COLUMN `lastOpenedAt` INTEGER")
            }
        }

        /** v3 adds the transfer queue; the drive index is kept. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `transfers` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `upload` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `size` INTEGER NOT NULL, " +
                        "`sourceUri` TEXT, `folderId` TEXT, `fileId` TEXT, `state` TEXT NOT NULL, " +
                        "`progress` REAL NOT NULL, `error` TEXT, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfers_state` ON `transfers` (`state`)")
            }
        }
    }
}
