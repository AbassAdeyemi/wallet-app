package walletbackend.models

enum class ExchangeStatus {
    RFQ_CREATION_PENDING,
    RFQ_CREATION_FAILED,
    RFQ_CREATION_COMPLETED,
    QUOTE_CREATION_PENDING,
    QUOTE_CREATION_FAILED,
    QUOTE_CREATION_COMPLETED,
    CLOSE_CREATION_PENDING,
    CLOSE_CREATION_FAILED,
    CLOSE_CREATION_COMPLETED,
    ORDER_CREATION_PENDING,
    ORDER_CREATION_FAILED,
    ORDER_CREATION_COMPLETED,
    EXCHANGE_COMPLETED
}