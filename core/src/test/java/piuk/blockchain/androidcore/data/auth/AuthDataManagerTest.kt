package piuk.blockchain.androidcore.data.auth

import com.blockchain.api.services.AuthApiService
import com.blockchain.logging.CrashLogger
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.verifyNoMoreInteractions
import com.nhaarman.mockitokotlin2.verifyZeroInteractions
import com.nhaarman.mockitokotlin2.whenever
import info.blockchain.wallet.api.data.Status
import info.blockchain.wallet.api.data.WalletOptions
import info.blockchain.wallet.crypto.AESUtil
import info.blockchain.wallet.exceptions.InvalidCredentialsException
import io.reactivex.rxjava3.core.Observable
import junit.framework.TestCase.assertTrue
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import com.nhaarman.mockitokotlin2.mock
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import piuk.blockchain.android.testutils.RxTest
import piuk.blockchain.androidcore.data.access.AccessState
import piuk.blockchain.androidcore.utils.AESUtilWrapper
import piuk.blockchain.androidcore.utils.PersistentPrefs
import piuk.blockchain.androidcore.utils.PrngFixer
import retrofit2.Response
import java.util.concurrent.TimeUnit

class AuthDataManagerTest : RxTest() {

    private val prefsUtil: PersistentPrefs = mock()
    private val authApiService: AuthApiService = mock()
    private val walletAuthService: WalletAuthService = mock()
    private val accessState: AccessState = mock()
    private val aesUtilWrapper: AESUtilWrapper = mock()
    private val prngHelper: PrngFixer = mock()
    private val crashLogger: CrashLogger = mock()

    private lateinit var subject: AuthDataManager

    @Before
    fun setUp() {
        subject = AuthDataManager(
            prefsUtil,
            authApiService,
            walletAuthService,
            accessState,
            aesUtilWrapper,
            prngHelper,
            crashLogger
        )
    }

    @Test
    fun getEncryptedPayload() {
        // Arrange
        val mockResponseBody = mock<ResponseBody>()
        whenever(
            walletAuthService.getEncryptedPayload(
                anyString(),
                anyString()
            )
        ).thenReturn(Observable.just(Response.success(mockResponseBody)))
        // Act
        val observer = subject.getEncryptedPayload("1234567890", "1234567890").test()
        // Assert
        verify(walletAuthService).getEncryptedPayload(anyString(), anyString())
        observer.assertComplete()
        observer.assertNoErrors()
        assertTrue(observer.values()[0].isSuccessful)
    }

    @Test
    fun getSessionId() {
        // Arrange
        val sessionId = "SESSION_ID"
        whenever(walletAuthService.getSessionId(anyString()))
            .thenReturn(Observable.just(sessionId))
        // Act
        val testObserver = subject.getSessionId("1234567890").test()
        // Assert
        verify(walletAuthService).getSessionId(anyString())
        testObserver.assertComplete()
        testObserver.onNext(sessionId)
        testObserver.assertNoErrors()
    }

    @Test
    fun submitTwoFactorCode() {
        // Arrange
        val sessionId = "SESSION_ID"
        val guid = "GUID"
        val code = "123456"
        val responseBody = "{}".toResponseBody(("application/json").toMediaTypeOrNull())
        whenever(walletAuthService.submitTwoFactorCode(sessionId, guid, code))
            .thenReturn(Observable.just(responseBody))
        // Act
        val testObserver = subject.submitTwoFactorCode(sessionId, guid, code).test()
        // Assert
        verify(walletAuthService).submitTwoFactorCode(sessionId, guid, code)
        testObserver.assertComplete()
        testObserver.onNext(responseBody)
        testObserver.assertNoErrors()
    }

