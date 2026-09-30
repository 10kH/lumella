package com.woolab.lumella.voice

/**
 * Topic-guided conversation (주제 유도형 자유대화) on the glasses — the booth panel's Chat skill.
 *
 * The session has a topic (a learning goal the learner is not lectured about). luma routes the
 * learner's on-topic turns to its topic tutor, ETRI Tango, prompted with that topic and the running
 * conversation; Tango's reply is its next line for the topic. The voice the learner hears stays the
 * realtime model (it answers in ~0.7 s; a coach turn takes seconds), so Tango leads one turn behind:
 * its line for turn N sets the direction of the voice's reply at turn N+1. Off-topic turns go to
 * GPT and carry no guide; the voice then brings the talk back to the topic on its own.
 *
 * Recast stays first: the persona's recast rule is not replaced, the topic only decides where the
 * reply goes after it.
 */
object TopicGuidance {
    /** A guide older than this many turns describes a conversation that has moved on. */
    const val MAX_GUIDE_AGE_TURNS = 2

    fun topicInstruction(topic: String): String =
        "TOPIC-GUIDED CONVERSATION. Today's topic: \"$topic\". Keep the conversation on this topic " +
            "without announcing it as a lesson: when there is room, ask one short question about it. If the " +
            "learner asks about something else, ANSWER IT first in one short, useful sentence — never refuse, " +
            "postpone or wave it away — and then bring the talk back to the topic with one question. Your recast " +
            "of the learner's sentence still comes first, and the whole reply stays 1-2 short sentences."

    fun guideInstruction(guide: String): String =
        "The topic tutor (ETRI Tango) proposed this next line for the topic: \"$guide\". After your recast, " +
            "take the conversation in that direction — use its question, in your own short words and in the " +
            "learner's politeness level. Do not read it out word for word. If the learner has just asked about " +
            "something else, answer that first; use this direction only to come back to the topic."

    /** The guide to use at [currentTurnId], or null when there is none or it is stale. */
    fun freshGuide(guide: String?, sourceTurnId: Int, currentTurnId: Int): String? =
        guide?.takeIf { it.isNotBlank() && currentTurnId - sourceTurnId in 1..MAX_GUIDE_AGE_TURNS }
}
