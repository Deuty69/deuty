package com.deuty.agent.core.model

/**
 * Risk tiers drive whether the agent may act on its own (requirement 14).
 *
 * The mapping is intentionally conservative: when classification is uncertain
 * the system escalates, never de-escalates.
 */
enum class RiskLevel(val rank: Int) {
    /** Navigation, reading, searching, copying, renaming, moving. Auto-allowed. */
    LOW(0),

    /** Sending, posting, submitting, installing, changing settings. Confirmable. */
    MEDIUM(1),

    /** Payments, credentials, deletions, account/security changes. Always confirm. */
    HIGH(2),

    /**
     * The agent refuses to act at all and hands the step to the user: system
     * permission dialogs, payment sheets, FLAG_SECURE screens, banking apps.
     */
    FORBIDDEN(3);

    fun atLeast(other: RiskLevel): Boolean = rank >= other.rank

    companion object {
        fun fromRank(rank: Int): RiskLevel = entries.firstOrNull { it.rank == rank } ?: HIGH
    }
}
