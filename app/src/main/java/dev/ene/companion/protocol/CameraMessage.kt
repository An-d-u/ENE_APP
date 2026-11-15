package dev.ene.companion.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 카메라 본문은 예약한 HTTPS 업로드에만 넣는다. */
@Serializable @SerialName("camera_share")
data class CameraShare(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val generation: Long,
    val lens: String,
    val question: Boolean,
    val observe: Boolean,
) : ExtensionMessage()

@Serializable @SerialName("camera_state")
data class CameraState(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val generation: Long,
    val available: Boolean,
    val observe_allowed: Boolean,
    val model: String,
    val remote: Boolean,
) : ExtensionMessage()

@Serializable @SerialName("camera_question")
data class CameraQuestion(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val generation: Long,
    val request_id: String,
    val text: String,
    val frame: InputImageRef,
) : ExtensionMessage()

@Serializable @SerialName("camera_observation")
data class CameraObservation(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val generation: Long,
    val frame: InputImageRef,
) : ExtensionMessage()
