package dev.ene.companion.connection

/** 외부 응답 원문이나 자격증명을 예외에 담지 않는다. */
class ConnectionException(val code: String) : Exception(code)
