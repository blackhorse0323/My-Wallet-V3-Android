package com.blockchain.api.wallet

import com.blockchain.api.wallet.data.WalletSettingsDto
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

internal interface WalletApiInterface {

    @FormUrlEncoded
    @POST("wallet")
    fun fetchSettings(
        @Field("guid") guid: String,
        @Field("sharedKey") sharedKey: String,
        @Field("api_code") apiCode: String,
        @Field("method") method: String = METHOD_GET_INFO,
        @Field("format") format: String = "plain"
    ): Single<WalletSettingsDto>

    @FormUrlEncoded
    @POST("wallet")
    fun triggerAlert(
        @Field("guid") guid: String,
        @Field("sharedKey") sharedKey: String,
        @Field("method") method: String = METHOD_TRIGGER_ALERT
    ): Completable

    companion object {
        internal const val METHOD_GET_INFO = "get-info"
        internal const val METHOD_TRIGGER_ALERT = "trigger-alert"
    }
}