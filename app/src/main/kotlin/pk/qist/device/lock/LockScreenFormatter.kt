package pk.qist.device.lock

import pk.qist.device.data.LockScreen

/**
 * Turns the server's lock screen (paisa, YYYY-MM-DD) into display text. Pure Kotlin with no locale or time-zone
 * lookups, so it is fast on a cold start and gives the same output on every phone.
 */
object LockScreenFormatter {
    private const val PAISA_PER_RUPEE = 100
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private const val FALLBACK_MESSAGE = "This phone is locked. Please contact the shop that sold it to unlock it."

    fun format(screen: LockScreen) = LockScreenUi(
        message = screen.message.ifBlank { FALLBACK_MESSAGE },
        amountDue = screen.amountDue.takeIf { it > 0 }?.let(::rupees),
        nextDueDate = screen.nextDueDate?.let(::date),
        branchName = screen.branchName.ifBlank { null },
        branchPhone = screen.branchPhone.ifBlank { null }
    )

    /** 1_500_050 paisa → "Rs 15,000.50"; whole rupees drop the paisa: "Rs 15,000". */
    fun rupees(paisa: Long): String {
        val rupees = paisa / PAISA_PER_RUPEE
        val rest = paisa % PAISA_PER_RUPEE
        val grouped = rupees.toString().reversed().chunked(3).joinToString(",").reversed()
        return if (rest == 0L) "Rs $grouped" else "Rs $grouped.${rest.toString().padStart(2, '0')}"
    }

    /** "2026-10-12" → "12 Oct 2026"; anything malformed → null, so the screen simply leaves the date out. */
    fun date(isoDate: String): String? {
        val parts = isoDate.split("-")
        val year = parts.getOrNull(0)?.toIntOrNull()
        val month = parts.getOrNull(1)?.toIntOrNull()?.let { MONTHS.getOrNull(it - 1) }
        val day = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..31 }
        return if (parts.size == 3 && year != null && month != null && day != null) "$day $month $year" else null
    }
}
