package piuk.blockchain.android.simplebuy

import com.blockchain.extensions.exhaustive
import com.blockchain.logging.CrashLogger
import com.blockchain.nabu.datamanagers.ApprovalErrorStatus
import com.blockchain.nabu.datamanagers.BuySellOrder
import com.blockchain.nabu.datamanagers.OrderState
import com.blockchain.nabu.datamanagers.RecurringBuyOrder
import com.blockchain.nabu.datamanagers.UndefinedPaymentMethod
import com.blockchain.nabu.datamanagers.custodialwalletimpl.PaymentMethodType
import com.blockchain.nabu.models.data.BankPartner
import com.blockchain.nabu.models.data.EligibleAndNextPaymentRecurringBuy
import com.blockchain.nabu.models.data.RecurringBuyFrequency
import com.blockchain.nabu.models.data.RecurringBuyState
import com.blockchain.nabu.models.responses.nabu.NabuApiException
import com.blockchain.nabu.models.responses.nabu.NabuErrorCodes
import com.blockchain.nabu.models.responses.simplebuy.EverypayPaymentAttrs
import com.blockchain.nabu.models.responses.simplebuy.SimpleBuyConfirmationAttributes
import com.blockchain.preferences.RatingPrefs
import com.blockchain.preferences.SimpleBuyPrefs
import info.blockchain.balance.AssetInfo
import info.blockchain.balance.FiatValue
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import piuk.blockchain.android.cards.partners.CardActivator
import piuk.blockchain.android.cards.partners.EverypayCardActivator
import piuk.blockchain.android.domain.usecases.GetEligibilityAndNextPaymentDateUseCase
import piuk.blockchain.android.domain.usecases.IsFirstTimeBuyerUseCase
import piuk.blockchain.android.networking.PollResult
import piuk.blockchain.android.ui.base.mvi.MviModel
import piuk.blockchain.android.ui.linkbank.BankTransferAction
import piuk.blockchain.androidcore.data.api.EnvironmentConfig
import piuk.blockchain.androidcore.utils.extensions.thenSingle

