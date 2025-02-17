package piuk.blockchain.android.ui.customviews.account

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import com.blockchain.nabu.datamanagers.custodialwalletimpl.PaymentMethodType
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import piuk.blockchain.android.R
import piuk.blockchain.android.coincore.AssetAction
import piuk.blockchain.android.coincore.fiat.LinkedBankAccount
import piuk.blockchain.android.databinding.ViewAccountBankOverviewBinding
import piuk.blockchain.android.ui.customviews.StatusPill
import piuk.blockchain.android.ui.transactionflow.analytics.TxFlowAnalytics
import piuk.blockchain.android.ui.transactionflow.engine.TransactionIntent
import piuk.blockchain.android.ui.transactionflow.engine.TransactionModel
import piuk.blockchain.android.ui.transactionflow.engine.TransactionState
import piuk.blockchain.android.ui.transactionflow.flow.customisations.EnterAmountCustomisations
import piuk.blockchain.android.ui.transactionflow.plugin.EnterAmountWidget
import piuk.blockchain.android.util.gone
import piuk.blockchain.android.util.invisible
import piuk.blockchain.android.util.visible
import piuk.blockchain.android.util.visibleIf
import timber.log.Timber

class AccountInfoBank @JvmOverloads constructor(
    ctx: Context,
    attr: AttributeSet? = null,
    defStyle: Int = 0
) : ConstraintLayout(ctx, attr, defStyle), EnterAmountWidget {

    private lateinit var model: TransactionModel
    private val compositeDisposable = CompositeDisposable()
    private var accountId: String = ""

    val binding: ViewAccountBankOverviewBinding =
        ViewAccountBankOverviewBinding.inflate(LayoutInflater.from(context), this, true)

    fun updateAccount(
        shouldShowBadges: Boolean = true,
        account: LinkedBankAccount,
        action: AssetAction? = null,
        onAccountClicked: (LinkedBankAccount) -> Unit
    ) {
        with(binding) {
            bankName.text = account.label
            bankLogo.setImageResource(R.drawable.ic_bank_transfer)
            bankDetails.text = context.getString(
                R.string.common_hyphenated_strings,
                if (account.accountType.isBlank()) {
                    context.getString(R.string.bank_account_info_default)
                } else {
                    account.accountType
                }, account.accountNumber
            )
        }
        setOnClickListener { onAccountClicked(account) }

        if (shouldShowBadges) {
            require(account.type == PaymentMethodType.BANK_TRANSFER || account.type == PaymentMethodType.BANK_ACCOUNT) {
                "Using incorrect payment method for Bank view"
            }

            if (account.accountId == accountId)
                return
            accountId = account.accountId

            getFeeOrShowDefault(account, action)
        }
    }

    private fun getFeeOrShowDefault(account: LinkedBankAccount, action: AssetAction?) {
        if (action == AssetAction.Withdraw) {
            getMinimumWithdrawalAndFee(account)
        } else {
            with(binding) {
                bankStatusFee.gone()
                bankStatusMin.gone()
            }
        }
    }

    private fun getMinimumWithdrawalAndFee(account: LinkedBankAccount) {
        with(binding) {
            compositeDisposable += account.getWithdrawalFeeAndMinLimit()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe {
                    // total hack.In order to avoid flickering we need to reserve the space for the extra info in the pills
                    // the reason that binding.bankStatusMin is set to gone, is that bankStatusFee should be aligned left if
                    // bankStatusMin is missing
                    bankStatusFee.invisible()
                    bankStatusMin.gone()
                }
                .subscribeBy(
                    onSuccess = {
                        bankStatusFee.visible()
                        if (it.fee.isZero) {
                            bankStatusFee.update(
                                context.getString(R.string.common_free), StatusPill.StatusType.UPSELL
                            )
                        } else {
                            bankStatusFee.update(
                                context.getString(R.string.bank_wire_transfer_fee, it.fee.toStringWithSymbol()),
                                StatusPill.StatusType.WARNING
                            )
                        }
                        if (!it.minLimit.isZero) {
                            bankStatusMin.visible()
                            bankStatusMin.update(
                                context.getString(
                                    R.string.bank_wire_transfer_min_withdrawal, it.minLimit.toStringWithSymbol()
                                ), StatusPill.StatusType.LABEL
                            )
                        }
                    },
                    onError = {
                        Timber.e("Error getting account fee $it")
                        bankStatusFee.gone()
                        bankStatusMin.gone()
                    }
                )
        }
    }

    override fun initControl(
        model: TransactionModel,
        customiser: EnterAmountCustomisations,
        analytics: TxFlowAnalytics
    ) {
        this.model = model
        with(binding) {
            bankSeparator.visible()
            bankChevron.visible()
            bankStatusMin.gone()
            bankStatusFee.gone()
        }
    }

    override fun update(state: TransactionState) {
        when (state.action) {
            AssetAction.FiatDeposit ->
                if (state.sendingAccount is LinkedBankAccount) {
                    // only try to update if we have a linked bank source
                    updateAccount(false, state.sendingAccount, state.action) {
                        if (::model.isInitialized) {
                            model.process(TransactionIntent.InvalidateTransactionKeepingTarget)
                        }
                    }
                }
            AssetAction.Withdraw ->
                if (state.selectedTarget is LinkedBankAccount) {
                    updateAccount(false, state.selectedTarget, state.action) {
                        if (::model.isInitialized) {
                            model.process(TransactionIntent.InvalidateTransaction)
                        }
                    }
                }
            else -> {
                // do nothing
            }
        }
    }

    override fun setVisible(isVisible: Boolean) {
        binding.root.visibleIf { isVisible }
    }
}
