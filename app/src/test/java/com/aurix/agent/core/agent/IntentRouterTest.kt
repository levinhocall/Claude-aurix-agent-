package com.aurix.agent.core.agent

import com.aurix.agent.core.net.isLocalNetworkHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentRouterTest {
    private fun r(s: String, hour: Int = 10) = IntentRouter.route(s, hour)

    @Test fun flashlight() {
        assertEquals(true, r("flashlight on")!!.input.getBoolean("on"))
        assertEquals(false, r("torch band karo")!!.input.getBoolean("on"))
        assertEquals("FLASHLIGHT", r("Flashlight chalu karo")!!.tool)
        assertEquals(false, r("flashlight off")!!.input.getBoolean("on"))
    }

    @Test fun alarms() {
        val a = r("mujhe kal 8 baje alarm laga")!!
        assertEquals("SET_ALARM", a.tool); assertEquals(8, a.input.getInt("hour")); assertEquals(0, a.input.getInt("minute"))
        val b = r("set alarm for 6:30 pm")!!
        assertEquals(18, b.input.getInt("hour")); assertEquals(30, b.input.getInt("minute"))
        assertEquals(7, r("alarm 7 am")!!.input.getInt("hour"))
        assertEquals(21, r("raat 9 baje alarm lagao")!!.input.getInt("hour"))
        assertEquals(15, r("alarm 3 baje", hour = 10)!!.input.getInt("hour"))
        assertEquals(3, r("alarm 3 baje", hour = 1)!!.input.getInt("hour"))
        assertEquals(7, r("saat baje alarm", hour = 3)!!.input.getInt("hour"))
        assertNull(r("alarm"))
        assertNull(r("cancel alarm 7 am"))
    }

    @Test fun timers() {
        assertEquals(300, r("5 minute ka timer laga")!!.input.getInt("seconds"))
        assertEquals(30, r("timer 30 seconds")!!.input.getInt("seconds"))
        assertEquals(3600, r("set a timer for 1 hour")!!.input.getInt("seconds"))
        assertEquals("SET_TIMER", r("alarm in 10 minutes")!!.tool)
    }

    @Test fun volume() {
        assertEquals(40, r("volume 40%")!!.input.getInt("level"))
        assertEquals(0, r("mute")!!.input.getInt("level"))
        assertEquals(15, r("volume badhao")!!.input.getInt("delta"))
        assertEquals(-15, r("volume kam karo")!!.input.getInt("delta"))
        assertEquals(100, r("volume full")!!.input.getInt("level"))
    }

    @Test fun mediaAndMusic() {
        assertEquals("pause", r("pause")!!.input.getString("action"))
        assertEquals("next", r("next song")!!.input.getString("action"))
        assertEquals("next", r("play next song")!!.input.getString("action"))
        assertEquals("previous", r("pichla gaana")!!.input.getString("action"))
        assertEquals("PLAY_MUSIC", r("play Arijit Singh")!!.tool)
        assertEquals("arijit singh", r("play Arijit Singh")!!.input.getString("query"))
        assertEquals("tum hi ho", r("tum hi ho bajao")!!.input.getString("query"))
        assertEquals("arijit singh", r("play arijit singh on youtube")!!.input.getString("query"))
        assertNull(r("play store"))
    }

    @Test fun appsAndNavigation() {
        assertEquals("youtube", r("open youtube")!!.input.getString("name"))
        assertEquals("whatsapp", r("whatsapp kholo")!!.input.getString("name"))
        assertEquals("OPEN_APP", r("chrome khol do")!!.tool)
        assertEquals("pune station", r("navigate to pune station")!!.input.getString("destination"))
        assertEquals("ghar", r("ghar ka rasta batao")!!.input.getString("destination"))
        assertEquals("CALL_PHONE", r("call 98765 43210")!!.tool)
        assertEquals("9876543210", r("call 98765 43210")!!.input.getString("number"))
    }

    @Test fun deviceAndScreen() {
        assertEquals("BATTERY_INFO", r("battery kitni hai")!!.tool)
        assertEquals("MY_LOCATION", r("where am i")!!.tool)
        assertEquals("STORAGE_INFO", r("storage kitni bachi hai")!!.tool)
        assertEquals("back", r("go back")!!.input.getString("key"))
        assertEquals("home", r("home screen")!!.input.getString("key"))
        assertEquals("screenshot", r("take screenshot")!!.input.getString("key"))
        assertEquals("down", r("scroll down")!!.input.getString("direction"))
        assertEquals("up", r("upar scroll karo")!!.input.getString("direction"))
    }

    @Test fun multiStepAndUnknownGoToTheAi() {
        assertNull(r("open youtube and play arijit singh"))
        assertNull(r("whatsapp kholo aur rahul ko message bhejo"))
        assertNull(r("research accounting jobs in mumbai and save a report"))
        assertNull(r("mom ko message bhejo ki late aaunga"))
        assertNull(r("what is the capital of france"))
        assertNull(r(""))
        assertNotNull(r("flashlight on"))
    }

    @Test fun cleartextOnlyForLocalHosts() {
        assertTrue(isLocalNetworkHost("192.168.1.20"))
        assertTrue(isLocalNetworkHost("10.0.0.5"))
        assertTrue(isLocalNetworkHost("localhost"))
        assertTrue(isLocalNetworkHost("mypc.local"))
        assertTrue(isLocalNetworkHost("100.101.102.103"))
        assertFalse(isLocalNetworkHost("8.8.8.8"))
        assertFalse(isLocalNetworkHost("api.openai.com"))
    }
}
