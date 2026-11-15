package dev.ene.companion.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable @SerialName("observation_intervals_get")
data class ObservationIntervalsGet(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val query_id: String,
) : ExtensionMessage()

@Serializable @SerialName("observation_intervals_set")
data class ObservationIntervalsSet(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val query_id: String,
    val sample_seconds: Int,
    val request_seconds: Int,
) : ExtensionMessage()

@Serializable @SerialName("observation_intervals_state")
data class ObservationIntervalsState(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val sample_seconds: Int,
    val request_seconds: Int,
    val revision: Long,
    val saved: Boolean,
    val query_id: String? = null,
) : ExtensionMessage()
