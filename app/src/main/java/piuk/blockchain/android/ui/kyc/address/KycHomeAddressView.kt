package piuk.blockchain.android.ui.kyc.address

import androidx.annotation.StringRes
import piuk.blockchain.android.ui.kyc.address.models.AddressModel
import piuk.blockchain.android.ui.kyc.profile.models.ProfileModel
import io.reactivex.rxjava3.core.Observable
import piuk.blockchain.android.ui.base.View

interface KycHomeAddressView : View {

    val profileModel: ProfileModel

    val address: Observable<AddressModel>

    fun setButtonEnabled(enabled: Boolean)

    fun showErrorToast(@StringRes message: Int)

    fun showInvalidPostcode()

    fun dismissProgressDialog()

    fun showProgressDialog()

    fun finishPage()

    fun continueToVeriffSplash(countryCode: String)

    fun continueToTier2MoreInfoNeeded(countryCode: String)

    fun tier1Complete()
    fun onSddVerified()

    fun restoreUiState(
        line1: String,
        line2: String?,
        city: String,
        state: String?,
        postCode: String,
        countryName: String
    )
}
