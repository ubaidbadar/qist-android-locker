package pk.qist.device.lock

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pk.qist.device.ui.theme.QistTheme

/** Runs on the JVM through Robolectric, so it needs no phone or emulator. */
@RunWith(AndroidJUnit4::class)
class LockScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val full = LockScreenUi(
        message = "Please pay your installment",
        amountDue = "Rs 15,000",
        nextDueDate = "12 Oct 2026",
        branchName = "Saddar",
        branchPhone = "0300 1234567"
    )

    private fun show(screen: LockScreenUi, onCall: (String) -> Unit = {}, onEmergency: () -> Unit = {}) =
        compose.setContent {
            QistTheme { LockScreenContent(screen = screen, onCallBranch = onCall, onEmergencyCall = onEmergency) }
        }

    @Test
    fun `shows the message, amount, due date and branch`() {
        show(full)

        compose.onNodeWithText("This phone is locked").assertIsDisplayed()
        compose.onNodeWithText("Please pay your installment").assertIsDisplayed()
        compose.onNodeWithText("Rs 15,000").assertIsDisplayed()
        compose.onNodeWithText("Next payment due 12 Oct 2026").assertIsDisplayed()
        compose.onNodeWithText("Pay at Saddar").assertIsDisplayed()
    }

    @Test
    fun `call branch dials the branch number and is labelled for screen readers`() {
        var dialled: String? = null
        show(full, onCall = { dialled = it })

        compose.onNodeWithContentDescription("Call Saddar at 0300 1234567").performClick()

        assertEquals("0300 1234567", dialled)
    }

    @Test
    fun `emergency call is always there, even without a branch number`() {
        var emergencies = 0
        show(full.copy(branchPhone = null), onEmergency = { emergencies++ })

        compose.onNodeWithText("Call branch").assertDoesNotExist()
        compose.onNodeWithText("Emergency call").performClick()

        assertEquals(1, emergencies)
    }

    @Test
    fun `nothing about money shows when nothing is due`() {
        show(full.copy(amountDue = null, nextDueDate = null))

        compose.onNodeWithText("Amount due").assertDoesNotExist()
        compose.onNodeWithText("Please pay your installment").assertIsDisplayed()
    }
}
