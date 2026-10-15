package com.aurix.agent.core.guard

import com.aurix.agent.core.tools.device.CalendarParse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class GuardsTest {
    private fun v(url: String, tainted: Boolean = false, blocked: Set<String> = emptySet()) = Sentinel.check(url, tainted, blocked).verdict

    @Test fun sentinelLetsNormalSitesThroughSilently() {
        assertEquals(WebVerdict.ALLOW, v("https://en.wikipedia.org/wiki/Delhi"))
        assertEquals(WebVerdict.ALLOW, v("https://www.example.co.in/a?b=c"))
    }

    @Test fun sentinelBlocksDangerousTargets() {
        for (u in listOf("file:///etc/passwd", "ftp://x.com", "http://localhost:8080", "https://192.168.1.1/admin", "https://[::1]/", "https://user:pw@site.com", "javascript:alert(1)", "not a url", "https://printer.local/"))
            assertEquals(u, WebVerdict.BLOCK, v(u))
        assertEquals(WebVerdict.BLOCK, v("https://sub.evil.com/x", blocked = setOf("evil.com")))
        assertEquals(WebVerdict.ALLOW, v("https://notevil.com/x", blocked = setOf("evil.com")))
    }

    @Test fun sentinelAsksForRiskyButNotForbiddenLinks() {
        assertEquals(WebVerdict.ASK, v("https://bit.ly/abc"))
        assertEquals(WebVerdict.ASK, v("https://xn--pple-43d.com/"))
        assertEquals(WebVerdict.ASK, v("http://example.com/"))
        assertEquals(WebVerdict.ASK, v("https://example.com/", tainted = true))
    }

    @Test fun vaultSubstitutesOnlyKnownNamesAndEscapesJson() {
        val inp = JSONObject().put("text", "pw={{vault:GMAIL}} other={{vault:NOPE}}")
        val out = VaultCodec.substitute(inp, mapOf("GMAIL" to "p\"a\\ss/word"))
        assertEquals("pw=p\"a\\ss/word other={{vault:NOPE}}", out.getString("text"))
        assertEquals(listOf("GMAIL", "NOPE"), VaultCodec.tokens(inp))
        assertTrue(VaultCodec.hasToken(inp)); assertFalse(VaultCodec.hasToken(JSONObject().put("text", "plain")))
    }

    @Test fun vaultRedactsEchoedSecretsAndOnlyScreenTypeMayUseThem() {
        assertEquals("typed {{vault:GMAIL}} ok", VaultCodec.redact("typed hunter22 ok", mapOf("GMAIL" to "hunter22")))
        assertEquals("abc", VaultCodec.redact("abc", mapOf("X" to "abc"))) // too short to redact safely: 3 chars
        assertEquals(setOf("SCREEN_TYPE"), VaultCodec.SECRET_TOOLS)
        assertEquals("MY_KEY", VaultCodec.normalize("my key")); assertNull(VaultCodec.normalize("bad;name"))
    }

    @Test fun connectorGroupsCoverTheMessagingAndControlTools() {
        assertEquals("WhatsApp", ConnectorPolicy.groupOf("WHATSAPP_MESSAGE"))
        assertEquals("Messages & calls", ConnectorPolicy.groupOf("CALL_PHONE"))
        assertEquals("Screen control", ConnectorPolicy.groupOf("SCREEN_TAP"))
        assertEquals("Notifications", ConnectorPolicy.groupOf("CLEAR_NOTIFICATIONS"))
        assertEquals("Calendar", ConnectorPolicy.groupOf("CALENDAR_ADD"))
        assertNull(ConnectorPolicy.groupOf("CALCULATOR"))
        assertTrue(ConnectorPolicy.GROUPS.containsAll(listOf("WhatsApp", "Web", "Calendar")))
    }

    @Test fun calendarDatesParseInThePhoneZone() {
        val z = ZoneId.of("Asia/Kolkata")
        assertNotNull(CalendarParse.parse("2026-10-20T15:00", z))
        assertEquals(CalendarParse.parse("2026-10-20T15:00", z), CalendarParse.parse("2026-10-20 15:00", z))
        assertEquals(19_800_000L, CalendarParse.parse("1970-01-01T00:00", z)!! * -1)
        assertNull(CalendarParse.parse("tomorrow", z))
    }
}
