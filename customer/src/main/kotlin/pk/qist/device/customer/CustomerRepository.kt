package pk.qist.device.customer

internal interface CustomerRepository {
    fun verify(code: String): Boolean
    fun dashboard(): DashboardSample
}

internal data class PaymentSample(val number: Int, val date: String, val amount: String)
internal data class DashboardSample(
    val name: String,
    val model: String,
    val amount: String,
    val dueDate: String,
    val days: Int,
    val paid: Int,
    val total: Int,
    val payments: List<PaymentSample>,
)

internal object DemoCustomerRepository : CustomerRepository {
    override fun verify(code: String) = code == "123456"
    override fun dashboard() = DashboardSample(
        name = "Demo Customer",
        model = "Demo Phone",
        amount = "7,500",
        dueDate = "12 Nov 2026",
        days = 8,
        paid = 3,
        total = 6,
        payments = listOf(
            PaymentSample(3, "12 Oct 2026", "7,500"),
            PaymentSample(2, "12 Sep 2026", "7,500"),
        ),
    )
}

