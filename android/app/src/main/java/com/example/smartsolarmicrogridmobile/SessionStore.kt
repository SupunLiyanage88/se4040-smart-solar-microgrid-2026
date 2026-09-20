package com.example.smartsolarmicrogridmobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** SQLite keeps profile/reference data and an encrypted session; passwords are never persisted. */
class SessionStore(context: Context) : SQLiteOpenHelper(context, "account.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE session (id INTEGER PRIMARY KEY CHECK(id=1), server TEXT NOT NULL, token TEXT NOT NULL, expires TEXT NOT NULL, profile TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future schema versions must supply an explicit migration to preserve profile data.
        check(oldVersion == newVersion) { "Unsupported account database upgrade" }
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("solargrid.session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("solargrid.session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    fun save(session: AccountSession) {
        writableDatabase.insertWithOnConflict("session", null, ContentValues().apply {
            put("id", 1); put("server", session.server); put("token", encrypt(session.token))
            put("expires", session.expires); put("profile", session.user.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Could not persist account session" } }
    }
    fun load(): AccountSession? {
        readableDatabase.rawQuery("SELECT server, token, expires, profile FROM session WHERE id=1", null).use {
            if (!it.moveToFirst()) return null
            return AccountSession(it.getString(0), decrypt(it.getString(1)), it.getString(2), JSONObject(it.getString(3)))
        }
    }
    fun clear() { writableDatabase.delete("session", null, null) }
}
