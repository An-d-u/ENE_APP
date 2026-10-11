package dev.ene.companion.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 입력 제어만 전달한다. 사진과 녹음 본문은 인증된 HTTPS가 담당한다. */

@Serializable @SerialName("input_reserve")
data class InputReserve(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val command_id: String,
    val purpose: String,
    val byte_length: Long,
    val sha256: String,
) : ExtensionMessage()

@Serializable @SerialName("input_reserved")
data class InputReserved(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val command_id: String,
    val purpose: String,
    val upload_id: String,
) : ExtensionMessage()

@Serializable @SerialName("input_cancel")
data class InputCancel(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val command_id: String,
    val purpose: String,
    val upload_id: String,
) : ExtensionMessage()

@Serializable @SerialName("chat_images_send")
data class ChatImagesSend(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val request_id: String,
    val text: String,
    val images: List<InputImageRef>,
) : ExtensionMessage()

@Serializable @SerialName("chat_images_request")
data class ChatImagesRequest(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val query_id: String,
    val message_id: String,
    val conversation_revision: Long,
) : ExtensionMessage()

@Serializable @SerialName("chat_images_state")
data class ChatImagesState(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val query_id: String,
    val message_id: String,
    val conversation_revision: Long,
    val attachment_revision: Long,
    val images: List<ChatImageInfo>,
) : ExtensionMessage()

@Serializable @SerialName("chat_images_invalidated")
data class ChatImagesInvalidated(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val message_id: String,
    val attachment_revision: Long,
) : ExtensionMessage()

@Serializable @SerialName("chat_images_limits_request")
data class ChatImagesLimitsRequest(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val query_id: String,
) : ExtensionMessage()

@Serializable @SerialName("chat_images_limits")
data class ChatImagesLimits(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val query_id: String,
    val allowed: Boolean,
    val reason: String,
    val max_count: Long,
) : ExtensionMessage()

@Serializable @SerialName("stt_limits_request")
data class SttLimitsRequest(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val query_id: String,
) : ExtensionMessage()

@Serializable @SerialName("stt_limits")
data class SttLimits(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val query_id: String,
    val min_ms: Long,
    val max_ms: Long,
    val sample_rate: Long,
    val channels: Long,
    val sample_width: Long,
    val allowed: Boolean,
    val reason: String,
) : ExtensionMessage()

@Serializable @SerialName("stt_begin")
data class SttBegin(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val command_id: String,
) : ExtensionMessage()

@Serializable @SerialName("stt_ready")
data class SttReady(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val command_id: String,
    val min_ms: Long,
    val max_ms: Long,
) : ExtensionMessage()

@Serializable @SerialName("stt_transcribe")
data class SttTranscribe(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val command_id: String,
    val upload_id: String,
    val sha256: String,
    val byte_length: Long,
) : ExtensionMessage()

@Serializable @SerialName("stt_result")
data class SttResult(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val command_id: String,
    val status: String,
    val text: String,
    val reason: String,
) : ExtensionMessage()

@Serializable @SerialName("stt_cancel")
data class SttCancel(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val command_id: String,
) : ExtensionMessage()

@Serializable
data class InputImageRef(val upload_id: String, val sha256: String, val byte_length: Long, val mime: String)

@Serializable
data class ChatImageInfo(val id: String, val width: Long, val height: Long, val mime: String)
