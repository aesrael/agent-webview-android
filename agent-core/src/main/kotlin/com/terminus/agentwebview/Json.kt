package com.terminus.agentwebview

/** Thrown for malformed JSON. */
class JsonException(message: String) : IllegalArgumentException(message)

/**
 * Small, dependency-free JSON reader/writer (no org.json on JVM unit tests).
 *
 * Values map to: Map<String, Any?>, List<Any?>, String, Long / Double, Boolean, null.
 * The parser is strict about structure and depth, lenient about raw control
 * characters inside strings.
 */
object Json {
    private const val MAX_DEPTH = 128

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (p.i != text.length) throw JsonException("trailing characters at ${p.i}")
        return v
    }

    fun stringify(value: Any?): String {
        val sb = StringBuilder()
        write(sb, value)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v.toString())
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> number(sb, v)
            is Float -> number(sb, v.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (e in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, e)
                }
                sb.append(']')
            }
            is Array<*> -> write(sb, v.asList())
            is Enum<*> -> quote(sb, v.name)
            else -> quote(sb, v.toString())
        }
    }

    private fun number(sb: StringBuilder, d: Double) {
        when {
            d.isNaN() || d.isInfinite() -> sb.append("null")
            d == Math.floor(d) && Math.abs(d) < 1e15 -> sb.append(d.toLong().toString())
            else -> sb.append(d.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    if (c < ' ' || c == ' ' || c == ' ') {
                        sb.append("\\u").append(String.format("%04x", c.code))
                    } else {
                        sb.append(c)
                    }
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonException("nesting too deep")
            if (i >= s.length) throw JsonException("unexpected end of input")
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> { lit("true"); true }
                'f' -> { lit("false"); false }
                'n' -> { lit("null"); null }
                else -> num()
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return out }
            while (true) {
                skipWs()
                if (peek() != '"') throw JsonException("expected string key at $i")
                val k = str()
                skipWs()
                if (peek() != ':') throw JsonException("expected ':' at $i")
                i++
                skipWs()
                out[k] = value(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw JsonException("expected ',' or '}' at $i")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { i++; return out }
            while (true) {
                skipWs()
                out += value(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw JsonException("expected ',' or ']' at $i")
                }
            }
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonException("unterminated string")
                val c = s[i]
                when {
                    c == '"' -> { i++; return sb.toString() }
                    c == '\\' -> {
                        i++
                        if (i >= s.length) throw JsonException("unterminated escape")
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) throw JsonException("bad \\u escape at $i")
                                val hex = s.substring(i + 1, i + 5)
                                val code = hex.toIntOrNull(16) ?: throw JsonException("bad \\u escape at $i")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw JsonException("bad escape '\\$e' at $i")
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }

        private fun num(): Any {
            val st = i
            if (i < s.length && s[i] == '-') i++
            val digitsStart = i
            while (i < s.length && s[i].isDigit()) i++
            if (i == digitsStart) throw JsonException("unexpected character '${s[st]}' at $st")
            var isInt = true
            if (i < s.length && s[i] == '.') {
                isInt = false
                i++
                val fs = i
                while (i < s.length && s[i].isDigit()) i++
                if (i == fs) throw JsonException("bad number at $st")
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isInt = false
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val es = i
                while (i < s.length && s[i].isDigit()) i++
                if (i == es) throw JsonException("bad number at $st")
            }
            val t = s.substring(st, i)
            return if (isInt) (t.toLongOrNull() ?: t.toDouble()) else t.toDouble()
        }

        private fun lit(l: String) {
            if (!s.startsWith(l, i)) throw JsonException("bad literal at $i")
            i += l.length
        }

        private fun peek(): Char {
            if (i >= s.length) throw JsonException("unexpected end of input")
            return s[i]
        }
    }
}

/** Typed accessors for parsed JSON maps. */
fun Map<*, *>.string(key: String): String? = this[key] as? String
fun Map<*, *>.long(key: String): Long? = (this[key] as? Number)?.toLong()
fun Map<*, *>.int(key: String): Int? = (this[key] as? Number)?.toInt()
fun Map<*, *>.double(key: String): Double? = (this[key] as? Number)?.toDouble()
fun Map<*, *>.bool(key: String): Boolean? = this[key] as? Boolean
fun Map<*, *>.map(key: String): Map<*, *>? = this[key] as? Map<*, *>
fun Map<*, *>.list(key: String): List<*>? = this[key] as? List<*>

/** Helpers for results coming back from WebView.evaluateJavascript. */
object Js {
    /**
     * evaluateJavascript returns JSON text: a quoted string for strings, JSON for
     * objects, "null" for null/undefined. Decodes it fully, including the <-style
     * escapes WebView emits for '<' and friends.
     */
    fun decodeResult(raw: String?): Any? {
        if (raw == null) return null
        val t = raw.trim()
        if (t.isEmpty() || t == "null") return null
        return Json.parse(t)
    }

    /** A string literal safe to embed in generated JavaScript. */
    fun quote(s: String): String = Json.stringify(s)
}
