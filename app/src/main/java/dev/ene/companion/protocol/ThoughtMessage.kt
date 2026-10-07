package dev.ene.companion.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable @SerialName("thought_request")
data class ThoughtRequest(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val conversation_revision: Long,
    val query_id: String,
    val message_id: String,
) : ExtensionMessage()

@Serializable @SerialName("thought_response")
data class ThoughtResponse(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val conversation_revision: Long,
    val query_id: String,
    val message_id: String,
    val status: String,
    val text: String,
) : ExtensionMessage()

@Serializable @SerialName("thought_invalidated")
data class ThoughtInvalidated(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
) : ExtensionMessage()
