package com.roadlink.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [EmergencyEventEntity::class, DeliveryAttemptEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class EmergencyDatabase : RoomDatabase() {

    abstract fun emergencyDao(): EmergencyDao

    companion object {
        const val NAME = "roadlink.db"

        @Volatile
        private var instance: EmergencyDatabase? = null

        fun get(context: Context): EmergencyDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): EmergencyDatabase =
            Room.databaseBuilder(context, EmergencyDatabase::class.java, NAME)
                // NO fallbackToDestructiveMigration. That call would wipe the
                // table on a schema change, which for this product means
                // silently discarding undelivered emergencies. If a migration
                // is ever missing we would rather fail loudly at open time
                // than lose an SOS.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Durability over speed. FULL means a committed
                        // transaction has reached the disk before the write
                        // returns, which is what makes "persisted before
                        // transmit" true across a process kill or a battery
                        // pull rather than merely likely.
                        db.execSQL("PRAGMA synchronous = FULL")
                    }
                })
                .build()
    }
}
