package app.droplink.core.protocol

/**
 * Minimal, dependency-free JSON reader/writer for protocol control frames.
 *
 * Values map to: [Map]<String, Any?>, [List]<Any?>, [String], [Long] (integers), [Double] (decimals),
 * [Boolean] and `null`. Kept tiny on purpose so the protocol layer runs on a plain JVM in unit tests
 * and never depends on reflection (safe under R8).
 */
object Json {

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue(0)
        p.skipWs()
        if (!p.atEnd()) throw ProtocolException("Trailing characters in JSON at ${p.pos}")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw ProtocolException("Expected a JSON object")

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Double -> {
                require(value.isFinite()) { "Non-finite numbers are not valid JSON" }
                sb.append(value.toString())
            }
            is Float -> write(sb, value.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, v)
                }
                sb.append(']')
            }
            is Array<*> -> write(sb, value.asList())
            is LongArray -> write(sb, value.asList())
            is BooleanArray -> write(sb, value.asList())
            else -> throw IllegalArgumentException("Unsupported JSON type: ${value::class.java.name}")
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) pos++
        }

        fun readValue(depth: Int): Any? {
            if (depth > 32) throw ProtocolException("JSON nested too deeply")
            if (atEnd()) throw ProtocolException("Unexpected end of JSON")
            return when (val c = s[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> expectWord("true", true)
                'f' -> expectWord("false", false)
                'n' -> expectWord("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else throw ProtocolException("Unexpected '$c' at $pos")
            }
        }

        private fun expectWord(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ProtocolException("Invalid literal at $pos")
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (peek() != '"') throw ProtocolException("Expected key at $pos")
                val key = readString()
                skipWs()
                if (peek() != ':') throw ProtocolException("Expected ':' at $pos")
                pos++
                skipWs()
                map[key] = readValue(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw ProtocolException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { pos++; return list }
            while (true) {
                skipWs()
                list += readValue(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw ProtocolException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun peek(): Char = if (atEnd()) throw ProtocolException("Unexpected end of JSON") else s[pos]

        private fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                val c = peek()
                pos++
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = peek().also { pos++ }) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw ProtocolException("Bad unicode escape")
                                val hex = s.substring(pos, pos + 4)
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: throw ProtocolException("Bad unicode escape"))
                                pos += 4
                            }
                            else -> throw ProtocolException("Bad escape '\\$e'")
                        }
                    }
                    else -> {
                        if (c < ' ') throw ProtocolException("Control character in string")
                        sb.append(c)
                    }
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            var decimal = false
            while (pos < s.length) {
                val c = s[pos]
                if (c in '0'..'9') pos++
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { decimal = true; pos++ }
                else break
            }
            val text = s.substring(start, pos)
            return if (decimal) {
                text.toDoubleOrNull() ?: throw ProtocolException("Bad number '$text'")
            } else {
                text.toLongOrNull() ?: throw ProtocolException("Bad number '$text'")
            }
        }
    }
}

// Typed accessors used by message parsing ----------------------------------------------------------

internal fun Map<String, Any?>.str(key: String): String =
    this[key] as? String ?: throw ProtocolException("Missing string '$key'")

internal fun Map<String, Any?>.strOrNull(key: String): String? = this[key] as? String

internal fun Map<String, Any?>.long(key: String): Long = when (val v = this[key]) {
    is Long -> v
    is Int -> v.toLong()
    is Double -> if (v % 1.0 == 0.0) v.toLong() else throw ProtocolException("'$key' must be an integer")
    else -> throw ProtocolException("Missing number '$key'")
}

internal fun Map<String, Any?>.int(key: String): Int {
    val v = long(key)
    if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) throw ProtocolException("'$key' out of range")
    return v.toInt()
}

internal fun Map<String, Any?>.bool(key: String, default: Boolean = false): Boolean =
    this[key] as? Boolean ?: default

internal fun Map<String, Any?>.list(key: String): List<Any?> =
    this[key] as? List<Any?> ?: throw ProtocolException("Missing list '$key'")
