package piuk.blockchain.android.ui.shapeshift.detail

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.content.ContextCompat
import kotlinx.android.synthetic.main.activity_shapeshift_detail.*
import kotlinx.android.synthetic.main.toolbar_general.*
import piuk.blockchain.android.R
import piuk.blockchain.android.injection.Injector
import piuk.blockchain.android.ui.base.BaseMvpActivity
import piuk.blockchain.android.ui.shapeshift.models.TradeDetailUiState
import piuk.blockchain.android.util.extensions.toast
import javax.inject.Inject

class ShapeShiftDetailActivity : BaseMvpActivity<ShapeShiftDetailView, ShapeShiftDetailPresenter>(),
        ShapeShiftDetailView {

    @Suppress("MemberVisibilityCanPrivate", "unused")
    @Inject lateinit var shapeShiftDetailPresenter: ShapeShiftDetailPresenter

    override val depositAddress: String by lazy { intent.getStringExtra(EXTRA_DEPOSIT_ADDRESS) }

    init {
        Injector.getInstance().presenterComponent.inject(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shapeshift_detail)
        setupToolbar(toolbar_general, R.string.shapeshift_in_progress_title)


        onViewReady()
    }

    override fun updateDeposit(label: String, amount: String) {
        textview_deposit_title.text = label
        textview_deposit_amount.text = amount
    }

    override fun updateReceive(label: String, amount: String) {
        textview_receive_title.text = label
        textview_receive_amount.text = amount
    }

    override fun updateExchangeRate(exchangeRate: String) {
        textview_rate_value.text = exchangeRate
    }

    override fun updateTransactionFee(displayString: String) {
        textview_transaction_fee_amount.text = displayString
    }

    override fun updateOrderId(displayString: String) {
        textview_order_id_amount.text = displayString
    }

    override fun showToast(message: Int, type: String) = toast(message, type)

    override fun finishPage() = finish()

    @SuppressLint("StringFormatMatches")
    override fun updateUi(uiState: TradeDetailUiState) {
        setupToolbar(toolbar_general, uiState.title)
        textview_current_stage.setText(uiState.heading)
        textview_current_word_step.text = uiState.message
        imageview_progress.setImageDrawable(ContextCompat.getDrawable(this, uiState.icon))
    }

    override fun createPresenter() = shapeShiftDetailPresenter

    override fun getView() = this

    companion object {

        private const val EXTRA_DEPOSIT_ADDRESS = "piuk.blockchain.android.EXTRA_DEPOSIT_ADDRESS"

        fun start(context: Context, depositAddress: String) {
            val intent = Intent(context, ShapeShiftDetailActivity::class.java).apply {
                putExtra(EXTRA_DEPOSIT_ADDRESS, depositAddress)
            }
            context.startActivity(intent)
        }

    }

}