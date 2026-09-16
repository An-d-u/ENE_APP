package dev.ene.companion.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 현재 등록·서버·연결에 결합하며 기본 대화 순서를 소유하지 않는 봉투. */
@Serializable
sealed class ExtensionMessage : WireMessage() {
    abstract val registration_generation: Long
    abstract val server_epoch: String
    abstract val connection_generation: String
}

data class ExtensionContext(val registrationGeneration: Long, val serverEpoch: String, val connectionGeneration: String, val conversationId: String)

@Serializable @SerialName("extensions_ready")
data class ExtensionsReady(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val capabilities: List<String>
) : ExtensionMessage()

@Serializable @SerialName("audio_availability")
data class AudioAvailability(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val available: Boolean,
    val conversation_id: String,
    val reason: String
) : ExtensionMessage()

@Serializable @SerialName("audio_status")
data class AudioStatus(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val mode: String,
    val reason: String
) : ExtensionMessage()

@Serializable @SerialName("audio_offer")
data class AudioOffer(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val message_id: String,
    val operation_id: String,
    val utterance_id: String,
    val sample_rate: Int,
    val channels: Int,
    val sample_width: Int,
    val prepare_timeout_ms: Int
) : ExtensionMessage()

@Serializable @SerialName("audio_prepared")
data class AudioPrepared(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val message_id: String,
    val operation_id: String,
    val utterance_id: String,
    val buffered_frames: Long
) : ExtensionMessage()

@Serializable @SerialName("audio_rejected")
data class AudioRejected(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val message_id: String,
    val operation_id: String,
    val utterance_id: String,
    val reason: String
) : ExtensionMessage()

@Serializable @SerialName("audio_start")
data class AudioStart(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val message_id: String,
    val operation_id: String,
    val utterance_id: String
) : ExtensionMessage()

@Serializable @SerialName("audio_started")
data class AudioStarted(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val message_id: String,
    val operation_id: String,
    val utterance_id: String,
    val played_frames: Long
) : ExtensionMessage()

@Serializable @SerialName("audio_source_end")
data class AudioSourceEnd(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val message_id: String,
    val operation_id: String,
    val utterance_id: String,
    val total_frames: Long
) : ExtensionMessage()

@Serializable @SerialName("audio_progress")
data class AudioProgress(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val message_id: String,
    val operation_id: String,
    val utterance_id: String,
    val played_frames: Long,
    val mouth_open: Double
) : ExtensionMessage()

@Serializable @SerialName("audio_progress_ack")
data class AudioProgressAck(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val message_id: String,
    val operation_id: String,
    val utterance_id: String,
    val played_frames: Long
) : ExtensionMessage()

@Serializable @SerialName("audio_finished")
data class AudioFinished(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val message_id: String,
    val operation_id: String,
    val utterance_id: String,
    val played_frames: Long
) : ExtensionMessage()

@Serializable @SerialName("audio_cancel")
data class AudioCancel(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val message_id: String,
    val operation_id: String,
    val utterance_id: String,
    val reason: String
) : ExtensionMessage()

@Serializable @SerialName("character_changed")
data class CharacterChanged(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val state_revision: Long,
    val model_version: String?,
    val reason: String
) : ExtensionMessage()

@Serializable @SerialName("character_snapshot_request")
data class CharacterSnapshotRequest(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String
) : ExtensionMessage()

@Serializable @SerialName("character_action")
data class CharacterAction(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val model_version: String,
    val action_seq: Long,
    val kind: String,
    val action_id: String,
    val duration_ms: Int
) : ExtensionMessage()

@Serializable @SerialName("character_playback")
data class CharacterPlayback(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val conversation_id: String,
    val message_id: String,
    val utterance_id: String,
    val output: String,
    val played_ms: Long,
    val mouth_open: Double,
    val active: Boolean
) : ExtensionMessage()

@Serializable @SerialName("head_pat")
data class HeadPat(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val model_version: String,
    val interaction_id: String,
    val interaction_no: Long,
    val seq: Long,
    val phase: String,
    val intensity: Double
) : ExtensionMessage()

@Serializable @SerialName("head_pat_state")
data class HeadPatState(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val model_version: String,
    val interaction_id: String,
    val interaction_no: Long,
    val seq: Long,
    val phase: String,
    val intensity: Double,
    val source: String,
    val reason: String
) : ExtensionMessage()

@Serializable @SerialName("character_settings_patch")
data class CharacterSettingsPatch(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val model_version: String,
    val command_id: String,
    val expected_revision: Long,
    val changes: JsonObject,
    val parameters: JsonObject
) : ExtensionMessage()

@Serializable @SerialName("character_settings_result")
data class CharacterSettingsResult(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val command_id: String,
    val status: String,
    val settings_revision: Long,
    val reason: String
) : ExtensionMessage()

@Serializable @SerialName("extension_error")
data class ExtensionError(
    override val registration_generation: Long,
    override val server_epoch: String,
    override val connection_generation: String,
    val feature: String,
    val code: String,
    val command_id: String? = null
) : ExtensionMessage()
