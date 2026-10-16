package zed.rainxch.core.data.local.data_store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import zed.rainxch.core.data.local.IosAppDataPaths

fun createAnnouncementsDataStore(): DataStore<Preferences> =
    createDataStore(
        producePath = { "${IosAppDataPaths.appDataDir()}/$announcementsDataStoreFileName" },
    )
