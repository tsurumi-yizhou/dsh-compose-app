package dev.fughetta.dshcompose.ui.dynamicui

import dev.fughetta.dshcompose.data.transcript.ChatTranscript
import dev.fughetta.dshcompose.domain.chat.ChatItem
import dev.fughetta.dshcompose.domain.chat.ChatRole
import dev.fughetta.dshcompose.protocol.SessionRecord
import dev.fughetta.dshcompose.protocol.SessionUpdate
import dev.fughetta.dshcompose.protocol.toToolPayload
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GenuiActionsTest {

    private fun view(source: String) = DynamicUiParser.parse(source).view!!

    private inline fun <reified T : UiNode> UiView.node(): T =
        root.walk().filterIsInstance<T>().single()

    private fun UiView.send(input: GenuiInput) =
        genuiActionText(this, input) { action, data -> "$action|$data" }

    @Test
    fun controlsRebuildTheWebPayloadFromTheSourceAndRejectValuesItCouldNotProduce() {

        val card =
            view(
                """

                {"items":[

                  {"type":"button","label":"Rerun","action":"rerun","tone":"danger"},

                  {"type":"switch","label":"Dark","action":"theme"},

                  {"type":"select","id":"env","options":["dev","prod"],"selected":1,"action":"env"},

                  {"type":"radio","options":["a","b"],"action":"pick"},

                  {"type":"slider","id":"n","min":0,"max":10,"step":1,"value":4,"action":"size"},

                  {"type":"input","id":"q","inputType":"number","action":"query"},

                  {"type":"choices","prompt":"Plan?","items":[{"label":"A"},{"label":"B","disabled":true}],"submit":"Go","action":"plan"},

                  {"type":"quiz","question":"2+2","options":[{"label":"4","correct":true},{"label":"5"}],"action":"quiz"},

                  {"type":"input","inputType":"password","id":"secret","action":"login"}

                ]}

                """
                    .trimIndent(),
            )

        val button = card.node<UiGenuiButton>()

        assertEquals(GenuiButtonStyle.Danger, button.style)

        assertEquals(
            """rerun|{"type":"button","label":"Rerun"}""",
            card.send(GenuiInput(button.path)),
        )

        val toggle = card.node<UiGenuiToggle>()

        assertEquals(
            """theme|{"type":"switch","checked":true}""",
            card.send(GenuiInput(toggle.path, checked = true)),
        )

        assertNull(card.send(GenuiInput(toggle.path)))

        val picks = card.root.walk().filterIsInstance<UiGenuiPick>().toList()

        assertEquals(1, picks[0].selected)

        assertEquals(
            """env|{"type":"select","value":"dev","id":"env"}""",
            card.send(GenuiInput(picks[0].path, "dev")),
        )

        assertNull(card.send(GenuiInput(picks[0].path, "staging")))

        assertEquals(
            """pick|{"type":"radio","value":"b"}""",
            card.send(GenuiInput(picks[1].path, "b")),
        )

        val slider = card.node<UiGenuiSlider>()

        assertEquals(
            """size|{"type":"slider","value":7,"id":"n"}""",
            card.send(GenuiInput(slider.path, "7")),
        )

        assertNull(card.send(GenuiInput(slider.path, "11")))

        val field = card.node<UiGenuiField>()

        assertEquals(
            """query|{"type":"input","value":"12","id":"q","submit":true}""",
            card.send(GenuiInput(field.path, "12", submit = true)),
        )

        assertNull(card.send(GenuiInput(field.path, "twelve")))

        val choices = card.node<UiGenuiChoices>()

        assertEquals(
            """plan|{"question":"Plan?","selected":["A"],"mode":"single"}""",
            card.send(GenuiInput(choices.path, values = listOf("A"))),
        )

        assertNull(card.send(GenuiInput(choices.path, values = listOf("B"))))

        assertNull(card.send(GenuiInput(choices.path, values = listOf("A", "A"))))

        val quiz = card.node<UiGenuiQuiz>()

        assertEquals(
            """quiz|{"type":"quiz","question":"2+2","answer":"5","correct":false}""",
            card.send(GenuiInput(quiz.path, "5")),
        )

        val password = card.root.walk().filterIsInstance<UiWebOnly>().single()

        assertEquals(UiWebKind.Interactive, password.kind)

        assertFalse("secret" in genuiMembers(card))

        assertNull(card.send(GenuiInput("spec.items[99]")))
    }

    @Test
    fun submitCollectsGroupsAndFieldsGradesKnownAnswersLocallyAndResets() {

        val form =
            view(
                """

                {"items":[

                  {"type":"radio","group":"color","options":["red","blue"],"selected":0},

                  {"type":"checkbox","group":"tags","label":"fast","checked":true},

                  {"type":"checkbox","group":"tags","label":"cheap"},

                  {"type":"input","id":"note"},

                  {"type":"submit","label":"Send","action":"save","resetAction":"again","groups":["color","tags"]}

                ]}

                """
                    .trimIndent(),
            )

        val defaults = genuiDefaults(form)

        assertEquals(mapOf("color" to "red"), defaults.answers)

        assertEquals(mapOf("tags" to listOf("fast")), defaults.multi)

        val submit = form.node<UiGenuiSubmit>()

        val state = defaults.copy(fields = mapOf("note" to "hi"))

        val resolved = genuiSubmitState(genuiMembers(form), submit.groups, state)

        assertEquals(2, resolved.total)

        assertTrue(resolved.ready)

        assertTrue(resolved.outOfScope)

        assertFalse(resolved.localGrade)

        assertEquals(
            """save|{"type":"submit","answers":{"color":"red","tags":["fast"]},"fields":{"note":"hi"},"total":2,"answered":2}""",
            form.send(GenuiInput(submit.path, submit = true, state = state)),
        )

        assertNull(
            form.send(
                GenuiInput(submit.path, submit = true, state = state.copy(answers = mapOf())),
            ),
        )

        assertNull(
            form.send(
                GenuiInput(
                    submit.path,
                    submit = true,
                    state = state.copy(answers = mapOf("color" to "green")),
                ),
            ),
        )

        assertEquals(
            """again|{"type":"submit-reset","groups":["color","tags"]}""",
            form.send(GenuiInput(submit.path, reset = true)),
        )

        val quiz =
            view(
                """

                {"items":[

                  {"type":"radio","group":"q1","label":"1+1","options":["1","2"],"answer":1},

                  {"type":"submit","label":"Check","action":"graded","groups":["q1"]}

                ]}

                """
                    .trimIndent(),
            )

        val check = quiz.node<UiGenuiSubmit>()

        val answered = GenuiState(answers = mapOf("q1" to "2"))

        val local = genuiSubmitState(genuiMembers(quiz), check.groups, answered)

        assertTrue(local.localGrade)

        assertTrue(local.gradesLocally(check.action))

        assertNull(quiz.send(GenuiInput(check.path, submit = true, state = answered)))
    }

    @Test
    fun renderUiToolResultIsALiveCardUntilTheNextUserMessage() {

        val transcript = ChatTranscript()

        fun event(seq: Long, type: String, data: String) =
            transcript.apply(
                SessionUpdate.Record(
                    SessionRecord(seq, seq, type, Json.parseToJsonElement(data).jsonObject),
                ),
            )

        event(1, "tool/call", """{"callId":"ui","name":"render_ui","arguments":"{}"}""")

        event(
            2,
            "tool/result",
            """{"message":{"toolCallId":"ui","content":[{"type":"text","text":"shown"}]},"meta":{"title":"Deploy","items":[{"type":"button","label":"Ship","action":"ship"}]}}""",
        )

        val row = transcript.items.single { it.tool != null }

        val source = genuiToolSource(row)!!

        val card = genuiToolView(row)!!.view!!

        assertEquals("genui", card.dialect)

        val input = GenuiInput(card.node<UiGenuiButton>().path)

        assertEquals(
            """ship|{"type":"button","label":"Ship"}""",
            dynamicUiGenuiActionText(row, source, input) { action, data -> "$action|$data" },
        )

        assertNull(dynamicUiGenuiActionText(row, "$source ", input) { a, _ -> a })

        event(
            3,
            "assistant/message",
            """{"turn":1,"step":2,"message":{"content":[{"type":"text","text":"Pick one above."}]}}""",
        )

        assertEquals(
            UiInteraction.Live,
            uiInteraction(row, transcript.presentation.order, false).mode,
        )

        event(
            4,
            "user/message",
            """{"source":{"kind":"user"},"content":[{"type":"text","text":"[genui-action] ship. data"}]}""",
        )

        assertEquals(
            UiInteraction.Answered,
            uiInteraction(row, transcript.presentation.order, false).mode,
        )

        val plain =
            ChatItem("t", ChatRole.Assistant, tool = row.tool!!.copy(name = "read"), seq = 1)

        assertNull(genuiToolSource(plain))

        assertNull(
            genuiToolSource(
                row.copy(
                    tool =
                        row.tool!!.copy(
                            meta =
                                buildJsonObject { put("dsh-compose/view", JsonNull) }
                                    .toToolPayload(),
                        ),
                ),
            ),
        )
    }
}
