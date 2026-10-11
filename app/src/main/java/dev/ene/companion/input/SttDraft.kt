package dev.ene.companion.input

class SttDraft(private val identity: String, private val revision: Long) {
    fun append(currentIdentity: String, currentRevision: Long, draft: String, result: String): String? {
        if (identity != currentIdentity || revision != currentRevision) return null
        return appendText(draft, result)
    }
    companion object {
        fun appendText(draft: String, result: String): String? =
            (if (draft.isBlank()) result.trim() else draft + "\n" + result.trim()).takeIf { it.toByteArray(Charsets.UTF_8).size <= 16384 }
    }
}
