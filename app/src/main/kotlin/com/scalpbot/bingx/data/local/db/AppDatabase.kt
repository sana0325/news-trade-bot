package com.scalpbot.bingx.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.scalpbot.bingx.data.local.db.dao.LessonDao
import com.scalpbot.bingx.data.local.db.dao.PairDao
import com.scalpbot.bingx.data.local.db.dao.ReportDao
import com.scalpbot.bingx.data.local.db.dao.TradeDao
import com.scalpbot.bingx.data.local.db.entity.LessonEntity
import com.scalpbot.bingx.data.local.db.entity.PairCacheEntity
import com.scalpbot.bingx.data.local.db.entity.ReportEntity
import com.scalpbot.bingx.data.local.db.entity.TradeEntity

@Database(
    entities = [TradeEntity::class, ReportEntity::class, LessonEntity::class, PairCacheEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun tradeDao(): TradeDao
    abstract fun reportDao(): ReportDao
    abstract fun lessonDao(): LessonDao
    abstract fun pairDao(): PairDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "scalpbot.db",
                )
                    // v1→v2 додав atrPercentAtEntry/durationSeconds до trades; попередньої
                    // схеми не експортовано (перша реальна зміна), тож повної Migration
                    // не написати безпечно — журнал угод буде скинуто один раз при апдейті.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
