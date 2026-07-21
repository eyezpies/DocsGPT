package com.docsgpt.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "docsgpt_settings")

data class UserSettings(
    val apiHost: String,
    val token: String?,
)

/** Persists the API host and bearer token the app streams answers with. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val API_HOST = stringPreferencesKey("api_host")
        val TOKEN = stringPreferencesKey("token")
    }

    fun observe(defaultApiHost: String): Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            apiHost = prefs[Keys.API_HOST]?.takeIf { it.isNotBlank() } ?: defaultApiHost,
            token = prefs[Keys.TOKEN]?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun setApiHost(apiHost: String) {
        context.dataStore.edit { it[Keys.API_HOST] = apiHost }
    }

    suspend fun setToken(token: String?) {
        context.dataStore.edit { prefs ->
            if (token.isNullOrBlank()) prefs.remove(Keys.TOKEN) else prefs[Keys.TOKEN] = token
        }
    }
}
