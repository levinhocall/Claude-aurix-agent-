package com.aurix.agent.core.agent

data class AgentLimits(
    val maxIterations: Int = 120,              // model calls per mission
    val maxMissionMillis: Long = 20 * 60_000L, // per run (resets on resume)
    val maxTokens: Int = 400_000,              // per-mission budget
    val maxToolCallsPerStep: Int = 16,
    val maxStepAttempts: Int = 2,
    val maxReplans: Int = 3,
    val maxVerifyRounds: Int = 2,
    val maxPlanAttempts: Int = 2,
    val loopRepeatThreshold: Int = 3,
)
