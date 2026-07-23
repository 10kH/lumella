package com.woolab.lumella.adapter

/**
 * Minimal, dependency-free JSON value tree used by [LumaTutorBrain]. `:app`'s
 * `util.MiniJson` is not visible from this module, so this is a small
 * from-scratch reader/writer scoped to what the luma REST surface needs.
 *
 * The reader is deliberately tolerant: unknown object fields are ignored by
 * callers (they just never look them up), and accessor helpers below return
 * `null`/defaults instead of throwing when an expected field is missing or
 * has the wrong shape. Only structurally malformed JSON text throws
 * [LumaJsonParseException] — callers MUST catch that and fail closed (see
 * `LumaTutorBrain`'s `UnavailableReason.SLOW_PATH_UNAVAILABLE` handling).
 */
sealed class LumaJson {
    object Null : LumaJson()
    data class Bool(val value: Boolean) : LumaJson()
    data class Num(val value: Double) : LumaJson()
    data class Str(val value: String) : LumaJson()
    data class Arr(val items: List<LumaJson>) : LumaJson()
    data class Obj(val fields: Map<String, LumaJson>) : LumaJson()
}

class LumaJsonParseException(message: String) : Exception(message)

/** Accessor helpers — all tolerant (return null/empty rather than throwing on shape mismatch). */
fun LumaJson.Obj.str(key: String): String? = (fields[key] as? LumaJson.Str)?.value

fun LumaJson.Obj.bool(key: String): Boolean? = (fields[key] as? LumaJson.Bool)?.value

fun LumaJson.Obj.num(key: String): Double? = (fields[key] as? LumaJson.Num)?.value

fun LumaJson.Obj.int(key: String): Int? = num(key)?.toInt()

fun LumaJson.Obj.obj(key: String): LumaJson.Obj? = fields[key] as? LumaJson.Obj

fun LumaJson.Obj.arr(key: String): List<LumaJson>? = (fields[key] as? LumaJson.Arr)?.items

fun LumaJson.Arr.strings(): List<String> = items.mapNotNull { (it as? LumaJson.Str)?.value }

/** Parses JSON text into a [LumaJson] tree, or throws [LumaJsonParseException] on malformed input. */
object LumaJsonParser {
    fun parse(text: String): LumaJson {
        val cursor = Cursor(text)
        cursor.skipWhitespace()
        val value = cursor.parseValue()
        cursor.skipWhitespace()
        if (!cursor.atEnd()) {
            throw LumaJsonParseException("trailing content after JSON value at offset ${cursor.pos}")
        }
        return value
    }

    /** Same as [parse] but returns `null` instead of throwing (tolerant callers). */
    fun parseOrNull(text: String): LumaJson? = try {
        parse(text)
    } catch (_: LumaJsonParseException) {
        null
    } catch (_: Exception) {
        null
    }

    private class Cursor(private val text: String) {
        var pos: Int = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun parseValue(): LumaJson {
            skipWhitespace()
            if (atEnd()) throw LumaJsonParseException("unexpected end of input")
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> LumaJson.Str(parseString())
                't' -> parseLiteral("true", LumaJson.Bool(true))
                'f' -> parseLiteral("false", LumaJson.Bool(false))
                'n' -> parseLiteral("null", LumaJson.Null)
                else -> parseNumber()
            }
        }

        private fun parseLiteral(literal: String, value: LumaJson): LumaJson {
            if (pos + literal.length > text.length || text.substring(pos, pos + literal.length) != literal) {
                throw LumaJsonParseException("invalid literal at offset $pos")
            }
            pos += literal.length
            return value
        }

        private fun parseObject(): LumaJson.Obj {
            expect('{')
            val fields = LinkedHashMap<String, LumaJson>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return LumaJson.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw LumaJsonParseException("expected string key at offset $pos")
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                fields[key] = value
                skipWhitespace()
                when (peek()) {
                    ',' -> { pos++; continue }
                    '}' -> { pos++; break }
                    else -> throw LumaJsonParseException("expected ',' or '}' at offset $pos")
                }
            }
            return LumaJson.Obj(fields)
        }

        private fun parseArray(): LumaJson.Arr {
            expect('[')
            val items = mutableListOf<LumaJson>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return LumaJson.Arr(items)
            }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> { pos++; continue }
                    ']' -> { pos++; break }
                    else -> throw LumaJsonParseException("expected ',' or ']' at offset $pos")
                }
            }
            return LumaJson.Arr(items)
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw LumaJsonParseException("unterminated string")
                val c = text[pos]
                pos++
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw LumaJsonParseException("unterminated escape")
                        val esc = text[pos]
                        pos++
                        when (esc) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw LumaJsonParseException("invalid unicode escape")
                                val hex = text.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: throw LumaJsonParseException("invalid unicode escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw LumaJsonParseException("invalid escape at offset $pos")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): LumaJson.Num {
            val start = pos
            if (peekOrNull() == '-') pos++
            while (pos < text.length && text[pos].isDigit()) pos++
            if (pos < text.length && text[pos] == '.') {
                pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos == start) throw LumaJsonParseException("invalid number at offset $pos")
            val raw = text.substring(start, pos)
            val value = raw.toDoubleOrNull() ?: throw LumaJsonParseException("invalid number '$raw'")
            return LumaJson.Num(value)
        }

        private fun peek(): Char = if (atEnd()) throw LumaJsonParseException("unexpected end of input") else text[pos]

        private fun peekOrNull(): Char? = if (atEnd()) null else text[pos]

        private fun expect(c: Char) {
            if (atEnd() || text[pos] != c) throw LumaJsonParseException("expected '$c' at offset $pos")
            pos++
        }
    }
}

/** Serializes a [LumaJson] tree to compact JSON text. */
object LumaJsonWriter {
    fun write(value: LumaJson): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(sb: StringBuilder, value: LumaJson) {
        when (value) {
            is LumaJson.Null -> sb.append("null")
            is LumaJson.Bool -> sb.append(if (value.value) "true" else "false")
            is LumaJson.Num -> sb.append(formatNumber(value.value))
            is LumaJson.Str -> appendString(sb, value.value)
            is LumaJson.Arr -> {
                sb.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    append(sb, item)
                }
                sb.append(']')
            }
            is LumaJson.Obj -> {
                sb.append('{')
                value.fields.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) sb.append(',')
                    appendString(sb, key)
                    sb.append(':')
                    append(sb, item)
                }
                sb.append('}')
            }
        }
    }

    private fun formatNumber(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    private fun appendString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}

/** Small builders to keep call sites in [LumaTutorBrain] readable. */
fun jsonObj(vararg pairs: Pair<String, LumaJson>): LumaJson.Obj = LumaJson.Obj(linkedMapOf(*pairs))

fun jsonStr(value: String?): LumaJson = if (value == null) LumaJson.Null else LumaJson.Str(value)

fun jsonArrOfStr(values: List<String>): LumaJson = LumaJson.Arr(values.map { LumaJson.Str(it) })
