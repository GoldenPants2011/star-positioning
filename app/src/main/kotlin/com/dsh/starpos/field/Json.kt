package com.dsh.starpos.field

/**
 * 极简 JSON 读写（纯 Kotlin，无 Android 依赖），用于会话清单与帧元数据。
 * 只支持对象/数组/字符串/数字/布尔/null —— 足够表达采集包，避免引入第三方依赖。
 */
object JsonText {

    fun write(value: Any?): String = StringBuilder(512).also { writeTo(it, value) }.toString()

    private fun writeTo(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> appendQuoted(sb, value)
            is Boolean -> sb.append(value)
            is Double -> sb.append(fmt(value))
            is Float -> sb.append(fmt(value.toDouble()))
            is Int, is Long, is Short, is Byte -> sb.append(value)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    appendQuoted(sb, k.toString())
                    sb.append(':')
                    writeTo(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, v)
                }
                sb.append(']')
            }
            is DoubleArray -> writeTo(sb, value.toList())
            is IntArray -> writeTo(sb, value.toList())
            is LongArray -> writeTo(sb, value.toList())
            else -> appendQuoted(sb, value.toString())
        }
    }

    private fun fmt(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "null"
        if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15) return v.toLong().toString()
        return String.format(java.util.Locale.US, "%.10g", v)
    }

    private fun appendQuoted(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    // ---- 解析 ----

    fun parse(text: String): Any? = Parser(text).let { p ->
        val v = p.parseValue()
        v
    }

    private class Parser(val s: String) {
        var i = 0

        fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("JSON 意外结束")
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> if (c == '-' || c.isDigit()) parseNumber() else throw IllegalArgumentException("非法字符 '$c' at $i")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (peek() == '}') { i++; return out }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(":")
                out[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> { i++; continue }
                    '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("对象缺少逗号 at $i")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (peek() == ']') { i++; return out }
            while (true) {
                out.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> { i++; continue }
                    ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("数组缺少逗号 at $i")
                }
            }
        }

        private fun parseString(): String {
            expect("\"")
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("字符串未结束")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> throw IllegalArgumentException("非法转义 \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Double {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(start, i).toDouble()
        }

        private fun literal(word: String) { expect(word) }

        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        fun expect(token: String) {
            if (!s.startsWith(token, i)) throw IllegalArgumentException("期望 '$token' at $i，实际 '${s.substring(i, minOf(s.length, i + 12))}'")
            i += token.length
        }
    }
}

// ---- 读取辅助 ----

@Suppress("UNCHECKED_CAST")
fun Any?.asMapOrNull(): Map<String, Any?>? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

fun Any?.asListOrEmpty(): List<Any?> = this as? List<Any?> ?: emptyList()

fun Map<String, Any?>.str(key: String, default: String = ""): String = this[key]?.toString() ?: default
fun Map<String, Any?>.numOrNull(key: String): Double? = when (val v = this[key]) {
    null -> null
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull()
    else -> null
}
fun Map<String, Any?>.num(key: String, default: Double = 0.0): Double = numOrNull(key) ?: default
fun Map<String, Any?>.int(key: String, default: Int = 0): Int = numOrNull(key)?.toInt() ?: default
fun Map<String, Any?>.long(key: String, default: Long = 0L): Long = numOrNull(key)?.toLong() ?: default
fun Map<String, Any?>.bool(key: String, default: Boolean = false): Boolean = this[key] as? Boolean ?: default
fun Map<String, Any?>.sub(key: String): Map<String, Any?> = (this[key] as? Map<String, Any?>) ?: emptyMap()
fun Map<String, Any?>.numList(key: String): List<Double> =
    (this[key] as? List<Any?>)?.mapNotNull { (it as? Number)?.toDouble() } ?: emptyList()
fun Map<String, Any?>.strList(key: String): List<String> =
    (this[key] as? List<Any?>)?.map { it.toString() } ?: emptyList()
