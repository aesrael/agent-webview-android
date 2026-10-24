package dev.agentwebview

/**
 * Line-oriented JSON transport helper: feed it one JSON command, get one JSON reply.
 * Wrap this in whatever channel you have (WebSocket, binder, stdin, an HTTP handler).
 */
suspend fun AgentSession.handleJson(command: String): String {
    val parsed = try {
        Json.parse(command) as? Map<*, *>
    } catch (_: JsonException) {
        null
    } ?: return Json.stringify(
        mapOf("ok" to false, "error" to AgentError(ErrorCode.PROTOCOL, "command must be a JSON object").toWire()),
    )
    return Json.stringify(handle(parsed))
}
