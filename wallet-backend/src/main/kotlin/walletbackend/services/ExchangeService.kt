package walletbackend.services

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.retry.support.RetryTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import tbdex.sdk.httpclient.TbdexHttpClient
import tbdex.sdk.httpclient.models.Exchange
import tbdex.sdk.protocol.models.*
import walletbackend.config.CryptoUtil
import walletbackend.entities.QuoteMapper
import walletbackend.entities.UserDID
import walletbackend.exceptions.OfferingNotFoundException
import walletbackend.exceptions.QuoteNotFoundException
import walletbackend.exceptions.RfqNotFoundException
import walletbackend.exceptions.UserNotFoundException
import walletbackend.models.*
import walletbackend.repositories.*
import web5.sdk.credentials.PresentationExchange
import web5.sdk.crypto.KeyManager
import web5.sdk.dids.did.BearerDid
import java.time.LocalDateTime
import java.util.*

@Service
class ExchangeService(
        val offeringRepository: OfferingRepository,
        val signedVcRepository: SignedVcRepository,
        val userDIDRepository: UserDIDRepository,
        val keyManager: KeyManager,
        @Value("\${did.encryption.secret}")
        val secret: String,
        @Value("\${tbdex.protocol}")
        val protocol: String,
        val objectMapper: ObjectMapper,
        val rfqRepository: RfqRepository,
        val quoteRepository: QuoteRepository,
        val retryTemplate: RetryTemplate) {

    private val log = LoggerFactory.getLogger(ExchangeService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun createRfq(rfQRequest: CreateRfQRequest): RfqCreationResponse {
        val optionalUserDid = getUserDid(rfQRequest.customerDID)
        if (optionalUserDid.isEmpty) {
            throw UserNotFoundException("User with didUri: ${rfQRequest.customerDID} does not exist")
        }
        val bearerDid = getBearerDid(optionalUserDid.get())
        val optionalOffering = offeringRepository.findByRef(rfQRequest.offeringRef)
        if (optionalOffering.isEmpty) {
            throw OfferingNotFoundException("offering with ref: ${rfQRequest.offeringRef} expired, Please choose a new offering")
        }
        val offering = optionalOffering.get()
        val signedVc = signedVcRepository.findAllByDidUri(rfQRequest.customerDID).map { it.signedJWT }
        val externalOffering = objectMapper.readValue(offering.jsonString, Offering::class.java)
        val selectCredentials = externalOffering.data.requiredClaims?.let {
            PresentationExchange.selectCredentials(
                    vcJwts = signedVc,
                    it
            )
        }
        val rfq = Rfq.create(
                to = offering.pfiDID,
                from = rfQRequest.customerDID,
                rfqData = CreateRfqData(
                        offeringId = rfQRequest.offeringRef,
                        payin = CreateSelectedPayinMethod(
                                kind = rfQRequest.payin.paymentMethodKind,
                                paymentDetails = rfQRequest.payin.paymentMethodValues.associate { it.fieldName to it.fieldValue },
                                amount = rfQRequest.amount.toString(),
                        ),
                        payout = CreateSelectedPayoutMethod(
                                kind = rfQRequest.payout.paymentMethodKind,
                                paymentDetails = rfQRequest.payout.paymentMethodValues.associate { it.fieldName to it.fieldValue }
                        ),
                        claims = selectCredentials!!
                )
        )

        val savedRfq = rfqRepository.save(walletbackend.entities.Rfq(
                id = UUID.randomUUID(),
                customerDID = rfq.metadata.from,
                pfiDID = rfq.metadata.to,
                exchangeId = rfq.metadata.exchangeId
        ))

        scope.launch {
            handlePostRFQCreationActions(rfq, externalOffering, bearerDid)
        }

        return RfqCreationResponse(exchangeId = savedRfq.exchangeId)
    }

    fun isRfqProcessed(exchangeId: String): Boolean {
        val optionalRfq = rfqRepository.findByExchangeId(exchangeId)
        if (optionalRfq.isEmpty)
            throw RfqNotFoundException("Rfq not found with exchangeId: {}, Please create a new rfq")
        val rfq = optionalRfq.get()
        return rfq.exchangeStatus == ExchangeStatus.QUOTE_CREATION_COMPLETED
    }

    fun processQuote(quoteNextStep: QuoteNextStep) {
        val optionalQuote = quoteRepository.findByExchangeIdAndOrderStatus(exchangeId = quoteNextStep.exchangeId, orderStatus = "")
        if (optionalQuote.isEmpty) {
            throw QuoteNotFoundException("Quote not found with exchangeId: ${quoteNextStep.exchangeId}")
        }
        val quote = optionalQuote.get()
        when (quoteNextStep.proceed) {
            true -> createOrder(quote)
            false -> quoteNextStep.reason?.let { closeExchange(quote, it) } ?: closeExchange(quote, "")
        }
    }

    private fun closeExchange(quote: walletbackend.entities.Quote, reason: String) {
        val close = Close.create(
                from = quote.customerDID,
                to = quote.pfiDID,
                exchangeId = quote.exchangeId,
                protocol = protocol,
                closeData = CloseData(
                        reason = reason,
                        success = false
                )
        )

        scope.launch {
            handlePostCloseCreationAction(close)
        }
        rfqRepository.updateExchangeStatus(quote.exchangeId, ExchangeStatus.CLOSE_CREATION_PENDING)
    }

    private fun handlePostCloseCreationAction(close: Close) {
        val failureMessages = mutableListOf<String>()
        val optionalUserDid = getUserDid(close.metadata.from)
        if (optionalUserDid.isEmpty) return
        val bearerDid = getBearerDid(optionalUserDid.get())
        close.sign(bearerDid)
        try {
            log.info("Submitting close for exchangeId: {}", close.metadata.exchangeId)
            retryTemplate.execute<Unit, RuntimeException> { TbdexHttpClient.submitClose(close) }
        } catch (e: Exception) {
            log.error("Could not submit close: {}", e.message)
            e.message?.let { failureMessages.add(it) }
        } finally {
            if (failureMessages.isEmpty()) rfqRepository.updateExchangeStatus(close.metadata.exchangeId, ExchangeStatus.CLOSE_CREATION_COMPLETED)
            else rfqRepository.updateExchangeStatus(close.metadata.exchangeId, ExchangeStatus.CLOSE_CREATION_FAILED)
        }
    }

    private fun createOrder(quote: walletbackend.entities.Quote) {
        val order = Order.create(
                from = quote.customerDID,
                to = quote.pfiDID,
                exchangeId = quote.exchangeId,
                protocol = protocol
        )
        scope.launch {
            handlePostOrderCreationActions(order)
        }
        rfqRepository.updateExchangeStatus(quote.exchangeId, ExchangeStatus.ORDER_CREATION_PENDING)
    }

    private fun handlePostOrderCreationActions(order: Order) {
        val failureMessages = mutableListOf<String>()
        val optionalUserDid = getUserDid(order.metadata.from)
        if (optionalUserDid.isEmpty) return
        val bearerDid = getBearerDid(optionalUserDid.get())
        order.sign(bearerDid)
        try {
            log.info("Submitting order for exchangeId: {}", order.metadata.exchangeId)
            retryTemplate.execute<Unit, RuntimeException> { TbdexHttpClient.submitOrder(order) }
        } catch (e: Exception) {
            log.error("Could not submit order: {}", e.message)
            e.message?.let { failureMessages.add(it) }
        } finally {
            if (failureMessages.isEmpty()) rfqRepository.updateExchangeStatus(order.metadata.exchangeId, ExchangeStatus.ORDER_CREATION_COMPLETED)
            else rfqRepository.updateExchangeStatus(order.metadata.exchangeId, ExchangeStatus.ORDER_CREATION_FAILED)
        }
    }

    private fun handlePostRFQCreationActions(rfq: Rfq, offering: Offering, bearerDid: BearerDid) {
        log.info("Handling post rfq creation actions")
        val failureMessages = mutableListOf<String>()
        try {
            rfq.verifyOfferingRequirements(offering)
        } catch (e: Exception) {
            log.error("Could not verify offering requirements {}", e.message)
            e.message?.let { failureMessages.add(it) }
        }
        rfq.sign(bearerDid)
        try {
            retryTemplate.execute<Unit, RuntimeException> { TbdexHttpClient.createExchange(rfq) }
        } catch (e: Exception) {
            log.error("Could not create exchange {}", e.message)
            e.message?.let { failureMessages.add(it) }
        } finally {
            if (failureMessages.isEmpty()) rfqRepository.updateExchangeStatus(rfq.metadata.exchangeId, ExchangeStatus.RFQ_CREATION_COMPLETED)
            else rfqRepository.updateExchangeStatus(rfq.metadata.exchangeId, ExchangeStatus.RFQ_CREATION_FAILED)
        }
    }


    @Scheduled(fixedDelay = 80000L)
    fun pollExchange() {
        log.info("Starting to poll exchange")
        val eligibleStatuses = mutableListOf(
                ExchangeStatus.RFQ_CREATION_COMPLETED,
                ExchangeStatus.ORDER_CREATION_COMPLETED,
                ExchangeStatus.CLOSE_CREATION_COMPLETED
        )
        val rfqs = rfqRepository.findAllByExchangeStatusIn(eligibleStatuses)
        val requests = mutableListOf<Deferred<Exchange>>()
        scope.launch {
            rfqs.forEach { rfq ->
                val optionalUserDid = userDIDRepository.findByDidUri(rfq.customerDID)
                if (optionalUserDid.isEmpty) {
                    log.error("User not found with didUri: {}", rfq.customerDID)
                    return@forEach
                }
                val userDID = optionalUserDid.get()
                val bearerDid = getBearerDid(userDID)
                requests.add(
                        async {
                            retryTemplate.execute<Exchange, RuntimeException> {
                                TbdexHttpClient.getExchange(
                                        pfiDid = rfq.pfiDID,
                                        requesterDid = bearerDid,
                                        exchangeId = rfq.exchangeId
                                )
                            }
                        })
            }

            val exchanges = requests.awaitAll()
            processExchanges(exchanges)
        }
    }

    fun processExchanges(exchanges: List<Exchange>) {
        for (exchange in exchanges) {

            for (message in exchange) {
                when (message) {
                    is Quote -> {
                        if (!isValidQuote(message)) {
                            break
                        }
                    }
                    is Close -> {
                        processClose(message)
                        break
                    }
                    is Order -> continue
                    is OrderStatus -> processOrderStatus(message)
                    is Rfq -> continue
                }
            }
        }
    }

    private fun getBearerDid(userDID: UserDID): BearerDid {
        val decrypted = CryptoUtil.decrypt(userDID.encryptedDID, secret)
        val portableDid = PortableDIDMapper.mapPortableDID(objectMapper.readValue(decrypted, PortableDID::class.java))
        return BearerDid.import(portableDid = portableDid, keyManager = keyManager)
    }

    private fun getUserDid(didUri: String): Optional<UserDID> {
        val optionalUserDid = userDIDRepository.findByDidUri(didUri)
        if (optionalUserDid.isEmpty) {
            log.error("User not found with didUri: {}", didUri)
            return Optional.empty<UserDID>()
        }
        return optionalUserDid
    }

    private fun processOrderStatus(orderStatus: OrderStatus) {
        val optionalQuote = quoteRepository.findByExchangeId(orderStatus.metadata.exchangeId)
        if (optionalQuote.isEmpty) return
        val quote = optionalQuote.get()
        quote.orderStatus = orderStatus.data.orderStatus
        quoteRepository.save(quote)
        if (quote.orderStatus.lowercase() == "success")
            rfqRepository.updateExchangeStatus(quote.exchangeId, ExchangeStatus.EXCHANGE_COMPLETED)
    }

    private fun processClose(close: Close) {
        val optionalQuote = quoteRepository.findByExchangeIdAndOrderStatus(close.metadata.exchangeId, "")
        if (optionalQuote.isEmpty) return
        val quote = optionalQuote.get()
        quote.orderCloseReason = close.data.reason?.let { close.data.reason } ?: ""
        quoteRepository.save(quote)

        rfqRepository.updateExchangeStatus(quote.exchangeId, ExchangeStatus.EXCHANGE_COMPLETED)
    }

    private fun isValidQuote(_quote: Quote): Boolean {
        val optionalQuote = quoteRepository.findByExchangeId(exchangeId = _quote.metadata.exchangeId)
        if (optionalQuote.isEmpty) {
            quoteRepository.save(QuoteMapper.mapQuote(_quote))
            rfqRepository.updateExchangeStatus(_quote.metadata.exchangeId, ExchangeStatus.QUOTE_CREATION_COMPLETED)
            return true
        }
        val quote = optionalQuote.get()
        val optionalRfq = rfqRepository.findByExchangeId(exchangeId = quote.exchangeId)
        if (optionalRfq.isEmpty || optionalRfq.get().exchangeStatus == ExchangeStatus.EXCHANGE_COMPLETED) {
            if (LocalDateTime.now().isAfter(quote.expiresAt))
                rfqRepository.updateExchangeStatus(quote.exchangeId, ExchangeStatus.EXCHANGE_COMPLETED)
            return false
        }
        return true
    }


}