    @Test
    fun validatePinSuccessful() {
        val pin = "1234"
        val key = "SHARED_KEY"
        val guid = ""
        val encryptedPassword = "ENCRYPTED_PASSWORD"
        val decryptionKey = "DECRYPTION_KEY"
        val plaintextPassword = "PLAINTEXT_PASSWORD"
        val status = Status()

        status.success = decryptionKey
        whenever(prefsUtil.pinId).thenReturn(key)
        whenever(prefsUtil.encryptedPassword).thenReturn(encryptedPassword)
        whenever(prefsUtil.backupEnabled).thenReturn(true)
        whenever(prefsUtil.hasBackup()).thenReturn(true)
        whenever(prefsUtil.walletGuid).thenReturn(guid)
        whenever(walletAuthService.validateAccess(key, pin))
            .thenReturn(Observable.just(Response.success(status)))

        whenever(
            aesUtilWrapper.decrypt(
                encryptedPassword,
                decryptionKey,
                AESUtil.PIN_PBKDF2_ITERATIONS
            )
        ).thenReturn(plaintextPassword)

        // Act
        subject.validatePin(pin)
            .test()
            .assertComplete()
            .assertValue(plaintextPassword)
            .assertNoErrors()

        // Assert
        verify(accessState).setPin(pin)
        verify(accessState).isNewlyCreated = false
        verify(accessState).isRestored = false
        verifyNoMoreInteractions(accessState)
        verify(prefsUtil).pinId
        verify(prefsUtil).hasBackup()
        verify(prefsUtil).backupEnabled
        verify(prefsUtil).walletGuid

        verify(prefsUtil).encryptedPassword

        verify(prefsUtil).restoreFromBackup(anyString(), eq(aesUtilWrapper))

        verify(walletAuthService).validateAccess(key, pin)
        verifyNoMoreInteractions(walletAuthService)

        verify(aesUtilWrapper).decrypt(
            encryptedPassword,
            decryptionKey,
            AESUtil.PIN_PBKDF2_ITERATIONS
        )

        verifyNoMoreInteractions(aesUtilWrapper)
        verifyNoMoreInteractions(prefsUtil)

        verifyZeroInteractions(prngHelper)
    }

    @Test
    fun validatePinFailure() {
        // Arrange
        val pin = "1234"
        val key = "SHARED_KEY"

        val decryptionKey = "DECRYPTION_KEY"
        val status = Status()
        status.success = decryptionKey

        whenever(prefsUtil.pinId).thenReturn(key)
        whenever(walletAuthService.validateAccess(key, pin))
            .thenReturn(
                Observable.just(
                    Response.error(
                        403,
                        "{}".toResponseBody(("application/json").toMediaTypeOrNull())
                    )
                )
            )
        // Act
        val observer = subject.validatePin(pin).test()
        // Assert
        verify(accessState).setPin(pin)
        verifyNoMoreInteractions(accessState)
        verify(prefsUtil).pinId
        verifyNoMoreInteractions(prefsUtil)
        verify(walletAuthService).validateAccess(key, pin)
        verifyNoMoreInteractions(walletAuthService)
        verifyZeroInteractions(aesUtilWrapper)
        observer.assertNotComplete()
        observer.assertNoValues()
        observer.assertError(InvalidCredentialsException::class.java)
    }

    @Test
    fun createPinInvalid() {
        // Arrange
        val password = "PASSWORD"
        val pin = "123"

        // Act
        val observer = subject.createPin(password, pin).test()

        // Assert
        verifyZeroInteractions(accessState)
        verifyZeroInteractions(prefsUtil)
        verifyZeroInteractions(walletAuthService)
        verifyZeroInteractions(aesUtilWrapper)

        observer.assertNotComplete()
        observer.assertError(IllegalArgumentException::class.java)
    }

    @Test
    fun createPinSuccessful() {
        // Arrange
        val password = "PASSWORD"
        val pin = "1234"
        val encryptedPassword = "ENCRYPTED_PASSWORD"
        val status = Status()
        whenever(
            walletAuthService.setAccessKey(
                anyString(),
                anyString(),
                eq(pin)
            )
        ).thenReturn(Observable.just(Response.success(status)))
        whenever(
            aesUtilWrapper.encrypt(
                eq(password),
                anyString(),
                eq(AESUtil.PIN_PBKDF2_ITERATIONS)
            )
        ).thenReturn(encryptedPassword)
        whenever(prefsUtil.backupEnabled).thenReturn(true)
        whenever(prefsUtil.hasBackup()).thenReturn(false)

        // Act
        val observer = subject.createPin(password, pin).test()

        // Assert
        verify(accessState).setPin(pin)
        verifyNoMoreInteractions(accessState)
        verify(prngHelper).applyPRNGFixes()
        verifyNoMoreInteractions(prngHelper)
        verify(walletAuthService).setAccessKey(
            anyString(),
            anyString(),
            eq(pin)
        )
        verifyNoMoreInteractions(walletAuthService)
        verify(aesUtilWrapper).encrypt(
            eq(password),
            anyString(),
            eq(AESUtil.PIN_PBKDF2_ITERATIONS)
        )
        verifyNoMoreInteractions(aesUtilWrapper)
        verify(prefsUtil).encryptedPassword = encryptedPassword
        verify(prefsUtil).pinId = anyString()
        verify(prefsUtil).backupEnabled
        verify(prefsUtil).hasBackup()

        verify(prefsUtil).backupCurrentPrefs(anyString(), eq(aesUtilWrapper))
        verifyNoMoreInteractions(prefsUtil)
        observer.assertComplete()
        observer.assertNoErrors()
    }

