package com.aurix.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HindiAndMediaTest {
    @Test fun hindiNormalized() {
        assertEquals("flashlight on karo", normalizeHindi("फ्लैशलाइट चालू करो"))
        assertEquals("alarm 7", normalizeHindi("अलार्म ७"))
        assertEquals("open youtube", normalizeHindi("open youtube"))
    }
    @Test fun newCommands() {
        assertEquals("EXPLAIN_LAST", IntentRouter.route("kya hua")!!.tool)
        assertEquals("CAMERA_OPEN", IntentRouter.route("open camera")!!.tool)
        assertEquals("PHOTOS", IntentRouter.route("photos kholo")!!.tool)
        assertEquals("pharmacy", IntentRouter.route("nearby pharmacy")!!.input.getString("query"))
        assertEquals("PLACE_GO", IntentRouter.route("navigate to home")!!.tool)
        assertNotNull(IntentRouter.route("फ्लैशलाइट चालू करो"))
    }
}
