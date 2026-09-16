package dev.ene.companion.audio

/** AudioTrack unsigned 32비트 head를 확장하며 이전 player와 일반 역전을 거절한다. */
class PlaybackClock(private var playerId: String) {
    private var previousRaw = 0L
    private var wraps = 0L
    private var previousWritten = 0L

    fun sample(sourcePlayerId: String, rawHead: Int, writtenFrames: Long): Long? {
        if (sourcePlayerId != playerId || writtenFrames < previousWritten) return null
        val raw = rawHead.toLong() and 0xffffffffL
        var nextWraps = wraps
        if (raw < previousRaw) {
            if (previousRaw - raw <= 0x80000000L) return null
            nextWraps++
        }
        val result = (nextWraps shl 32) + raw
        if (result > writtenFrames) return null
        previousRaw = raw
        wraps = nextWraps
        previousWritten = writtenFrames
        return result
    }

    fun reset(nextPlayerId: String) {
        require(nextPlayerId != playerId)
        playerId = nextPlayerId
        previousRaw = 0
        wraps = 0
        previousWritten = 0
    }
}
