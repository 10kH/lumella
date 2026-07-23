package com.woolab.lumella.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LumaJsonTest {

    @Test
    fun `parses nested object with mixed value types`() {
        val value = LumaJsonParser.parse(
            """{"a": 1, "b": "text", "c": true, "d": null, "e": [1, "x", false], "f": {"g": 2.5}}""",
        )
        val obj = value as LumaJson.Obj
        assertEquals(1.0, obj.num("a"))
        assertEquals("text", obj.str("b"))
        assertEquals(true, obj.bool("c"))
        assertTrue(obj.fields["d"] is LumaJson.Null)
        assertEquals(3, obj.arr("e")?.size)
        assertEquals(2.5, obj.obj("f")?.num("g"))
    }

    @Test
    fun `unknown fields are simply absent from accessors, not errors`() {
        val obj = LumaJsonParser.parse("""{"known": "value", "unknownFutureField": {"nested": [1,2,3]}}""") as LumaJson.Obj
        assertEquals("value", obj.str("known"))
        assertNull(obj.str("doesNotExist"))
    }

    @Test
    fun `missing optional fields default via accessor nullability`() {
        val obj = LumaJsonParser.parse("""{"onlyField": "x"}""") as LumaJson.Obj
        assertNull(obj.str("focusHint"))
        assertNull(obj.num("confidence"))
        assertEquals(emptyList<LumaJson>(), obj.arr("hints") ?: emptyList())
    }

    @Test
    fun `malformed JSON throws LumaJsonParseException`() {
        var threw = false
        try {
            LumaJsonParser.parse("""{"a": 1,""")
        } catch (e: LumaJsonParseException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `parseOrNull returns null instead of throwing on malformed input`() {
        assertNull(LumaJsonParser.parseOrNull("not json at all"))
        assertNull(LumaJsonParser.parseOrNull("{unterminated"))
    }

    @Test
    fun `writer round-trips through parser`() {
        val original = jsonObj(
            "s" to jsonStr("hello \"world\""),
            "n" to LumaJson.Num(42.0),
            "arr" to jsonArrOfStr(listOf("a", "b")),
            "nested" to jsonObj("flag" to LumaJson.Bool(false)),
        )
        val text = LumaJsonWriter.write(original)
        val reparsed = LumaJsonParser.parse(text) as LumaJson.Obj
        assertEquals("hello \"world\"", reparsed.str("s"))
        assertEquals(42.0, reparsed.num("n"))
        assertEquals(listOf("a", "b"), reparsed.arr("arr")?.let { LumaJson.Arr(it).strings() })
        assertFalse(reparsed.obj("nested")?.bool("flag") ?: true)
    }
}
