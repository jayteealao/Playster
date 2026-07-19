package com.github.jayteealao.playster

import android.accounts.Account
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsManager
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val dataStore = context.dataStore

        private val accountNameKey = stringPreferencesKey("account_name")
        val accountName: Flow<String> =
            dataStore.data.map { preferences ->
                preferences[accountNameKey] ?: ""
            }

        suspend fun setAccountName(accountName: String) {
            dataStore.edit { preferences ->
                preferences[accountNameKey] = accountName
            }
        }

        private val accountTypeKey = stringPreferencesKey("account_type")
        val accountType: Flow<String> =
            dataStore.data.map { preferences ->
                preferences[accountTypeKey] ?: ""
            }

        suspend fun setAccountType(accountType: String) {
            dataStore.edit { preferences ->
                preferences[accountTypeKey] = accountType
            }
        }

        suspend fun saveAccount(account: Account) {
            dataStore.edit { preferences ->
                preferences[accountNameKey] = account.name
                preferences[accountTypeKey] = account.type
            }
        }

        // Editorial reading preferences (face / type size / line height).
        // These are NOT needed before the first frame, so they live in the
        // async DataStore; the palette deliberately does not — it has its own
        // synchronous store (see ui.editorial.EditorialThemeGate) because the
        // cold-start window background must know it before setContent.

        private val editorialFaceKey = stringPreferencesKey("editorial_face")
        val editorialFace: Flow<String> =
            dataStore.data.map { preferences ->
                preferences[editorialFaceKey] ?: "source"
            }

        suspend fun setEditorialFace(faceKey: String) {
            dataStore.edit { preferences ->
                preferences[editorialFaceKey] = faceKey
            }
        }

        private val editorialSizeStepKey = stringPreferencesKey("editorial_size_step")
        val editorialSizeStep: Flow<String> =
            dataStore.data.map { preferences ->
                preferences[editorialSizeStepKey] ?: "m"
            }

        suspend fun setEditorialSizeStep(sizeStepKey: String) {
            dataStore.edit { preferences ->
                preferences[editorialSizeStepKey] = sizeStepKey
            }
        }

        private val editorialLineHeightStepKey = stringPreferencesKey("editorial_line_height_step")
        val editorialLineHeightStep: Flow<String> =
            dataStore.data.map { preferences ->
                preferences[editorialLineHeightStepKey] ?: "comfortable"
            }

        suspend fun setEditorialLineHeightStep(lineHeightStepKey: String) {
            dataStore.edit { preferences ->
                preferences[editorialLineHeightStepKey] = lineHeightStepKey
            }
        }

        // Default playback speed. Stored as a string (mirroring the face/size keys)
        // so a corrupt/absent value resolves to 1.0x rather than crashing the
        // Float mapper; the Player seeds its starting speed from this, and the
        // controller snaps whatever it gets to the nearest supported YouTube rate.
        private val editorialDefaultSpeedKey = stringPreferencesKey("editorial_default_speed")
        val defaultSpeed: Flow<Float> =
            dataStore.data.map { preferences ->
                preferences[editorialDefaultSpeedKey]?.toFloatOrNull() ?: DEFAULT_SPEED
            }

        suspend fun setDefaultSpeed(rate: Float) {
            dataStore.edit { preferences ->
                preferences[editorialDefaultSpeedKey] = rate.toString()
            }
        }

        suspend fun getAccount(): Flow<Account?> {
            return combine(accountName, accountType) { name, type ->
                if (name.isNotBlank() && type.isNotBlank()) {
                    return@combine Account(name, type)
                } else {
                    return@combine null
                }
            }
        }

        private companion object {
            const val DEFAULT_SPEED = 1.0f
        }
    }
