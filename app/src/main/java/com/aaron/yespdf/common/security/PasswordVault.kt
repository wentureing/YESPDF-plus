package com.aaron.yespdf.common.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.blankj.utilcode.util.SPStaticUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * PDF 打开密码的保存与读取。
 *
 * 设计决定：
 * 1. **不写进 GreenDAO 的 PDF 实体**。给实体加字段会改变 @Generated 的 hash，
 *    greendao 插件会拒绝编译，还得配套 schema 迁移；而密码与书本是 1:1 的弱关联，
 *    用"路径 → 密文"的 SP 映射更轻，也不影响现有数据库。
 * 2. **只存密文**：明文 AES/GCM 加密后落盘，密钥由 Android Keystore 保管（不出安全硬件）。
 * 3. **API < 23 直接不保存**（Keystore AES 不可用），绝不降级为明文存储。
 * 4. 解密失败（换机、清 key、密码被改）时返回 null，调用方负责清除并重新询问。
 *
 * @author YESPDF-plus
 */
object PasswordVault {

    private const val SP_MAP = "SP_PASSWORD_MAP"
    private const val KEY_ALIAS = "yespdf_pdf_pwd_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val BLOB_PREFIX = "v1"
    private const val KEY_SIZE = 256
    private const val TAG_BITS = 128

    /** 当前设备是否支持保存密码 */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    /**
     * 保存密码。
     *
     * @return true 表示确实已加密落盘；false 表示设备不支持或加密失败（调用方应提示）
     */
    fun save(path: String, password: String): Boolean {
        if (path.isEmpty() || password.isEmpty() || !isSupported()) return false
        val blob = encrypt(password) ?: return false
        val map = readMap()
        map[path] = blob
        writeMap(map)
        return true
    }

    /** 读取密码；不存在或解密失败返回 null */
    fun load(path: String): String? {
        if (path.isEmpty() || !isSupported()) return null
        val blob = readMap()[path] ?: return null
        return decrypt(blob)
    }

    fun has(path: String): Boolean = readMap().containsKey(path)

    fun clear(path: String) {
        val map = readMap()
        if (map.remove(path) != null) writeMap(map)
    }

    fun clearAll() {
        writeMap(HashMap())
    }

    /** 已保存密码的条目数（供"数据管理"页展示，不泄露任何内容） */
    fun count(): Int = readMap().size

    //region 加解密

    private fun encrypt(plain: String): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            listOf(
                    BLOB_PREFIX,
                    Base64.encodeToString(iv, Base64.NO_WRAP),
                    Base64.encodeToString(encrypted, Base64.NO_WRAP)
            ).joinToString(":")
        } catch (e: Throwable) {
            null
        }
    }

    private fun decrypt(blob: String): String? {
        val parts = blob.split(":")
        if (parts.size != 3 || parts[0] != BLOB_PREFIX) return null
        return try {
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                    javax.crypto.spec.GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Throwable) {
            // 密钥丢失或被替换 —— 视为"没有保存过密码"，由调用方清除后重新询问
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE)
                .build())
        return generator.generateKey()
    }
    //endregion

    //region 存储

    private fun readMap(): MutableMap<String, String> {
        val json = SPStaticUtils.getString(SP_MAP, "")
        if (json.isNullOrEmpty()) return HashMap()
        return try {
            val type = object : TypeToken<MutableMap<String, String>>() {}.type
            val map: MutableMap<String, String>? = Gson().fromJson(json, type)
            map ?: HashMap()
        } catch (e: Exception) {
            HashMap()
        }
    }

    private fun writeMap(map: Map<String, String>) {
        SPStaticUtils.put(SP_MAP, Gson().toJson(map))
    }
    //endregion
}
