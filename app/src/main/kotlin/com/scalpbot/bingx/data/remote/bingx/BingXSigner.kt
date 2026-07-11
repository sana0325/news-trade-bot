package com.scalpbot.bingx.data.remote.bingx

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 підпис запитів BingX. За документацією BingX параметри перед
 * підписом сортуються за ASCII-кодом ключа (dictionary order), а результат
 * додається як параметр `signature`; API-ключ іде окремо в заголовку X-BX-APIKEY.
 */
object BingXSigner {

    private const val HMAC_SHA256 = "HmacSHA256"

    fun sign(secret: String, payload: String): String {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_SHA256))
        val bytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    /** Повертає query-string з відсортованими параметрами і доданим signature. */
    fun buildSignedQuery(params: Map<String, String>, secret: String): String {
        val sorted = params.toSortedMap()
        val base = sorted.entries.joinToString("&") { (k, v) -> "$k=$v" }
        val signature = sign(secret, base)
        return "$base&signature=$signature"
    }
}
