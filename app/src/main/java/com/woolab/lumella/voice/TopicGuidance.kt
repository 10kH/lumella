package com.woolab.lumella.voice

import java.io.File

/**
 * lumella is organised around a conversation topic (주제 유도형 자유대화 — the booth panel's Chat
 * skill). Every conversation starts on one:
 *
 * - No topic yet: the tutor speaks first and asks what to talk about ("오늘은 어떤 얘기할까요?"),
 *   offering the topic of last time and the learner's favourites (luma profile) as examples. The
 *   learner's answer sets it by voice (the set_topic tool); "주제 없이" means free talk.
 * - A topic already set (an operator's `--topic`): the tutor opens with a question on it.
 * - Back after a break (the session idled out and was woken): the tutor asks whether to keep the
 *   topic or talk about something else.
 *
 * During the conversation luma routes on-topic turns to its topic tutor, ETRI Tango, prompted with
 * the topic and the running conversation; Tango's reply is its next line for the topic. The voice
 * the learner hears stays the realtime model (it answers in ~0.7 s; a coach turn takes seconds), so
 * Tango leads one turn behind: its line for turn N sets the direction of the voice's reply at turn
 * N+1. Off-topic turns go to GPT and carry no guide; the voice answers them and comes back.
 *
 * Recast stays first: the persona's recast rule is not replaced, the topic only decides where the
 * reply goes after it.
 */
object TopicGuidance {
    /** A guide older than this many turns describes a conversation that has moved on. */
    const val MAX_GUIDE_AGE_TURNS = 2

    const val MAX_TOPIC_CHARS = 30

    fun topicInstruction(topic: String): String =
        "TOPIC-GUIDED CONVERSATION. Today's topic: \"$topic\". Keep the conversation on this topic " +
            "without announcing it as a lesson: when there is room, ask one short question about it. If the " +
            "learner asks about something else, ANSWER IT first in one short, useful sentence — never refuse, " +
            "postpone or wave it away — and then bring the talk back to the topic with one question. Your recast " +
            "or expansion of the learner's sentence still comes first — the topic question never replaces it — " +
            "and the whole reply stays 1-2 short sentences."

    fun guideInstruction(guide: String): String =
        "The topic tutor (ETRI Tango) proposed this next line for the topic: \"$guide\". After your recast or " +
            "expansion, " +
            "take the conversation in that direction — use its question, in your own short words and in the " +
            "learner's politeness level. Do not read it out word for word. If the learner has just asked about " +
            "something else, answer that first; use this direction only to come back to the topic."

    /** How a conversation opens; each has its own first words (see [openingInstruction]). */
    enum class Opening { CHOOSE, ON_TOPIC, WELCOME_BACK }

    fun openingKind(topic: String?, returning: Boolean): Opening = when {
        topic == null -> Opening.CHOOSE
        returning -> Opening.WELCOME_BACK
        else -> Opening.ON_TOPIC
    }

    // Templates, one per opening. {examples} and {topic} are filled in; aaai27 opener_probe.py reads
    // these literals to measure exactly what the app sends.
    const val OPENING_CHOOSE =
        "OPENING (the learner has not spoken yet). In one or two short sentences in 해요체, greet the learner " +
            "with \"안녕하세요\" and ask what they would like to talk about today — \"오늘은 어떤 얘기할까요?\" — offering these as " +
            "examples: {examples} — every one said in natural Korean, never an English word (\"travel\" is 여행, " +
            "\"daily life\" is 일상). Do not call any tool now."
    const val OPENING_CHOOSE_WITH_LAST =
        " The first example is what you talked about last time: offer to continue it " +
            "(\"지난번에 이야기한 … 이어서 해도 좋고요\")."
    const val OPENING_EVERYDAY_EXAMPLES = "two everyday topics such as 어제 한 일, 좋아하는 음식"
    const val OPENING_ON_TOPIC =
        "OPENING (the learner has not spoken yet). Today's topic is \"{topic}\". In one or two short sentences " +
            "in 해요체, greet the learner with \"안녕하세요\", name today's topic in natural Korean, and ask one short, easy question " +
            "about it. Do not call any tool now."
    const val OPENING_WELCOME_BACK =
        "OPENING (the learner is back after a pause; they have not spoken yet). In one short sentence in 해요체, " +
            "greet them and ask whether to keep talking about \"{topic}\" or to talk about something else. " +
            "Do not call any tool now."

    /** What happened, as a system note put into the conversation before the opening response. */
    fun openingNote(kind: Opening): String = when (kind) {
        Opening.WELCOME_BACK -> "The learner is back after a pause. Open the conversation."
        else -> "The learner has just put the glasses on. Open the conversation."
    }

    /** The tutor's first words of a conversation (the learner has not spoken; politeness is 해요체). */
    fun openingInstruction(
        topic: String?,
        returning: Boolean,
        lastTopic: String?,
        favorites: List<String>,
    ): String = when (openingKind(topic, returning)) {
        Opening.ON_TOPIC -> OPENING_ON_TOPIC.replace("{topic}", topic!!)
        Opening.WELCOME_BACK -> OPENING_WELCOME_BACK.replace("{topic}", topic!!)
        Opening.CHOOSE -> {
            val last = lastTopic?.trim()?.takeIf { it.isNotEmpty() }
            val examples = (listOfNotNull(last) + favorites.map { it.trim() })
                .filter { it.isNotEmpty() }.distinct().take(2)
            val text = if (examples.isEmpty()) OPENING_EVERYDAY_EXAMPLES else examples.joinToString(", ") { "\"$it\"" }
            OPENING_CHOOSE.replace("{examples}", text) + if (last != null) OPENING_CHOOSE_WITH_LAST else ""
        }
    }

    /** A spoken or file topic, tidied: one line, at most [MAX_TOPIC_CHARS]; blank means none. */
    fun normalizeTopic(raw: String?): String? =
        raw?.lineSequence()?.firstOrNull()?.trim()?.trim('"', '\'', '“', '”')?.take(MAX_TOPIC_CHARS)
            ?.takeIf { it.isNotEmpty() }

    /** The guide to use at [currentTurnId], or null when there is none or it is stale. */
    fun freshGuide(guide: String?, sourceTurnId: Int, currentTurnId: Int): String? =
        guide?.takeIf { it.isNotBlank() && currentTurnId - sourceTurnId in 1..MAX_GUIDE_AGE_TURNS }
}

/**
 * The topics the learner chose by voice, newest last, as "epochMillis<TAB>topic" lines — so the next
 * conversation can offer to continue the last one. Operator presets are not remembered (they are not
 * the learner's choice). ops/launch-lumella.sh --reset deletes the file with the learner record.
 */
class TopicMemory(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun remember(topic: String) {
        val kept = entries().filter { it.second != topic } + (now() to topic)
        runCatching {
            file.writeText(kept.takeLast(MAX_ENTRIES).joinToString("") { "${it.first}\t${it.second}\n" })
        }
    }

    /** The last chosen topic, if chosen within [maxAgeMs]. */
    fun last(maxAgeMs: Long = DEFAULT_MAX_AGE_MS): String? =
        entries().lastOrNull()?.takeIf { now() - it.first in 0..maxAgeMs }?.second

    private fun entries(): List<Pair<Long, String>> =
        runCatching { if (file.isFile) file.readLines() else emptyList() }.getOrDefault(emptyList())
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val at = line.substring(0, tab).toLongOrNull() ?: return@mapNotNull null
                TopicGuidance.normalizeTopic(line.substring(tab + 1))?.let { at to it }
            }

    companion object {
        const val MAX_ENTRIES = 5
        const val DEFAULT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
