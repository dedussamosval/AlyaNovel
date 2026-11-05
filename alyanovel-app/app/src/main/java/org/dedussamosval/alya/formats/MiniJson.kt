package org.dedussamosval.alya.formats

/**
 * Крошечный JSON-парсер без зависимостей. Нужен для manifest.json внутри .mtx/.mpx и для ответов сервера.
 * Результат: Map<String, Any?> | List<Any?> | String | Long | Double | Boolean | null.
 * Слой форматов — чистый Kotlin/JVM: его можно тестировать без Android.
 *
 * Рудимент от Claude. Попросил его переписать formats.py в Kotlin
 * он зачем то написал к нему еще и парсер...
 */
class JsonException(message: String) : Exception(message)

object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) p.fail("лишние символы после JSON")
        return v
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun atEnd() = pos >= s.length
        fun fail(msg: String): Nothing = throw JsonException("JSON: $msg (позиция $pos)")

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun expect(c: Char) {
            if (pos >= s.length || s[pos] != c) fail("ожидался символ '$c'")
            pos++
        }

        fun readValue(): Any? {
            skipWs()
            if (atEnd()) fail("неожиданный конец")
            val c = s[pos]
            return when {
                c == '{' -> readObject()
                c == '[' -> readArray()
                c == '"' -> readString()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || c.isDigit() -> readNumber()
                else -> fail("неожиданный символ '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("ожидалось $word")
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                expect(':')
                map[key] = readValue()
                skipWs()
                if (pos >= s.length) fail("неожиданный конец объекта")
                val c = s[pos++]
                if (c == '}') return map
                if (c != ',') fail("ожидалась ',' или '}'")
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(readValue())
                skipWs()
                if (pos >= s.length) fail("неожиданный конец массива")
                val c = s[pos++]
                if (c == ']') return list
                if (c != ',') fail("ожидалась ',' или ']'")
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("строка не закрыта")
                val c = s[pos++]
                if (c == '"') return sb.toString()
                if (c != '\\') {
                    sb.append(c)
                    continue
                }
                if (pos >= s.length) fail("оборванный escape")
                when (val e = s[pos++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (pos + 4 > s.length) fail("короткая последовательность \\u")
                        val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: fail("неверный \\u")
                        sb.append(code.toChar()) // суррогатные пары склеятся сами
                        pos += 4
                    }
                    else -> fail("неизвестный escape \\$e")
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' ||
                    s[pos] == '+' || s[pos] == '-')
            ) pos++
            val t = s.substring(start, pos)
            val isFloat = t.any { it == '.' || it == 'e' || it == 'E' }
            return if (isFloat) {
                t.toDoubleOrNull() ?: fail("неверное число $t")
            } else {
                t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("неверное число $t")
            }
        }
    }
}

// ───── удобные аксессоры ─────
@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?> = this as? Map<String, Any?> ?: throw JsonException("JSON: ожидался объект")

@Suppress("UNCHECKED_CAST")
fun Any?.arr(): List<Any?> = this as? List<Any?> ?: throw JsonException("JSON: ожидался массив")

fun Map<String, Any?>.string(key: String, default: String = ""): String = (this[key] as? String) ?: default
fun Map<String, Any?>.long(key: String, default: Long = 0L): Long = (this[key] as? Number)?.toLong() ?: default
fun Map<String, Any?>.int(key: String, default: Int = 0): Int = (this[key] as? Number)?.toInt() ?: default

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.list(key: String): List<Any?> = (this[key] as? List<Any?>) ?: emptyList()
