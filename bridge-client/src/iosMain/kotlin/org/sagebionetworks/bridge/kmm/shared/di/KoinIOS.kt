package org.sagebionetworks.bridge.kmm.shared.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.sagebionetworks.bridge.kmm.shared.BridgeConfig
import org.sagebionetworks.bridge.kmm.shared.IOSBridgeConfig
import org.sagebionetworks.bridge.kmm.shared.cache.BridgeResourceDatabase
import org.sagebionetworks.bridge.kmm.shared.cache.SqliteDriverFactory
import platform.Foundation.NSFileManager

actual val platformModule = module {
    single<SqlDriver> { SqliteDriverFactory.createDriver(BridgeResourceDatabase.Schema, "resource.db") }

    single<CoroutineScope>(named("background"))  { MainScope() }

    single<BridgeConfig> { IOSBridgeConfig }
}
