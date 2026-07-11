package com.scalpbot.bingx.data.remote.deepseek

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeepSeekMessage(val role: String, val content: String)

@Serializable
data class DeepSeekResponseFormat(val type: String = "json_object")

@Serializable
data class DeepSeekChatRequest(
    val model: String = "deepseek-chat",
    val messages: List<DeepSeekMessage>,
    val temperature: Double = 0.4,
    @SerialName("response_format") val responseFormat: DeepSeekResponseFormat? = null,
)

@Serializable
data class DeepSeekChoice(val message: DeepSeekMessage)

@Serializable
data class DeepSeekChatResponse(val choices: List<DeepSeekChoice> = emptyList())
