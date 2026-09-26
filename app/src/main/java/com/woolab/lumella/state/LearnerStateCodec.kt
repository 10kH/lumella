package com.woolab.lumella.state

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON encoding of [LearnerState] for on-disk persistence.
 *
 * Why this exists: [LearnerStateStore] holds the learner's accumulated errors, recasts and
 * profile in an [java.util.concurrent.atomic.AtomicReference], so every app launch started
 * from a blank state and the tutor could not remember anything across sessions. Persisting
 * needs a stable wire form, and the app already speaks org.json everywhere else (RealtimeTools,
 * MainActivity), so this uses the same rather than adding a serialization dependency.
 *
 * Pure and dependency-free beyond org.json, so it is unit-testable on a plain JVM (the test
 * classpath carries a real org.json; production runs on the platform one).
 *
 * The [SCHEMA] field is written so a future shape change can be detected and the file
 * discarded rather than half-read. Unknown keys are ignored; missing keys fall back to the
 * data-class defaults, so adding a field later does not invalidate files already on disk.
 */
object LearnerStateCodec {
    const val SCHEMA = 1

    fun encode(state: LearnerState): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("revision", state.revision)
        put("profile", JSONObject().apply {
            put("l1", state.profile.l1)
            put("proficiencyEstimate", state.profile.proficiencyEstimate)
            put("goals", JSONArray(state.profile.goals))
        })
        put("grammarErrors", JSONArray().apply {
            state.grammarErrors.forEach { e ->
                put(JSONObject().apply {
                    put("span", e.span); put("type", e.type); put("recast", e.recast)
                    put("turnId", e.turnId); put("status", e.status.name)
                })
            }
        })
        put("pronFluency", JSONObject().apply {
            state.pronFluency.lastScore?.let { put("lastScore", it) }
            put("problemPhonemes", JSONArray(state.pronFluency.problemPhonemes))
            state.pronFluency.wpm?.let { put("wpm", it) }
            state.pronFluency.pauseRatio?.let { put("pauseRatio", it) }
        })
        put("vocabTargets", JSONArray().apply {
            state.vocabTargets.forEach { v ->
                put(JSONObject().apply {
                    put("word", v.word); put("context", v.context); put("introduced", v.introduced)
                })
            }
        })
        put("visualContext", JSONArray().apply {
            state.visualContext.forEach { v ->
                put(JSONObject().apply {
                    put("turnId", v.turnId); put("caption", v.caption)
                    put("groundedObjects", JSONArray(v.groundedObjects))
                })
            }
        })
        put("deferredCorrections", JSONArray().apply {
            state.deferredCorrections.forEach { c ->
                put(JSONObject().apply {
                    put("text", c.text); put("priority", c.priority); put("sourceAgent", c.sourceAgent)
                    put("turnId", c.turnId); put("age", c.age)
                })
            }
        })
        put("turnHistory", JSONArray().apply {
            state.turnHistory.forEach { t ->
                put(JSONObject().apply {
                    put("turnId", t.turnId); put("userTranscript", t.userTranscript)
                    put("ellaTranscript", t.ellaTranscript); put("imageAttached", t.imageAttached)
                })
            }
        })
        state.ruleGap?.let { put("ruleGap", it) }
        put("practiceTargets", JSONArray(state.practiceTargets))
        put("lastConsolidatedTurnId", state.lastConsolidatedTurnId)
    }.toString()

    /** Returns null when the text is not a state this codec wrote (wrong schema, malformed). */
    fun decode(text: String): LearnerState? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optInt("schema", -1) != SCHEMA) return null

        val profileJson = root.optJSONObject("profile")
        val profile = Profile(
            l1 = profileJson?.optString("l1", "ko") ?: "ko",
            proficiencyEstimate = profileJson?.optString("proficiencyEstimate", "unknown") ?: "unknown",
            goals = profileJson?.optJSONArray("goals").toStringList(),
        )

        val grammarErrors = root.optJSONArray("grammarErrors").mapObjects { o ->
            ErrorRecord(
                span = o.getString("span"),
                type = o.getString("type"),
                recast = o.getString("recast"),
                turnId = o.getInt("turnId"),
                status = runCatching { CorrectionStatus.valueOf(o.optString("status", "NEW")) }
                    .getOrDefault(CorrectionStatus.NEW),
            )
        }

        val pfJson = root.optJSONObject("pronFluency")
        val pronFluency = PronFluency(
            lastScore = pfJson?.takeIf { it.has("lastScore") }?.getDouble("lastScore"),
            problemPhonemes = pfJson?.optJSONArray("problemPhonemes").toStringList(),
            wpm = pfJson?.takeIf { it.has("wpm") }?.getDouble("wpm"),
            pauseRatio = pfJson?.takeIf { it.has("pauseRatio") }?.getDouble("pauseRatio"),
        )

        val vocabTargets = root.optJSONArray("vocabTargets").mapObjects { o ->
            VocabTarget(
                word = o.getString("word"),
                context = o.getString("context"),
                introduced = o.optBoolean("introduced", false),
            )
        }

        val visualContext = root.optJSONArray("visualContext").mapObjects { o ->
            VisualContextItem(
                turnId = o.getInt("turnId"),
                caption = o.getString("caption"),
                groundedObjects = o.optJSONArray("groundedObjects").toStringList(),
            )
        }

        val deferredCorrections = root.optJSONArray("deferredCorrections").mapObjects { o ->
            Correction(
                text = o.getString("text"),
                priority = o.getInt("priority"),
                sourceAgent = o.getString("sourceAgent"),
                turnId = o.getInt("turnId"),
                age = o.optInt("age", 0),
            )
        }

        val turnHistory = root.optJSONArray("turnHistory").mapObjects { o ->
            TurnRecord(
                turnId = o.getInt("turnId"),
                userTranscript = o.getString("userTranscript"),
                ellaTranscript = o.getString("ellaTranscript"),
                imageAttached = o.optBoolean("imageAttached", false),
            )
        }

        return LearnerState(
            profile = profile,
            grammarErrors = grammarErrors,
            pronFluency = pronFluency,
            vocabTargets = vocabTargets,
            visualContext = visualContext,
            deferredCorrections = deferredCorrections,
            turnHistory = turnHistory,
            ruleGap = root.takeIf { it.has("ruleGap") && !it.isNull("ruleGap") }?.getString("ruleGap"),
            practiceTargets = root.optJSONArray("practiceTargets").toStringList(),
            lastConsolidatedTurnId = root.optInt("lastConsolidatedTurnId", 0),
            revision = root.optInt("revision", 0),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return List(length()) { i -> getString(i) }
    }

    private fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            runCatching { f(o) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }
}
