package dev.uednd.hydros.data.ble

import dev.uednd.hydros.data.ble.DeviceSigner


object BleSignBuilder {


    private fun rawOf(params: Map<String, String?>): String =
        params.keys.sorted().joinToString("") { k -> k + (params[k] ?: return@joinToString "") }


    fun macType(type: Int, a1: Int): String =
        String.format("%02x%02x", type and 0xFF, a1 and 0xFF)


    fun signRateOrder(
        loginCode: String,
        telephone: String,
        deviceId: String,
        xfModel: String,
        randomNumber: String
    ): String? = DeviceSigner.signParams(
        loginCode,
        rawOf(
            mapOf(
                "telephone" to telephone,
                "deviceId" to deviceId,
                "xfModel" to xfModel,
                "randomNumber" to randomNumber.lowercase()
            )
        )
    )


    fun signUpload(
        loginCode: String,
        telephone: String,
        xfData: String
    ): String? = DeviceSigner.signParams(
        loginCode,
        rawOf(
            mapOf(
                "loginCode" to loginCode,
                "telephone" to telephone,
                "xfData" to xfData.lowercase()
            )
        )
    )


    fun signFail(
        loginCode: String,
        telephone: String,
        consumeDate: String
    ): String? = DeviceSigner.signParams(
        loginCode,
        rawOf(
            mapOf(
                "consumeDate" to consumeDate,
                "loginCode" to loginCode,
                "telephone" to telephone
            )
        )
    )
}
