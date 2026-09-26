package com.woolab.tutor.slowpath

import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointPedagogyAgentClientTest {
    @Test
    fun everyRoleTellsTheServerThisIsTheKoreanTutor() {
        // The server (ELLA api/pedagogy-agent.js dcce79d) selects its prompt table by
        // payload.language and defaults to "en" when it is missing. A body without it would
        // be analysed as English and answer a Korean particle slip with nothing. This is the
        // only place lumella says which language it is; it must say so for every role.
        val task = SlowPathTask(turnId = 1, userTranscript = "어제 친구가 만났어요", imageBase64 = "img")
        for (role in listOf("grammar", "pronunciation", "visual", "consolidate")) {
            val body = buildRequestJson(TutorLanguage.KOREAN, role, task)
            assertTrue("$role body must carry language:ko — $body", body.contains("\"language\":\"ko\""))
            assertTrue("$role body must carry the role", body.contains("\"role\":\"$role\""))
        }
    }
}
