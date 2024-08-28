package walletbackend.entities

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

@Document
data class Quote(
        @Id
        val id: UUID,
        val exchangeId: String,
        val quoteId: String,
        val customerDID: String,
        val pfiDID: String,
        val createdAt: LocalDateTime,
        val expiresAt: LocalDateTime,
        val payin: PaymentDetail,
        val payout: PaymentDetail,
        var orderStatus: String,
        var orderCloseReason: String
)

data class PaymentDetail(
        val currencyCode: String,
        val amount: BigDecimal,
        val fee: BigDecimal?,
        val paymentInstruction: PaymentInstruction
)

data class PaymentInstruction(
        val link: String?,
        val instruction: String?
)

object QuoteMapper {

    fun mapQuote(quote: tbdex.sdk.protocol.models.Quote): Quote {
        return Quote(
                id = UUID.randomUUID(),
                exchangeId = quote.metadata.exchangeId,
                quoteId = quote.metadata.id,
                createdAt = quote.metadata.createdAt.toLocalDateTime(),
                expiresAt = quote.data.expiresAt.toLocalDateTime(),
                customerDID = quote.metadata.to,
                pfiDID = quote.metadata.from,
                payout = PaymentDetail(
                        currencyCode = quote.data.payout.currencyCode,
                        amount = BigDecimal(quote.data.payout.amount),
                        fee = quote.data.payout.fee?.let { BigDecimal(quote.data.payin.fee) } ?: BigDecimal(0),
                        paymentInstruction = PaymentInstruction(
                                link = quote.data.payout.paymentInstruction?.link,
                                instruction = quote.data.payout.paymentInstruction?.instruction
                        )
                ),
                payin = PaymentDetail(
                        currencyCode = quote.data.payin.currencyCode,
                        amount = BigDecimal(quote.data.payin.amount),
                        fee = quote.data.payin.fee?.let { BigDecimal(quote.data.payin.fee) } ?: BigDecimal(0),
                        paymentInstruction = PaymentInstruction(
                                link = quote.data.payin.paymentInstruction?.link,
                                instruction = quote.data.payin.paymentInstruction?.instruction
                        )
                ),
                orderStatus = "",
                orderCloseReason = ""
        )
    }
}
