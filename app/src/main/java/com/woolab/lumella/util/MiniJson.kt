package com.woolab.lumella.util

/**
 * Minimal dependency-free JSON parser (plan P3). The existing credential parser
 * avoids org.json so logic stays unit-testable on the plain JVM (Android's org.json
 * is a "not mocked" stub in unit tests). This parser supports objects, arrays,
 * strings, numbers, booleans, and null, returning Map/List/String/Double/Boolean/null.
 *
 * Intended for small, controlled agent responses, not adversarial input.
 */
object MiniJson {

    /** Parse a JSON document. Returns null on malformed input. */
    fun parse(source: String): Any? = try {
        val p = Parser(source)
        p.skipWs()
        val v = p.parseValue()
        p.skipWs()
        if (p.atEnd()) v else null
    } catch (_: Exception) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    fun asObject(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun asArray(value: Any?): List<Any?>? = value as? List<Any?>

    fun string(map: Map<String, Any?>?, key: String): String? = map?.get(key) as? String

    fun stringList(map: Map<String, Any?>?, key: String): List<String> =
        (map?.get(key) as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd(): Boolean = i >= s.length

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalStateException("eof")
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBool()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return map }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipWs()
                when (s[i]) {
                    ',' -> { i++; }
                    '}' -> { i++; return map }
                    else -> throw IllegalStateException("expected , or }")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { i++; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                when (s[i]) {
                    ',' -> { i++; }
                    ']' -> { i++; return list }
                    else -> throw IllegalStateException("expected , or ]")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        val e = s[i++]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(i, i + 4); i += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalStateException("bad escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalStateException("unterminated string")
        }

        private fun parseBool(): Boolean {
            return when {
                s.startsWith("true", i) -> { i += 4; true }
                s.startsWith("false", i) -> { i += 5; false }
                else -> throw IllegalStateException("bad bool")
            }
        }

        private fun parseNull(): Any? {
            if (s.startsWith("null", i)) { i += 4; return null }
            throw IllegalStateException("bad null")
        }

        private fun parseNumber(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }

        private fun expect(c: Char) {
            if (i >= s.length || s[i] != c) throw IllegalStateException("expected $c")
            i++
        }

        private fun peek(): Char = s[i]
    }
}
