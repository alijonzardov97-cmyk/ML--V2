package com.privacy.imsidetector.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

/**
 * Improvement #6, now implemented: history contains geo-tagged movement
 * data, so the on-disk DB file is encrypted with SQLCipher. The passphrase
 * itself lives only in Keystore-backed EncryptedSharedPreferences
 * (see DbKeyStore) — never hardcoded, never logged.
 */
@Database(
    entities = [CellHistoryEntity::class, ObservationEntity::class, AlertEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun cellHistoryDao(): CellHistoryDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }

        private fun build(context: Context): AppDatabase {
            SQLiteDatabase.loadLibs(context)
            val passphrase = DbKeyStore.getOrCreatePassphrase(context)
            val factory = SupportFactory(SQLiteDatabase.getBytes(passphrase))

            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "imsi_detector_encrypted.db"
            )
                .openHelperFactory(factory)
                .build()
        }
    }
}
