/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.matrix.androidsdk.crypto.verification

import android.content.Context
import org.matrix.androidsdk.MXSession
import org.matrix.androidsdk.crypto.data.MXDeviceInfo
import org.matrix.androidsdk.crypto.data.MXKey
import org.matrix.androidsdk.crypto.data.MXUsersDevicesMap
import org.matrix.androidsdk.rest.callback.ApiCallback
import org.matrix.androidsdk.rest.model.Event
import org.matrix.androidsdk.rest.model.MatrixError
import org.matrix.androidsdk.rest.model.crypto.*
import org.matrix.androidsdk.util.Log
import org.matrix.olm.OlmSAS
import org.matrix.olm.OlmUtility
import kotlin.properties.Delegates

/**
 * Represents an ongoing interactive key verification between two devices.
 */
abstract class SASVerificationTransaction(transactionId: String,
                                          otherUserID: String,
                                          otherDevice: String?,
                                          isIncoming: Boolean,
                                          val autoAccept: Boolean = true) :
        VerificationTransaction(transactionId, otherUserID, otherDevice, isIncoming) {

    companion object {
        val LOG_TAG = SASVerificationTransaction::javaClass.name

        //ordered by preferred order
        val KNOWN_AGREEMENT_PROTOCOLS = listOf(MXKey.KEY_CURVE_25519_TYPE)
        //ordered by preferred order
        val KNOWN_HASHES = listOf("sha256")
        //ordered by preferred order
        val KNOWN_MAC = listOf("hmac-sha256")

        val KNOWN_SHORT_CODES = listOf(/*KeyVerificationStart.SAS_MODE_EMOJI,*/ KeyVerificationStart.SAS_MODE_DECIMAL)

    }

    enum class SASVerificationTxState {
        None,
        // I have started a verification request
        SendingStart,
        Started,
        // Other user/device sent me a request
        OnStarted,
        // I have accepted a request started by the other user/device
        SendingAccept,
        Accepted,
        // My request has been accepted by the other user/device
        OnAccepted,
        // I have sent my public key
        SendingKey,
        KeySent,
        // The other user/device has sent me his public key
        OnKeyReceived,
        // Short code is ready to be displayed
        ShortCodeReady,
        // I have compared the code and manually said that they match
        ShortCodeAccepted,

        SendingMac,
        MacSent,
        Verifying,
        Verified,

        //Global: The verification has been cancelled (by me or other), see cancelReason for details
        Cancelled,
        OnCancelled
    }

    var state by Delegates.observable(SASVerificationTxState.None) { _, _, new ->
        //        println("$property has changed from $old to $new")
        listeners.forEach {
            try {
                it.transactionUpdated(this)
            } catch (e: Throwable) {
                Log.e(LOG_TAG, "## Error while notifying listeners", e)
            }
        }
        if (new == SASVerificationTxState.Cancelled || new == SASVerificationTxState.OnCancelled) {
            releaseSAS()
        }
    }

    var cancelledReason: CancelCode? = null

    private var olmSas: OlmSAS? = null

    var startReq: KeyVerificationStart? = null
    var accepted: KeyVerificationAccept? = null
    var otherKey: String? = null
    var shortCodeBytes: ByteArray? = null

    var myMac: KeyVerificationMac? = null
    var theirMac: KeyVerificationMac? = null

    fun getSAS(): OlmSAS {
        if (olmSas == null) olmSas = OlmSAS()
        return olmSas!!
    }

    //To override finalize(), all you need to do is simply declare it, without using the override keyword:
    protected fun finalize() {
        releaseSAS()
    }

    private fun releaseSAS() {
        // finalization logic
        olmSas?.releaseSas()
        olmSas = null
    }


    /**
     * To be called by the client when the user has verified that
     * both short codes do match
     */
    fun userHasVerifiedShortCode(session: MXSession) {
        if (state != SASVerificationTxState.ShortCodeReady) {
            //ignore and cancel?
            Log.e(LOG_TAG, "## Accepted short code from invalid state $state")
            cancel(session, CancelCode.UnexpectedMessage)
            return
        }

        state = SASVerificationTxState.ShortCodeAccepted
        //Alice and Bob’ devices calculate the HMAC of their own device keys and a comma-separated,
        // sorted list of the key IDs that they wish the other user to verify,
        //the shared secret as the input keying material, no salt, and with the input parameter set to the concatenation of:
        // - the string “MATRIX_KEY_VERIFICATION_MAC”,
        // - the Matrix ID of the user whose key is being MAC-ed,
        // - the device ID of the device sending the MAC,
        // - the Matrix ID of the other user,
        // - the device ID of the device receiving the MAC,
        // - the transaction ID, and
        // - the key ID of the key being MAC-ed, or the string “KEY_IDS” if the item being MAC-ed is the list of key IDs.

//        const keyId = `ed25519:${this._baseApis.deviceId}`;
//        const mac = {};
//        const baseInfo = "MATRIX_KEY_VERIFICATION_MAC"
//        + this._baseApis.getUserId() + this._baseApis.deviceId
//        + this.userId + this.deviceId
//        + this.transactionId;
//
//        mac[keyId] = olmSAS.calculate_mac(
//                this._baseApis.getDeviceEd25519Key(),
//                baseInfo + keyId,
//                );
//        const keys = olmSAS.calculate_mac(
//                keyId,
//        baseInfo + "KEY_IDS",
//        );
        val baseInfo = "MATRIX_KEY_VERIFICATION_MAC" +
                session.myUserId + session.crypto!!.myDevice.deviceId +
                otherUserID + otherDevice +
                transactionId

        val keyId = "ed25519:${session.crypto!!.myDevice.deviceId}"
        val macBytes = getSAS().calculateMac(session.crypto!!.myDevice.fingerprint(), baseInfo + keyId)
        val keysBytes = getSAS().calculateMac(keyId, baseInfo + "KEY_IDS")

        if (macBytes == null || keysBytes == null) {
            //Should not happen
            Log.e(LOG_TAG, "## SAS verification [$transactionId] failed to send KeyMac, empty key hashes.")
            cancel(session, CancelCode.UnexpectedMessage)
            return
        }

        val macString = String(macBytes, Charsets.UTF_8)
        val keyStrings = String(keysBytes, Charsets.UTF_8)

        val macMsg = KeyVerificationMac.new(transactionId,
                mapOf(Pair(keyId, macString)),
                keyStrings
        )
        myMac = macMsg
        state = SASVerificationTxState.SendingMac
        sendToOther(Event.EVENT_TYPE_KEY_VERIFICATION_MAC, macMsg, session, SASVerificationTxState.MacSent, CancelCode.User) {
            if (state == SASVerificationTxState.SendingMac) {
                //It is possible that we receive the next event before this one :/, in this case we should keep state
                state = SASVerificationTxState.MacSent
            }
        }

        //Do I already have their Mac?
        if (theirMac != null) {
            verifyMacs(session)
        } //if not wait for it

    }

    override fun acceptToDeviceEvent(session: MXSession, senderId: String, event: SendToDeviceObject) {
        when (event) {
            is KeyVerificationStart -> onVerificationStart(session, event)
            is KeyVerificationAccept -> onVerificationAccept(session, event)
            is KeyVerificationKey -> onKeyVerificationKey(session, senderId, event)
            is KeyVerificationMac -> onKeyVerificationMac(session, event)
            else -> {
                //nop
            }
        }
    }

    abstract fun onVerificationStart(session: MXSession, startReq: KeyVerificationStart)

    abstract fun onVerificationAccept(session: MXSession, accept: KeyVerificationAccept)

    abstract fun onKeyVerificationKey(session: MXSession, userId: String, vKey: KeyVerificationKey)

    abstract fun onKeyVerificationMac(session: MXSession, vKey: KeyVerificationMac)

    protected fun verifyMacs(session: MXSession) {
        state = SASVerificationTxState.Verifying
        //Alice and Bob’ devices calculate the HMAC of their own device keys and a comma-separated,
        // sorted list of the key IDs that they wish the other user to verify,
        //the shared secret as the input keying material, no salt, and with the input parameter set to the concatenation of:
        // - the string “MATRIX_KEY_VERIFICATION_MAC”,
        // - the Matrix ID of the user whose key is being MAC-ed,
        // - the device ID of the device sending the MAC,
        // - the Matrix ID of the other user,
        // - the device ID of the device receiving the MAC,
        // - the transaction ID, and
        // - the key ID of the key being MAC-ed, or the string “KEY_IDS” if the item being MAC-ed is the list of key IDs.


        session.crypto!!.getDeviceInfo(otherUserID, otherDevice, object : ApiCallback<MXDeviceInfo> {
            override fun onSuccess(info: MXDeviceInfo) {
                val otherDeviceKey = info.fingerprint()
                session.crypto!!.decryptingThreadHandler.post {

                    //mmmm could have been canceled by other meanwhile?
                    if (state == SASVerificationTxState.OnCancelled || state == SASVerificationTxState.Cancelled) {
                        //ignore
                        return@post
                    }

                    val baseInfo = "MATRIX_KEY_VERIFICATION_MAC" +
                            otherUserID + otherDevice +
                            session.myUserId + session.crypto!!.myDevice.deviceId +
                            transactionId

                    val keyId = "ed25519:${otherDevice}"

                    val macBytes = getSAS().calculateMac(otherDeviceKey, baseInfo + keyId)

                    val keysBytes = getSAS().calculateMac(keyId, baseInfo + "KEY_IDS")
                    if (macBytes == null || keysBytes == null) {
                        //Should not happen
                        Log.e(LOG_TAG, "## SAS verification [$transactionId] failed to send KeyMac, empty key hashes.")
                        cancel(session, CancelCode.UnexpectedMessage)
                        return@post
                    }

                    val macString = String(macBytes, Charsets.UTF_8)
                    val keyStrings = String(keysBytes, Charsets.UTF_8)
//
//        val keyId = "ed25519:${otherDevice}"
//        //baseInfo = "MATRIX_KEY_VERIFICATION_MAC" + this.userId + this.deviceId + this._baseApis.getUserId() + this._baseApis.deviceId + this.transactionId;
//
//
//        val macKey = getSAS().calculateMac(session.crypto!!.myDevice.identityKey(), baseInfo + keyId)
//        val keysMac = hashUsingAgreedHashMethod(
//                String(getSAS().calculateMac(keyId, baseInfo + "KEY_IDS"), Charsets.UTF_8))
                    //Check
                    if (theirMac!!.keys != keyStrings) {
                        //WRONG!
                        cancel(session, CancelCode.MismatchedKeys)
                        return@post
                    }

                    if (macString != theirMac?.mac?.get(keyId)) {
                        cancel(session, CancelCode.MismatchedKeys)
                        return@post
                    }


                    session.crypto!!.setDeviceVerification(MXDeviceInfo.DEVICE_VERIFICATION_VERIFIED,
                            otherDevice,
                            otherUserID,
                            object : ApiCallback<Void> {


                                override fun onSuccess(info: Void?) {
                                    //We good
                                    state = SASVerificationTxState.Verified
                                }

                                override fun onUnexpectedError(e: java.lang.Exception?) {
                                    Log.d(LOG_TAG, "## SAS verification [$transactionId] failed in state : $state")
                                }

                                override fun onNetworkError(e: java.lang.Exception?) {
                                    Log.d(LOG_TAG, "## SAS verification [$transactionId] failed in state : $state")
                                }

                                override fun onMatrixError(e: MatrixError?) {
                                    Log.d(LOG_TAG, "## SAS verification [$transactionId] failed in state : $state")
                                }

                            })
                }
            }

            override fun onUnexpectedError(e: java.lang.Exception?) {
                cancel(session, CancelCode.UnexpectedMessage)
                Log.e(LOG_TAG, "## SAS verification failed to get info for device $otherDevice ")
            }

            override fun onNetworkError(e: java.lang.Exception?) {
                cancel(session, CancelCode.UnexpectedMessage)
                Log.e(LOG_TAG, "## SAS verification failed to get info for device $otherDevice ")
            }

            override fun onMatrixError(e: MatrixError?) {
                cancel(session, CancelCode.UnexpectedMessage)
                Log.e(LOG_TAG, "## SAS verification failed to get info for device $otherDevice ")
            }

        })

    }


    override fun cancel(session: MXSession, code: CancelCode) {
        cancelledReason = code
        state = SASVerificationTxState.Cancelled
        VerificationManager.cancelTransaction(session,
                this.transactionId,
                this.otherUserID,
                this.otherDevice ?: "",
                code)
    }

    protected fun sendToOther(type: String,
                              keyToDevice: Any,
                              session: MXSession,
                              nextState: SASVerificationTxState,
                              onErrorReason: CancelCode,
                              onDone: (() -> Unit)?) {
        val contentMap = MXUsersDevicesMap<Any>()
        contentMap.setObject(keyToDevice, otherUserID, otherDevice)

        session.cryptoRestClient.sendToDevice(type, contentMap, transactionId, object : ApiCallback<Void> {
            override fun onSuccess(info: Void?) {
                Log.d(LOG_TAG, "## SAS verification [$transactionId] toDevice type '$type' success.")
                if (onDone != null) {
                    onDone()
                } else {
                    state = nextState
                }
            }

            override fun onUnexpectedError(e: Exception?) {
                Log.d(LOG_TAG, "## SAS verification [$transactionId] failed in state : $state")
                cancel(session, onErrorReason)
            }

            override fun onNetworkError(e: Exception?) {
                Log.d(LOG_TAG, "## SAS verification [$transactionId] failed in state : $state")
                cancel(session, onErrorReason)
            }

            override fun onMatrixError(e: MatrixError?) {
                Log.d(LOG_TAG, "## SAS verification [$transactionId] failed in state : $state")
                cancel(session, onErrorReason)
            }
        })
    }

    fun getShortCodeRepresentation(short_authentication_string: String): String? {
        if (shortCodeBytes == null) {
            return null
        }
        if (KeyVerificationStart.SAS_MODE_DECIMAL == short_authentication_string) {
            if (shortCodeBytes!!.size < 5) return null
            return getDecimalCodeRepresentation(shortCodeBytes!!)
        } else if (KeyVerificationStart.SAS_MODE_EMOJI == short_authentication_string) {
            if (shortCodeBytes!!.size < 6) return null
            return getEmojiCodeRepresentation(shortCodeBytes!!, null)?.joinToString(" ") { it.emoji }
        } else {
            return null
        }
    }

    fun supportsEmoji(): Boolean {
        return this.accepted?.short_authentication_string?.contains(KeyVerificationStart.SAS_MODE_EMOJI) == true
    }

    fun supportsDecimal(): Boolean {
        return this.accepted?.short_authentication_string?.contains(KeyVerificationStart.SAS_MODE_DECIMAL) == true
    }

    protected fun hashUsingAgreedHashMethod(toHash: String): String? {
        if ("sha256".toLowerCase() == accepted?.hash?.toLowerCase()) {
            val olmUtil = OlmUtility()
            val hashBytes = olmUtil.sha256(toHash)
            olmUtil.releaseUtility()
            return hashBytes
        }
        return null
    }

    /**
     * decimal: generate five bytes by using HKDF.
     * Take the first 13 bits and convert it to a decimal number (which will be a number between 0 and 8191 inclusive),
     * and add 1000 (resulting in a number between 1000 and 9191 inclusive).
     * Do the same with the second 13 bits, and the third 13 bits, giving three 4-digit numbers.
     * In other words, if the five bytes are B0, B1, B2, B3, and B4, then the first number is (B0 << 5 | B1 >> 3) + 1000,
     * the second number is ((B1 & 0x7) << 10 | B2 << 2 | B3 >> 6) + 1000, and the third number is ((B3 & 0x3f) << 7 | B4 >> 1) + 1000.
     * (This method of converting 13 bits at a time is used to avoid requiring 32-bit clients to do big-number arithmetic,
     * and adding 1000 to the number avoids having clients to worry about properly zero-padding the number when displaying to the user.)
     * The three 4-digit numbers are displayed to the user either with dashes (or another appropriate separator) separating the three numbers,
     * or with the three numbers on separate lines.
     */
    public fun getDecimalCodeRepresentation(byteArray: ByteArray): String? {
        val b0 = byteArray[0].toInt().and(0xff) //need unsigned byte
        val b1 = byteArray[1].toInt().and(0xff) //need unsigned byte
        val b2 = byteArray[2].toInt().and(0xff) //need unsigned byte
        val b3 = byteArray[3].toInt().and(0xff) //need unsigned byte
        val b4 = byteArray[4].toInt().and(0xff) //need unsigned byte
        //(B0 << 5 | B1 >> 3) + 1000
        val first = (b0.shl(5) or b1.shr(3)) + 1000
        //((B1 & 0x7) << 10 | B2 << 2 | B3 >> 6) + 1000
        val second = ((b1 and 0x7).shl(10) or b2.shl(2) or b3.shr(6)) + 1000
        //((B3 & 0x3f) << 7 | B4 >> 1) + 1000
        val third = ((b3 and 0x3f).shl(7) or b4.shr(1)) + 1000
        return "$first $second $third"
    }


    /**
     * emoji: generate six bytes by using HKDF.
     * Split the first 42 bits into 7 groups of 6 bits, as one would do when creating a base64 encoding.
     * For each group of 6 bits, look up the emoji from Appendix A corresponding
     * to that number 7 emoji are selected from a list of 64 emoji (see Appendix A)
     */
    fun getEmojiCodeRepresentation(byteArray: ByteArray, context: Context?): List<VerificationEmoji.EmojiRepresentation>? {
        val b0 = byteArray[0].toInt().and(0xff)
        val b1 = byteArray[1].toInt().and(0xff)
        val b2 = byteArray[2].toInt().and(0xff)
        val b3 = byteArray[3].toInt().and(0xff)
        val b4 = byteArray[4].toInt().and(0xff)
        val b5 = byteArray[5].toInt().and(0xff)
        return listOf(
                VerificationEmoji.getEmojiForCode((b0 and 0xFC).shr(2), context)!!,
                VerificationEmoji.getEmojiForCode((b0 and 0x3).shl(4) or (b1 and 0xF0).shr(4), context)!!,
                VerificationEmoji.getEmojiForCode((b1 and 0xF).shl(2) or (b2 and 0xC0).shr(6), context)!!,
                VerificationEmoji.getEmojiForCode((b2 and 0x3F), context)!!,

                VerificationEmoji.getEmojiForCode((b3 and 0xFC).shr(2), context)!!,
                VerificationEmoji.getEmojiForCode((b3 and 0x3).shl(4) or (b4 and 0xF0).shr(4), context)!!,
                VerificationEmoji.getEmojiForCode((b4 and 0xF).shl(2) or (b5 and 0xC0).shr(6), context)!!
        )
    }

}