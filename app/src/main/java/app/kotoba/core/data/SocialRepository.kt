package app.kotoba.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.socialStore: DataStore<Preferences> by preferencesDataStore(name = "social")

enum class AddFriendResult { ADDED, UPDATED, INVALID, SELF }

/**
 * Friends are added by exchanging codes (no account/server needed); re-adding a newer code refreshes
 * that friend's weekly XP on the leaderboard.
 */
@Singleton
class SocialRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val store = context.socialStore
    private val data = store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
    private val listSerializer = ListSerializer(FriendSnapshot.serializer())

    private object K {
        val installId = stringPreferencesKey("install_id")
        val friends = stringPreferencesKey("friends")
    }

    val friends: Flow<List<FriendSnapshot>> = data.map { decode(it[K.friends]) }

    val installId: Flow<String?> = data.map { it[K.installId] }

    suspend fun ensureInstallId(): String {
        data.first()[K.installId]?.let { return it }
        val id = UUID.randomUUID().toString().take(12)
        store.edit { if (it[K.installId] == null) it[K.installId] = id }
        return data.first()[K.installId] ?: id
    }

    suspend fun addFriend(text: String): AddFriendResult {
        val snap = FriendCode.decode(text) ?: return AddFriendResult.INVALID
        if (snap.id == ensureInstallId()) return AddFriendResult.SELF
        var result = AddFriendResult.ADDED
        store.edit { p ->
            val list = decode(p[K.friends]).toMutableList()
            val i = list.indexOfFirst { it.id == snap.id }
            if (i >= 0) {
                result = AddFriendResult.UPDATED
                // Never let an older code overwrite fresher data.
                if (snap.updatedAt >= list[i].updatedAt) list[i] = snap
            } else {
                list += snap
            }
            p[K.friends] = json.encodeToString(listSerializer, list.take(MAX_FRIENDS))
        }
        return result
    }

    suspend fun removeFriend(id: String) {
        store.edit { p -> p[K.friends] = json.encodeToString(listSerializer, decode(p[K.friends]).filterNot { it.id == id }) }
    }

    private fun decode(raw: String?): List<FriendSnapshot> =
        raw?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() } ?: emptyList()

    companion object {
        const val MAX_FRIENDS = 50
    }
}
