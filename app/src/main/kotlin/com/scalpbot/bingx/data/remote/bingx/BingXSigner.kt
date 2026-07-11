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

    /**
     * Підпис рахується над СИРими значеннями (без URL-кодування) — так BingX
     * рахує його на своєму боці, розкодувавши query-string назад у сирі значення.
     * Якщо підписати вже закодований рядок (напр. JSON у stopLoss/takeProfit з
     * `{`, `"`, `:`), підпис не збігається з тим, що очікує сервер, і BingX
     * повертає "Signature verification failed" — саме so сталось до цього фіксу.
     * `encode` застосовується лише до значень у фінальному query-string, який
     * реально йде по мережі; сам підпис від encode не залежить.
     */
    fun buildSignedQuery(rawParams: Map<String, String>, secret: String, encode: (String) -> String): String {
        val sorted = rawParams.toSortedMap()
        val rawBase = sorted.entries.joinToString("&") { (k, v) -> "$k=$v" }
        val signature = sign(secret, rawBase)
        val encodedBase = sorted.entries.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        return "$encodedBase&signature=$signature"
    }
}
