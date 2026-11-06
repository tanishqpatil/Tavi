package app.lfm3.probe.presentation

data class ResponsePart(val text: String, val thinking: Boolean, val complete: Boolean)

/** Handles model-provided tags and partial streaming tags without inventing hidden content. */
fun parseThinking(text: String, streaming: Boolean = true): List<ResponsePart> {
    val result = mutableListOf<ResponsePart>()
    var cursor = 0
    var thinking = false
    while (cursor < text.length) {
        val tag = if (thinking) "</think>" else "<think>"
        val end = text.indexOf(tag, cursor)
        if (end >= 0) {
            if (end > cursor || thinking) result += ResponsePart(text.substring(cursor, end), thinking, true)
            cursor = end + tag.length
            thinking = !thinking
        } else {
            var tail = text.substring(cursor)
            val partial = if (streaming) (1 until tag.length).lastOrNull { tail.endsWith(tag.take(it)) } ?: 0 else 0
            tail = tail.dropLast(partial)
            if (tail.isNotEmpty() || thinking) result += ResponsePart(tail, thinking, !thinking)
            break
        }
    }
    if (thinking && (result.isEmpty() || !result.last().thinking)) result += ResponsePart("", true, false)
    return result
}
