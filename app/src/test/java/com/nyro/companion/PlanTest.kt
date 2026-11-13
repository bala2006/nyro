package com.nyro.companion

import com.nyro.companion.exec.Condition
import com.nyro.companion.exec.MatchMode
import com.nyro.companion.exec.PlanAction
import com.nyro.companion.exec.PlanLimits
import com.nyro.companion.exec.PlanParser
import com.nyro.companion.exec.PlanValidator
import com.nyro.companion.exec.ToolSpec
import com.nyro.companion.tools.Args
import com.nyro.companion.tools.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTest {
    private val registry = ToolRegistry()
    /** Validates against the REAL tool schemas, so a plan step cannot pass here and fail on the phone. */
    private val spec = ToolSpec { tool, args ->
        val t = registry.get(tool) ?: return@ToolSpec "Unknown tool '$tool'."
        t.validate(Args(args)) // null means valid
    }

    private fun parse(json: String) = PlanParser.parse(Json.parseToJsonElement(json).jsonObject)
    private fun errors(json: String): List<String> {
        val r = parse(json)
        return r.errors.ifEmpty { PlanValidator.validate(r.plan!!, spec) }
    }

    @Test fun aValidPlanParsesAndValidates() {
        val r = parse("""{"goal":"search cats","steps":[
          {"do":"open_app","app":"YouTube","expect":[{"app":"YouTube"}]},
          {"do":"tap","target":{"label":"Search","role":"Button"},"expect":[{"visible":{"role":"Input","editable":true}}]},
          {"do":"type_text","text":"cats","submit":true,"expect":[{"changed":true}]}]}""")
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        val plan = r.plan!!
        assertEquals(3, plan.steps.size)
        assertEquals(PlanAction.OPEN_APP, plan.steps[0].action)
        assertEquals("Search", plan.steps[1].target!!.label)
        assertTrue(plan.steps[0].expect.single() is Condition.App)
        assertEquals(emptyList<String>(), PlanValidator.validate(plan, spec))
    }

    @Test fun selectorsSupportMatchModesAndIndex() {
        val s = parse("""{"steps":[{"do":"tap","target":{"label":"Follow","match":"exact","index":2}}]}""").plan!!.steps[0].target!!
        assertEquals(MatchMode.EXACT, s.mode); assertEquals(2, s.index)
    }

    @Test fun idsAndCoordinatesAreNotPlanActions() {
        val e = errors("""{"steps":[{"do":"tap_point","x":10,"y":20}]}""")
        assertTrue(e.toString(), e.single().contains("'do' must be one of"))
        // A tap with no semantic target is refused.
        val e2 = errors("""{"steps":[{"do":"tap"}]}""")
        assertTrue(e2.toString(), e2.any { it.contains("Ids and coordinates are not allowed") })
        val e3 = errors("""{"steps":[{"do":"tap","target":{"role":"Button"}}]}""")
        assertTrue(e3.toString(), e3.any { it.contains("label, text, desc or view_id") })
    }

    @Test fun sensitiveToolsCannotBeBatched() {
        for (tool in listOf("reply_to_notification", "send_whatsapp_message", "lock_screen", "share_text", "forget", "take_screenshot")) {
            val e = errors("""{"steps":[{"do":"$tool"}]}""")
            assertTrue("$tool must not be allowed in a plan: $e", e.single().contains("'do' must be one of"))
        }
    }

    @Test fun stepCountIsLimited() {
        val steps = (1..PlanLimits.MAX_STEPS + 1).joinToString(",") { """{"do":"scroll","direction":"down"}""" }
        assertTrue(errors("""{"steps":[$steps]}""").any { it.contains("At most ${PlanLimits.MAX_STEPS} steps") })
        assertTrue(errors("""{"steps":[]}""").any { it.contains("no steps") })
    }

    @Test fun toolArgumentsAreCheckedAgainstTheToolSchema() {
        assertTrue(errors("""{"steps":[{"do":"scroll","direction":"sideways"}]}""").any { it.contains("direction") })
        assertTrue(errors("""{"steps":[{"do":"open_app"}]}""").any { it.contains("'app' is required") })
        assertTrue(errors("""{"steps":[{"do":"open_settings","page":"not_a_page"}]}""").any { it.contains("page") })
        assertTrue(errors("""{"steps":[{"do":"open_settings","page":"wifi"}]}""").isEmpty())
    }

    @Test fun repeatingTheSameStepIsRejected() {
        val e = errors("""{"steps":[
          {"do":"tap","target":{"label":"Section 2"}},
          {"do":"tap","target":{"label":"Section 2"}}]}""")
        assertTrue(e.toString(), e.any { it.contains("repeats step 1") })
        // Scrolling twice is legitimate.
        assertTrue(errors("""{"steps":[{"do":"scroll","direction":"down"},{"do":"scroll","direction":"down"}]}""").isEmpty())
    }

    @Test fun backPressChainsAreLimited() {
        val e = errors("""{"steps":[{"do":"press_back"},{"do":"press_back"},{"do":"press_back"},{"do":"press_back"}]}""")
        assertTrue(e.toString(), e.any { it.contains("back presses in a row") })
    }

    @Test fun malformedConditionsAreReported() {
        val e = errors("""{"steps":[{"do":"tap","target":{"label":"Go"},"expect":[{"colour":"red"}]}]}""")
        assertTrue(e.toString(), e.any { it.contains("expect[1] needs one of") })
        assertNull(parse("""{"steps":"nope"}""").plan)
        assertTrue(parse("""{"steps":[1]}""").errors.single().contains("must be an object"))
    }

    @Test fun waitForNeedsAnExpectation() {
        assertTrue(errors("""{"steps":[{"do":"wait_for"}]}""").any { it.contains("at least one 'expect'") })
        assertTrue(errors("""{"steps":[{"do":"wait_for","expect":[{"visible":{"label":"Home"}}]}]}""").isEmpty())
    }

    @Test fun everyScreenChangingStepGetsADefaultExpectation() {
        val plan = parse("""{"steps":[{"do":"tap","target":{"label":"Go"}},{"do":"type_text","text":"x"},{"do":"wait_for","expect":[{"changed":true}]}]}""").plan!!
        assertEquals(listOf(Condition.Changed), PlanValidator.effectiveExpect(plan.steps[0]))
        assertEquals(listOf(Condition.Changed), PlanValidator.effectiveExpect(plan.steps[1]))
        assertNotNull(plan.steps[2].expect.singleOrNull())
    }

    @Test fun planToolSchemaIsStableAndWellFormed() {
        val a = ToolRegistry().schemas.toString()
        val b = ToolRegistry().schemas.toString()
        assertEquals("tool schemas must be byte-identical between builds of the registry (prompt cache prefix)", a, b)
        println("tool schemas: ${registry.tools.size} tools, ${a.length} chars (~${a.length / 4} tokens); run_plan+run_workflow add ${registry.get("run_plan")!!.schema().toString().length + registry.get("run_workflow")!!.schema().toString().length} chars")
        val tool = registry.get("run_plan")!!.schema().toString()
        assertTrue(tool.contains("\"steps\"") && tool.contains("\"items\"") && tool.contains("\"target\""))
    }
}