    @Test
    fun createPinError() {
        // Arrange
        val password = "PASSWORD"
        val pin = "1234"
        whenever(
            walletAuthService.setAccessKey(
                anyString(),
                anyString(),
                eq(pin)
            )
        ).thenReturn(
            Observable.just(
                Response.error(
                    500,
                    "{}".toResponseBody(("application/json").toMediaTypeOrNull())
                )
            )
        )
        // Act
        val observer = subject.createPin(password, pin).test()
        // Assert
        verify(accessState).setPin(pin)
        verifyNoMoreInteractions(accessState)
        verify(prngHelper).applyPRNGFixes()
        verifyNoMoreInteractions(prngHelper)
        verify(walletAuthService).setAccessKey(
            anyString(),
            anyString(),
            eq(pin)
        )
        verifyNoMoreInteractions(walletAuthService)
        verifyZeroInteractions(aesUtilWrapper)
        verifyZeroInteractions(prefsUtil)
        observer.assertNotComplete()
        observer.assertError(Throwable::class.java)
    }

    @Test
    fun getWalletOptions() {
        // Arrange
        val walletOptions = WalletOptions()
        whenever(walletAuthService.getWalletOptions()).thenReturn(Observable.just(walletOptions))
        // Act
        val observer = subject.getWalletOptions().test()
        // Assert
        verify(walletAuthService).getWalletOptions()
        observer.assertComplete()
        observer.assertNoErrors()
        observer.assertValue(walletOptions)
    }

    /**
     * Getting encrypted payload returns error, should be caught by Observable and transformed into
     * [AuthDataManager.AUTHORIZATION_REQUIRED]
     */
    @Test
    fun startPollingAuthStatusError() {
        // Arrange
        val sessionId = "SESSION_ID"
        val guid = "GUID"
        whenever(walletAuthService.getEncryptedPayload(guid, sessionId))
            .thenReturn(Observable.error(Throwable()))
        // Act
        val testObserver = subject.startPollingAuthStatus(guid, sessionId).test()
        testScheduler.advanceTimeBy(3, TimeUnit.SECONDS)
        // Assert
        verify(walletAuthService).getEncryptedPayload(guid, sessionId)
        testObserver.assertComplete()
        testObserver.assertValue(AuthDataManager.AUTHORIZATION_REQUIRED)
        testObserver.assertNoErrors()
    }

    /**
     * Getting encrypted payload returns Auth Required, should be filtered out and emit no values.
     */
    @Test
    fun startPollingAuthStatusAccessRequired() {
        // Arrange
        val sessionId = "SESSION_ID"
        val guid = "GUID"
        val responseBody = ERROR_BODY.toResponseBody(("application/json").toMediaTypeOrNull())

        whenever(walletAuthService.getEncryptedPayload(guid, sessionId))
            .thenReturn(Observable.just(Response.error(500, responseBody)))
        // Act
        val testObserver = subject.startPollingAuthStatus(guid, sessionId).test()
        testScheduler.advanceTimeBy(2, TimeUnit.SECONDS)
        // Assert
        verify(walletAuthService).getEncryptedPayload(guid, sessionId)
        testObserver.assertNotComplete()
        testObserver.assertNoValues()
        testObserver.assertNoErrors()
    }

    @Test
    fun createCheckEmailTimer() {
        // Arrange

        // Act
        val testObserver = subject.createCheckEmailTimer().take(1).test()
        subject.timer = 1
        testScheduler.advanceTimeBy(2, TimeUnit.SECONDS)
        // Assert
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        testObserver.assertValue(1)
    }

    companion object {

        private const val ERROR_BODY = "{\n" +
            "\t\"authorization_required\": \"true\"\n" +
            "}"
    }
}