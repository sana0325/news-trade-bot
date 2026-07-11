package com.scalpbot.bingx.core.util

import android.util.Log

/**
 * Єдина точка логування. У фазі 14 сюди додається файловий sink з ротацією;
 * до того часу всі логи йдуть у logcat, щоб решта коду вже викликала фінальний API.
 */
object AppLogger {
    fun d(tag: String, message: String) = Log.d(tag, message)
    fun i(tag: String, message: String) = Log.i(tag, message)
    fun w(tag: String, message: String, throwable: Throwable? = null) = Log.w(tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = Log.e(tag, message, throwable)
}
