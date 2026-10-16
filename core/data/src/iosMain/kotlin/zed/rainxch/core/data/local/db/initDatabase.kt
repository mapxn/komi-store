package zed.rainxch.core.data.local.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import zed.rainxch.core.data.local.IosAppDataPaths
import zed.rainxch.core.data.local.db.migrations.MIGRATION_19_20

fun initDatabase(): AppDatabase =
    Room
        .databaseBuilder<AppDatabase>(
            name = "${IosAppDataPaths.appDataDir()}/github_store.db",
        ).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(true)
        .addMigrations(MIGRATION_19_20)
        .build()
