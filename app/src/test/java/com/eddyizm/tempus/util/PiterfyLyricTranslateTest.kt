package com.eddyizm.tempus.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyLyricTranslateTest {
    @Test fun requestBodyKeepsEveryLineInOrderIncludingBlankOnes() {
        val body = JsonParser.parseString(PiterfyLyricTranslate.requestBody("song-1", "pl", listOf(" Hola ", "", "adiós"))).asJsonObject
        assertEquals("song-1", body.get("song").asString)
        assertEquals("pl", body.get("target").asString)
        assertEquals(listOf("Hola", "", "adiós"), body.getAsJsonArray("lines").map { it.asString })
    }

    @Test fun parseReturnsTheTranslatedLines() {
        val result = PiterfyLyricTranslate.parse(200, """{"lines":["Cześć"," ",null],"source":"es","backend":"mymemory","cached":false}""", 3)
        assertTrue(result is PiterfyLyricTranslate.Result.Ok)
        assertEquals(listOf("Cześć", "", ""), (result as PiterfyLyricTranslate.Result.Ok).lines)
    }

    @Test fun parseRecognisesSkippedLyrics() {
        assertTrue(PiterfyLyricTranslate.parse(200, """{"skipped":true,"reason":"same-language"}""", 2) is PiterfyLyricTranslate.Result.Skipped)
    }

    @Test fun missingServiceIsUnavailableAndOtherStatusesFail() {
        assertTrue(PiterfyLyricTranslate.parse(503, "{}", 1) is PiterfyLyricTranslate.Result.Unavailable)
        assertTrue(PiterfyLyricTranslate.parse(404, "", 1) is PiterfyLyricTranslate.Result.Unavailable)
        assertTrue(PiterfyLyricTranslate.parse(500, "{}", 1) is PiterfyLyricTranslate.Result.Failed)
        assertTrue(PiterfyLyricTranslate.parse(401, "{}", 1) is PiterfyLyricTranslate.Result.Failed)
    }

    @Test fun malformedOrMismatchedBodiesFail() {
        assertTrue(PiterfyLyricTranslate.parse(200, "not json", 1) is PiterfyLyricTranslate.Result.Failed)
        assertTrue(PiterfyLyricTranslate.parse(200, """{"lines":["a"]}""", 2) is PiterfyLyricTranslate.Result.Failed)
        assertTrue(PiterfyLyricTranslate.parse(200, """{"nothing":1}""", 1) is PiterfyLyricTranslate.Result.Failed)
        assertTrue(PiterfyLyricTranslate.parse(200, null, 1) is PiterfyLyricTranslate.Result.Failed)
    }
}
