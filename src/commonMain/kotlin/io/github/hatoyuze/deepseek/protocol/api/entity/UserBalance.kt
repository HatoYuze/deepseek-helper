package io.github.hatoyuze.deepseek.protocol.api.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 用户账户余额信息。
 *
 * 通过 [Deepseek.balance][io.github.hatoyuze.deepseek.protocol.api.Deepseek.balance] 获取。
 */
@Serializable
public data class UserBalance(
    @SerialName("is_available")
    val isAvailable: Boolean,
    @SerialName("balance_infos")
    internal val balanceInfos: List<BalanceInfo>,
) {
    /** 首个余额信息条目 */
    public val balanceInfo: BalanceInfo = balanceInfos.first()
}

/**
 * 单项余额详情。
 *
 * @param currency 货币类型
 * @param totalBalance 总余额
 * @param grantedBalance 赠送余额
 * @param toppedUpBalance 充值余额
 */
@Serializable
public data class BalanceInfo(
    val currency: Currency,
    @SerialName("total_balance")
    val totalBalance: String,
    @SerialName("granted_balance")
    val grantedBalance: String,
    @SerialName("topped_up_balance")
    val toppedUpBalance: String,
)

/** 货币类型 */
@Serializable
public enum class Currency {
    @SerialName("CNY") CNY,
    @SerialName("USD") USD,
}
