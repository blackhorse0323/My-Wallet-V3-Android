package piuk.blockchain.android.ui.transactionflow.plugin

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import com.blockchain.core.price.ExchangeRatesDataManager
import com.blockchain.koin.scopedInject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import piuk.blockchain.android.ui.resources.AccountIcon
import piuk.blockchain.android.coincore.CryptoAccount
import piuk.blockchain.android.coincore.toFiat
import piuk.blockchain.android.databinding.ViewCheckoutSwapHeaderBinding
import piuk.blockchain.android.ui.resources.AssetResources
import piuk.blockchain.android.ui.transactionflow.analytics.TxFlowAnalytics
import piuk.blockchain.android.ui.transactionflow.engine.TransactionModel
import piuk.blockchain.android.ui.transactionflow.engine.TransactionState
import piuk.blockchain.android.ui.transactionflow.flow.customisations.TransactionConfirmationCustomisations
import piuk.blockchain.android.util.setAssetIconColoursNoTint
import piuk.blockchain.android.util.visible
import piuk.blockchain.android.util.visibleIf

class SwapInfoHeaderView @JvmOverloads constructor(
    ctx: Context,
    attr: AttributeSet? = null,
    defStyle: Int = 0
) : ConstraintLayout(ctx, attr, defStyle), ConfirmSheetWidget, KoinComponent {

    private lateinit var model: TransactionModel
    private lateinit var customiser: TransactionConfirmationCustomisations
    private lateinit var analytics: TxFlowAnalytics
    private val assetResources: AssetResources by inject()
    private val exchangeRates: ExchangeRatesDataManager by scopedInject()

    private val binding: ViewCheckoutSwapHeaderBinding =
        ViewCheckoutSwapHeaderBinding.inflate(LayoutInflater.from(context), this, true)

    override fun initControl(
        model: TransactionModel,
        customiser: TransactionConfirmationCustomisations,
        analytics: TxFlowAnalytics
    ) {
        check(this::model.isInitialized.not()) { "Control already initialised" }

        this.model = model
        this.customiser = customiser
        this.analytics = analytics
    }

    override fun update(state: TransactionState) {
        check(::model.isInitialized) { "Control not initialised" }

        with(binding) {
            state.pendingTx?.amount?.let { amount ->
                sendingAmountCrypto.text = amount.toStringWithSymbol()
                state.fiatRate?.let {
                    sendingAmountFiat.text = it.convert(amount, false).toStringWithSymbol()
                }
            }

            state.targetRate?.let { cryptoExchangeRate ->
                val receivingAmount = cryptoExchangeRate.convert(state.amount)
                receivingAmountCrypto.text = receivingAmount.toStringWithSymbol()
                state.pendingTx?.selectedFiat?.let { fiat ->
                    receivingAmountFiat.text = receivingAmount.toFiat(fiat, exchangeRates).toStringWithSymbol()
                }
            }

            sendingAccountLabel.text = state.sendingAccount.label
            val accountIcon = AccountIcon(state.sendingAccount, assetResources)
            accountIcon.loadAssetIcon(sendingIcon)
            accountIcon.indicator?.let {
                sendingAccountIcon.apply {
                    visible()
                    setAssetIconColoursNoTint(state.sendingAsset)
                    setImageResource(it)
                }
            }

            (state.selectedTarget as? CryptoAccount)?.let { account ->
                receivingAccountLabel.text = account.label
                val targetIcon = AccountIcon(account, assetResources)
                targetIcon.loadAssetIcon(receivingIcon)
                targetIcon.indicator?.let {
                    receivingAccountIcon.apply {
                        visible()
                        setAssetIconColoursNoTint(account.asset)
                        setImageResource(it)
                    }
                }
            }
        }
    }

    override fun setVisible(isVisible: Boolean) {
        binding.root.visibleIf { isVisible }
    }
}