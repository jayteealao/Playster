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
class SettingsManager @Inject constructor (
    @ApplicationContext context: Context
) {
    private val dataStore = context.dataStore

    private val accountNameKey = stringPreferencesKey("account_name")
    val accountName: Flow<String> = dataStore.data.map { preferences ->
        preferences[accountNameKey] ?: ""
    }

    suspend fun setAccountName(accountName: String) {
        dataStore.edit { preferences ->
            preferences[accountNameKey] = accountName
        }
    }

    private val accountTypeKey = stringPreferencesKey("account_type")
    val accountType: Flow<String> = dataStore.data.map { preferences ->
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

    suspend fun getAccount(): Flow<Account?> {
        return combine(accountName, accountType) { name, type ->
            if (name.isNotBlank() && type.isNotBlank()) {
                return@combine Account(name, type)
            } else {
                return@combine null
            }
        }
    }
}

