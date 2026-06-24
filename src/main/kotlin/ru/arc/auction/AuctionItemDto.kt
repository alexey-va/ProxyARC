package ru.arc.auction

data class AuctionItemDto(
    @JvmField var display: String? = null,
    @JvmField var seller: String? = null,
    @JvmField var price: String? = null,
    @JvmField var expire: Long = 0,
    @JvmField var category: String? = null,
    @JvmField var amount: Int = 0,
    @JvmField var priority: Int = 0,
    @JvmField var uuid: String? = null,
    @JvmField var exist: Boolean = false,
    @JvmField var lore: MutableList<String> = ArrayList(),
)