class SimpleBuyModel(
    private val prefs: SimpleBuyPrefs,
    private val ratingPrefs: RatingPrefs,
    initialState: SimpleBuyState,
    uiScheduler: Scheduler,
    private val serializer: SimpleBuyPrefsSerializer,
    private val cardActivators: List<CardActivator>,
    private val interactor: SimpleBuyInteractor,
    private val isFirstTimeBuyerUseCase: IsFirstTimeBuyerUseCase,
    private val getEligibilityAndNextPaymentDateUseCase: GetEligibilityAndNextPaymentDateUseCase,
    environmentConfig: EnvironmentConfig,
    crashLogger: CrashLogger,
    private val bankPartnerCallbackProvider: BankPartnerCallbackProvider
) : MviModel<SimpleBuyState, SimpleBuyIntent>(
    initialState = serializer.fetch() ?: initialState,
    uiScheduler = uiScheduler,
    environmentConfig = environmentConfig,
    crashLogger = crashLogger
) {

    override fun performAction(previousState: SimpleBuyState, intent: SimpleBuyIntent): Disposable? =
        when (intent) {
            is SimpleBuyIntent.FetchBuyLimits ->
                interactor.fetchBuyLimitsAndSupportedCryptoCurrencies(intent.fiatCurrency)
                    .subscribeBy(
                        onSuccess = { (pairs, transferLimits) ->
                            process(
                                SimpleBuyIntent.UpdatedBuyLimitsAndSupportedCryptoCurrencies(
                                    pairs,
                                    intent.asset,
                                    transferLimits
                                )
                            )
                            process(SimpleBuyIntent.NewCryptoCurrencySelected(intent.asset))
                        },
                        onError = { process(SimpleBuyIntent.ErrorIntent()) }
                    )
            is SimpleBuyIntent.FetchSupportedFiatCurrencies ->
                interactor.fetchSupportedFiatCurrencies()
                    .subscribeBy(
                        onSuccess = { process(it) },
                        onError = { process(SimpleBuyIntent.ErrorIntent()) }
                    )
            is SimpleBuyIntent.CancelOrder,
            is SimpleBuyIntent.CancelOrderAndResetAuthorisation -> (previousState.id?.let {
                interactor.cancelOrder(it)
            } ?: Completable.complete())
                .subscribeBy(
                    onComplete = { process(SimpleBuyIntent.OrderCanceled) },
                    onError = { process(SimpleBuyIntent.ErrorIntent()) }
                )
            is SimpleBuyIntent.CancelOrderIfAnyAndCreatePendingOne -> (previousState.id?.let {
                interactor.cancelOrder(it)
            } ?: Completable.complete()).thenSingle {
                processCreateOrder(
                    previousState.selectedCryptoAsset,
                    previousState.selectedPaymentMethod,
                    previousState.order,
                    previousState.recurringBuyFrequency
                )
            }.subscribeBy(
                onSuccess = {
                    process(it)
                },
                onError = {
                    process(SimpleBuyIntent.ErrorIntent())
                }
            )

            is SimpleBuyIntent.FetchKycState -> interactor.pollForKycState()
                .subscribeBy(
                    onSuccess = { process(it) },
                    onError = { /*never fails. will return SimpleBuyIntent.KycStateUpdated(KycState.FAILED)*/ }
                )

            is SimpleBuyIntent.FetchQuote -> interactor.fetchQuote(
                previousState.selectedCryptoAsset,
                previousState.order.amount
            ).subscribeBy(
                onSuccess = { process(it) },
                onError = { process(SimpleBuyIntent.ErrorIntent()) }
            )

            is SimpleBuyIntent.LinkBankTransferRequested -> interactor.linkNewBank(previousState.fiatCurrency)
                .subscribeBy(
                    onSuccess = { process(it) },
                    onError = { process(SimpleBuyIntent.ErrorIntent(ErrorState.LinkedBankNotSupported)) }
                )
            is SimpleBuyIntent.TryToLinkABankTransfer -> {
                interactor.eligiblePaymentMethodsTypes(previousState.fiatCurrency).map {
                    it.any { paymentMethod -> paymentMethod.paymentMethodType == PaymentMethodType.BANK_TRANSFER }
                }.subscribeBy(
                    onSuccess = { isEligibleToLinkABank ->
                        if (isEligibleToLinkABank) {
                            process(SimpleBuyIntent.LinkBankTransferRequested)
                        } else {
                            process(SimpleBuyIntent.ErrorIntent(ErrorState.LinkedBankNotSupported))
                        }
                    },
                    onError = {
                        process(SimpleBuyIntent.ErrorIntent(ErrorState.LinkedBankNotSupported))
                    }
                )
            }
            is SimpleBuyIntent.NewCryptoCurrencySelected -> interactor.exchangeRate(intent.asset)
                .subscribeBy(
                    onSuccess = { process(it) },
                    onError = { }
                )
            is SimpleBuyIntent.FetchWithdrawLockTime -> {
                require(previousState.selectedPaymentMethod != null)
                interactor.fetchWithdrawLockTime(
                    previousState.selectedPaymentMethod.paymentMethodType,
                    previousState.fiatCurrency
                )
                    .subscribeBy(
                        onSuccess = { process(it) },
                        onError = { }
                    )
            }
            is SimpleBuyIntent.BuyButtonClicked -> interactor.checkTierLevel()
                .subscribeBy(
                    onSuccess = { process(it) },
                    onError = { process(SimpleBuyIntent.ErrorIntent()) }
                )

            is SimpleBuyIntent.FetchPaymentDetails ->
                processGetPaymentMethod(intent.fiatCurrency, intent.selectedPaymentMethodId)

            is SimpleBuyIntent.FetchSuggestedPaymentMethod ->
                processGetPaymentMethod(intent.fiatCurrency, intent.selectedPaymentMethodId)

            is SimpleBuyIntent.PaymentMethodChangeRequested -> {
                if (intent.paymentMethod.isEligible && intent.paymentMethod is UndefinedPaymentMethod) {
                    process(SimpleBuyIntent.AddNewPaymentMethodRequested(intent.paymentMethod))
                } else {
                    process(SimpleBuyIntent.SelectedPaymentMethodUpdate(intent.paymentMethod))
                }
                null
            }
            is SimpleBuyIntent.MakePayment ->
                interactor.fetchOrder(intent.orderId)
                    .subscribeBy({
                        process(SimpleBuyIntent.ErrorIntent())
                    }, {
                        process(SimpleBuyIntent.OrderPriceUpdated(it.price))
                        if (it.attributes != null) {
                            handleOrderAttrs(it)
                        } else {
                            pollForOrderStatus()
                        }
                    })
            is SimpleBuyIntent.GetAuthorisationUrl ->
                interactor.pollForAuthorisationUrl(intent.orderId)
                    .subscribeBy(
                        onSuccess = {
                            when (it) {
                                is PollResult.FinalResult -> {
                                    it.value.attributes?.authorisationUrl?.let { url ->
                                        handleBankAuthorisationPayment(it.value.paymentMethodId, url)
                                    }
                                }
                                is PollResult.TimeOut -> process(
                                    SimpleBuyIntent.ErrorIntent(
                                        ErrorState.BankLinkingTimeout
                                    )
                                )
                                is PollResult.Cancel -> process(SimpleBuyIntent.ErrorIntent())
                            }
                        },
                        onError = {
                            process(SimpleBuyIntent.ErrorIntent())
                        })
            is SimpleBuyIntent.UpdatePaymentMethodsAndAddTheFirstEligible -> interactor.eligiblePaymentMethods(
                intent.fiatCurrency, null
            ).subscribeBy(
                onSuccess = {
                    process(it)
                    it.availablePaymentMethods.firstOrNull { it.isEligible }?.let { paymentMethod ->
                        process(SimpleBuyIntent.AddNewPaymentMethodRequested(paymentMethod))
                    }
                },
                onError = {
                    process(SimpleBuyIntent.ErrorIntent())
                }
            )
            is SimpleBuyIntent.ConfirmOrder -> processConfirmOrder(
                previousState.id,
                previousState.selectedPaymentMethod
            )
            is SimpleBuyIntent.FinishedFirstBuy -> null
            is SimpleBuyIntent.CheckOrderStatus -> interactor.pollForOrderStatus(
                previousState.id ?: throw IllegalStateException("Order Id not available")
            ).subscribeBy(
                onSuccess = {
                    if (it.state == OrderState.FINISHED) {
                        updatePersistingCountersForCompletedOrders()
                        process(SimpleBuyIntent.PaymentSucceeded)
                    } else if (it.state == OrderState.AWAITING_FUNDS || it.state == OrderState.PENDING_EXECUTION) {
                        process(SimpleBuyIntent.PaymentPending)
                    } else {
                        if (it.approvalErrorStatus != ApprovalErrorStatus.NONE) {
                            handleApprovalErrorState(it)
                        } else {
                            process(SimpleBuyIntent.ErrorIntent())
                        }
                    }
                },
                onError = {
                    process(SimpleBuyIntent.ErrorIntent())
                }
            )
            is SimpleBuyIntent.PaymentSucceeded -> {
                interactor.checkTierLevel().map { it.kycState != KycState.VERIFIED_AND_ELIGIBLE }.subscribeBy(
                    onSuccess = {
                        if (it) process(SimpleBuyIntent.UnlockHigherLimits)
                    },
                    onError = {
                        process(SimpleBuyIntent.ErrorIntent())
                    }
                )
            }
            is SimpleBuyIntent.AppRatingShown -> {
                ratingPrefs.hasSeenRatingDialog = true
                null
            }

            is SimpleBuyIntent.RecurringBuySelectedFirstTimeFlow ->
                createRecurringBuy(
                    previousState.selectedCryptoAsset,
                    previousState.order,
                    previousState.selectedPaymentMethod,
                    intent.recurringBuyFrequency
                ).subscribeBy(
                    onSuccess = {
                        process(SimpleBuyIntent.RecurringBuyCreatedFirstTimeFlow)
                    },
                    onError = {
                        process(SimpleBuyIntent.ErrorIntent())
                    }
                )

            else -> null
        }

    private fun handleApprovalErrorState(it: BuySellOrder) {
        when (it.approvalErrorStatus) {
            ApprovalErrorStatus.FAILED -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.ApprovedBankFailed)
            )
            ApprovalErrorStatus.REJECTED -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.ApprovedBankRejected)
            )
            ApprovalErrorStatus.DECLINED -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.ApprovedBankDeclined)
            )
            ApprovalErrorStatus.EXPIRED -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.ApprovedBankExpired)
            )
            ApprovalErrorStatus.UNKNOWN -> process(
                SimpleBuyIntent.ErrorIntent(ErrorState.ApprovedGenericError)
            )
            ApprovalErrorStatus.NONE -> {
                // do nothing
            }
        }.exhaustive
    }

    private fun processGetPaymentMethod(fiatCurrency: String, selectedPaymentMethodId: String?) =
        interactor.eligiblePaymentMethods(
            fiatCurrency,
            selectedPaymentMethodId
        ).flatMap { intent ->
            getEligibilityAndNextPaymentDateUseCase(Unit)
                .map { intent to it }
                .onErrorReturn { intent to emptyList() }
        }.subscribeBy(
            onSuccess = { (intent, eligibilityNextPaymentList) ->
                val eligibility = eligibilityNextPaymentList.flatMap { it.eligibleMethods }.distinct()
                process(SimpleBuyIntent.RecurringBuyEligibilityUpdated(eligibility, eligibilityNextPaymentList))
                process(intent)
            },
            onError = {
                process(SimpleBuyIntent.ErrorIntent())
            }
        )

    private fun handleOrderAttrs(order: BuySellOrder) {
        order.attributes?.everypay?.let {
            handleCardPayment(order)
        } ?: kotlin.run {
            if (!order.fiat.isOpenBankingCurrency()) {
                process(SimpleBuyIntent.CheckOrderStatus)
            } else {
                order.attributes?.authorisationUrl?.let {
                    handleBankAuthorisationPayment(order.paymentMethodId, it)
                } ?: process(SimpleBuyIntent.GetAuthorisationUrl(order.id))
            }
        }
    }

    private fun FiatValue.isOpenBankingCurrency() =
        this.currencyCode == "EUR" || this.currencyCode == "GBP"

    private fun processCreateOrder(
        selectedCryptoAsset: AssetInfo?,
        selectedPaymentMethod: SelectedPaymentMethod?,
        order: SimpleBuyOrder,
        recurringBuyFrequency: RecurringBuyFrequency
    ): Single<SimpleBuyIntent.OrderCreated> {
        return isFirstTimeBuyer(recurringBuyFrequency)
            .flatMap { isFirstTimeBuyer ->
                createOrder(
                    selectedCryptoAsset,
                    selectedPaymentMethod,
                    order,
                    recurringBuyFrequency.takeIf { it != RecurringBuyFrequency.ONE_TIME }
                )
                    .map { isFirstTimeBuyer to it }
            }.map { (isFirstTimeBuyer, buySellOrder) ->
                if (isFirstTimeBuyer && recurringBuyFrequency == RecurringBuyFrequency.ONE_TIME) {
                    prefs.isFirstTimeBuyer = false
                    process(SimpleBuyIntent.FinishedFirstBuy)
                }
                buySellOrder
            }
    }

    private fun isFirstTimeBuyer(recurringBuyFrequency: RecurringBuyFrequency): Single<Boolean> {
        return if (prefs.isFirstTimeBuyer && recurringBuyFrequency == RecurringBuyFrequency.ONE_TIME) {
            isFirstTimeBuyerUseCase(Unit)
                .onErrorReturn { false }
        } else {
            Single.just(false)
        }
    }

    private fun createRecurringBuy(
        selectedCryptoAsset: AssetInfo?,
        order: SimpleBuyOrder,
        selectedPaymentMethod: SelectedPaymentMethod?,
        recurringBuyFrequency: RecurringBuyFrequency
    ): Single<RecurringBuyOrder> =
        interactor.createRecurringBuyOrder(
            selectedCryptoAsset,
            order,
            selectedPaymentMethod,
            recurringBuyFrequency
        )
            .onErrorReturn {
                RecurringBuyOrder(RecurringBuyState.INACTIVE)
            }

    private fun createOrder(
        selectedCryptoAsset: AssetInfo?,
        selectedPaymentMethod: SelectedPaymentMethod?,
        order: SimpleBuyOrder,
        recurringBuyFrequency: RecurringBuyFrequency?
    ): Single<SimpleBuyIntent.OrderCreated> {
        require(selectedCryptoAsset != null) { "Missing Cryptocurrency" }
        require(order.amount != null) { "Missing amount" }
        require(selectedPaymentMethod != null) { "Missing selectedPaymentMethod" }
        return interactor.createOrder(
            cryptoAsset = selectedCryptoAsset,
            amount = order.amount,
            paymentMethodId = selectedPaymentMethod.concreteId(),
            paymentMethod = selectedPaymentMethod.paymentMethodType,
            isPending = true,
            recurringBuyFrequency = recurringBuyFrequency
        )
    }

    private fun confirmOrder(
        id: String?,
        selectedPaymentMethod: SelectedPaymentMethod?
    ): Single<BuySellOrder> {
        require(id != null) { "Order Id not available" }
        require(selectedPaymentMethod != null) { "selectedPaymentMethod missing" }
        return interactor.confirmOrder(
            orderId = id,
            paymentMethodId = selectedPaymentMethod.takeIf { it.isBank() }?.concreteId(),
            attributes = if (selectedPaymentMethod.isBank()) {
                SimpleBuyConfirmationAttributes(
                    callback = bankPartnerCallbackProvider.callback(BankPartner.YAPILY, BankTransferAction.PAY)
                )
            } else {
                cardActivators.firstOrNull {
                    selectedPaymentMethod?.partner == it.partner
                }?.paymentAttributes()
            },
            isBankPartner = selectedPaymentMethod.isBank()
        )
    }

    private fun isPaymentMethodSelectedAllowingRecurringBuys(
        eligibleList: List<EligibleAndNextPaymentRecurringBuy>,
        buySellOrder: BuySellOrder
    ): Boolean {
        val eligibility = eligibleList.flatMap { it.eligibleMethods }.distinct()
        return eligibility.contains(buySellOrder.paymentMethodType)
    }

    private fun processConfirmOrder(
        id: String?,
        selectedPaymentMethod: SelectedPaymentMethod?
    ): Disposable {
        return confirmOrder(id, selectedPaymentMethod).map { it }
            .subscribeBy(
                onSuccess = { buySellOrder ->
                    val orderCreatedSuccessfully = buySellOrder!!.state == OrderState.FINISHED
                    if (orderCreatedSuccessfully) {
                        updatePersistingCountersForCompletedOrders()
                    }
                    process(
                        SimpleBuyIntent.OrderCreated(
                            buySellOrder, shouldShowAppRating(orderCreatedSuccessfully)
                        )
                    )
                },
                onError = {
                    processOrderErrors(it)
                }
            )
    }

    private fun processOrderErrors(it: Throwable) {
        if (it is NabuApiException) {
            when (it.getErrorCode()) {
                NabuErrorCodes.DailyLimitExceeded -> process(
                    SimpleBuyIntent.ErrorIntent(ErrorState.DailyLimitExceeded)
                )
                NabuErrorCodes.WeeklyLimitExceeded -> process(
                    SimpleBuyIntent.ErrorIntent(ErrorState.WeeklyLimitExceeded)
                )
                NabuErrorCodes.AnnualLimitExceeded -> process(
                    SimpleBuyIntent.ErrorIntent(ErrorState.YearlyLimitExceeded)
                )
                NabuErrorCodes.PendingOrdersLimitReached -> process(
                    SimpleBuyIntent.ErrorIntent(ErrorState.ExistingPendingOrder)
                )
                else -> process(SimpleBuyIntent.ErrorIntent())
            }
        } else {
            process(SimpleBuyIntent.ErrorIntent())
        }
    }

    private fun updatePersistingCountersForCompletedOrders() {
        ratingPrefs.preRatingActionCompletedTimes = ratingPrefs.preRatingActionCompletedTimes + 1
        prefs.hasCompletedAtLeastOneBuy = true
    }

    private fun shouldShowAppRating(orderCreatedSuccessFully: Boolean): Boolean =
        ratingPrefs.preRatingActionCompletedTimes >= COMPLETED_ORDERS_BEFORE_SHOWING_APP_RATING &&
            !ratingPrefs.hasSeenRatingDialog && orderCreatedSuccessFully

    private fun pollForOrderStatus() {
        process(SimpleBuyIntent.CheckOrderStatus)
    }

    private fun handleCardPayment(order: BuySellOrder) {
        order.attributes?.everypay?.let { attrs ->
            if (attrs.paymentState == EverypayPaymentAttrs.WAITING_3DS &&
                order.state == OrderState.AWAITING_FUNDS
            ) {
                process(
                    SimpleBuyIntent.Open3dsAuth(
                        attrs.paymentLink,
                        EverypayCardActivator.redirectUrl
                    )
                )
                process(SimpleBuyIntent.ResetEveryPayAuth)
            } else {
                process(SimpleBuyIntent.CheckOrderStatus)
            }
        } ?: kotlin.run {
            process(SimpleBuyIntent.ErrorIntent()) // todo handle case of partner not supported
        }
    }

    private fun handleBankAuthorisationPayment(
        paymentMethodId: String,
        authorisationUrl: String
    ) {
        disposables += interactor.getLinkedBankInfo(paymentMethodId).subscribeBy(
            onSuccess = { linkedBank ->
                process(SimpleBuyIntent.AuthorisePaymentExternalUrl(authorisationUrl, linkedBank))
            },
            onError = {
                process(SimpleBuyIntent.ErrorIntent())
            }
        )
    }

    override fun onStateUpdate(s: SimpleBuyState) {
        serializer.update(s)
    }

    companion object {
        const val COMPLETED_ORDERS_BEFORE_SHOWING_APP_RATING = 1
    }
}
