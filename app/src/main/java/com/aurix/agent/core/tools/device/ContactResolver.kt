package com.aurix.agent.core.tools.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class ContactMatch(val name: String, val number: String, val score: Int)

/** Accent-insensitive, ranked contact search (exact name beats partial). Used by LOOKUP_CONTACT and by the offline message/call flow. */
@Singleton
class ContactResolver @Inject constructor(@ApplicationContext private val ctx: Context) {
    suspend fun search(query: String, limit: Int = 5): List<ContactMatch> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            throw ToolException(ToolErrorType.PERMISSION_REQUIRED, "Contacts permission is not granted. Open AURIX → Settings → Phone permissions and grant it.")
        val found = LinkedHashMap<String, ContactMatch>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n++ < 20_000) {
                val name = c.getString(0).orEmpty(); val num = c.getString(1).orEmpty()
                val sc = contactScore(name, query)
                if (sc > 0) {
                    val key = num.filter { it.isDigit() }.takeLast(10)
                    val old = found[key]
                    if (old == null || old.score < sc) found[key] = ContactMatch(name, num, sc)
                }
            }
        }
        found.values.sortedByDescending { it.score }.take(limit)
    }
}
