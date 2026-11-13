package kr.contest.pleosapp.core

/**
 * 작은 JSON 읽기 (장소 검색 응답처럼 작은 문서를 읽을 때). 안드로이드 org.json 없이 JVM 시험에서도 돈다.
 *
 * 객체는 Map<String, Any?>, 배열은 List<Any?>, 문자열은 String, 수는 Double, 참·거짓은 Boolean, null은 null로 돌려준다.
 * 잘못된 문서면 null.
 */
object MiniJson {
    fun parse(text: String): Any? = runCatching { Reader(text).readDocument() }.getOrNull()

    /** 객체의 문자열 값 (수면 문자열로). 없으면 null */
    fun Map<*, *>.str(key: String): String? = when (val v = this[key]) {
        is String -> v
        is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        else -> null
    }

    /** 객체의 수 값 (문자열로 적힌 수도). 없으면 null */
    fun Map<*, *>.num(key: String): Double? = when (val v = this[key]) {
        is Double -> v
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    fun Map<*, *>.obj(key: String): Map<*, *>? = this[key] as? Map<*, *>

    fun Map<*, *>.list(key: String): List<*>? = this[key] as? List<*>

    private class Reader(private val s: String) {
        private var i = 0

        fun readDocument(): Any? {
            val v = readValue()
            skipSpace()
            require(i == s.length) { "trailing text" }
            return v
        }

        private fun readValue(): Any? {
            skipSpace()
            require(i < s.length) { "end of text" }
            return when (val c = s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else error("unexpected '$c' at $i")
            }
        }

        private fun readObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // {
            skipSpace()
            if (peek() == '}') {
                i++
                return out
            }
            while (true) {
                skipSpace()
                require(peek() == '"') { "key expected at $i" }
                val key = readString()
                skipSpace()
                require(peek() == ':') { "':' expected at $i" }
                i++
                out[key] = readValue()
                skipSpace()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return out
                    }
                    else -> error("',' or '}' expected at $i")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // [
            skipSpace()
            if (peek() == ']') {
                i++
                return out
            }
            while (true) {
                out += readValue()
                skipSpace()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return out
                    }
                    else -> error("',' or ']' expected at $i")
                }
            }
        }

        private fun readString(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(i < s.length) { "bad escape" }
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "bad unicode escape" }
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> error("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Double {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(start, i).toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return value
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
