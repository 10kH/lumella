package com.woolab.lumella.agents

import com.woolab.lumella.slowpath.SlowPathTask

/**
 * What the slow path talks to.
 *
 * One implementation in production: [EndpointPedagogyAgentClient], the Vercel pedagogy
 * function. The luma brain used to sit behind this interface too, through an adapter that
 * re-shaped its coach steering into role-scoped chat-completion JSON so the dispatcher could
 * parse it the same way — and every role the brain had no field for came back as "{}". The
 * brain is not a per-turn grammar analyser and was never going to be; it serves the fast path
 * (VoiceFastPath.fetchSteering), the bottom coach hint, and photo upload, none of which is the
 * slow path. Removed 2026-09-26.
 */
interface PedagogyAgentClient {
    fun analyze(role: String, task: SlowPathTask, callback: (Result<String>) -> Unit)
}
