package dev.ene.companion.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable @SerialName("chat_display_request")
data class ChatDisplayRequest(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
) : ExtensionMessage()

@Serializable @SerialName("chat_display_state")
data class ChatDisplaySettings(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val display_revision: Long,
    val message_split_enabled: Boolean,
) : ExtensionMessage()
