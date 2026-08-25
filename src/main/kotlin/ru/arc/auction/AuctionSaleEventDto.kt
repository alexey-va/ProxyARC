package ru.arc.auction

data class AuctionSaleEventDto(
    var listingId: String = "",
    var sellerUuid: String? = null,
    var sellerName: String = "",
    var buyerName: String = "",
    var itemDisplay: String = "предмет",
    var amount: Int = 1,
    var price: String = "",
    var occurredAt: Long = 0,
)
