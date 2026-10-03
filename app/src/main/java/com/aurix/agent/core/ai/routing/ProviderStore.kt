package com.aurix.agent.core.ai.routing

import com.aurix.agent.core.security.SecureSettings
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Providers + keys, stored encrypted (Keystore-backed). Keys never reach logs, prompts or UI (only last 4 chars). */
@Singleton
class ProviderStore @Inject constructor(private val secure: SecureSettings) {
    @Volatile private var cache: List<ProviderEntry>? = null
    @Volatile private var policyCache: RoutingPolicy? = null

    @Synchronized fun providers(): List<ProviderEntry> {
        cache?.let { return it }
        val raw = secure.getString(K_PROVIDERS)
        val list = if (raw != null) ProviderCodec.decode(raw) else migrateLegacy()
        cache = list
        return list
    }

    @Synchronized fun save(list: List<ProviderEntry>) {
        secure.putString(K_PROVIDERS, ProviderCodec.encode(list))
        cache = list
    }

    fun policy(): RoutingPolicy {
        policyCache?.let { return it }
        val p = try { RoutingPolicy.valueOf(secure.getString(K_POLICY) ?: "") } catch (e: Exception) { RoutingPolicy.QUALITY }
        policyCache = p
        return p
    }

    fun setPolicy(p: RoutingPolicy) { secure.putString(K_POLICY, p.name); policyCache = p }

    /** Phase 1 stored a single key; carry it over so the user does not have to re-enter it. */
    private fun migrateLegacy(): List<ProviderEntry> {
        val legacy = secure.load() ?: return emptyList()
        val list = listOf(
            ProviderEntry(
                id = UUID.randomUUID().toString(), type = ProviderType.OPENAI_COMPATIBLE, name = "Imported",
                baseUrl = legacy.baseUrl, fastModel = legacy.model, strongModel = legacy.model,
                keys = listOf(KeyEntry(UUID.randomUUID().toString(), "Key 1", legacy.apiKey)),
            )
        )
        save(list)
        return list
    }

    private companion object {
        const val K_PROVIDERS = "providers_v1"
        const val K_POLICY = "routing_policy"
    }
}
