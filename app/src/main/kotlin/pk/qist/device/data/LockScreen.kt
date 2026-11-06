package pk.qist.device.data

import kotlinx.serialization.Serializable

/** What the customer sees on a locked phone, exactly as the server sends it. Money is paisa, dates YYYY-MM-DD. */
@Serializable
data class LockScreen(
    val locked: Boolean = false,
    val customerName: String = "",
    val deviceLabel: String = "",
    val amountDue: Long = 0,
    val nextDueDate: String? = null,
    val branchName: String = "",
    val branchPhone: String = "",
    val retailerName: String = "",
    val branchAddress: String = "",
    val paymentMethods: List<RetailerPaymentMethod> = emptyList(),
    val message: String = ""
)

@Serializable
data class RetailerPaymentMethod(
    val id: String,
    val kind: String,
    val enabled: Boolean = true,
    val accountTitle: String = "",
    val accountNumber: String = "",
    val bankName: String = ""
)
