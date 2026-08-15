package com.samourai.sentinel.ui.dojo

import com.google.gson.annotations.SerializedName
import com.samourai.sentinel.helpers.toJSON

/**
 * Response shape of the Dojo Bay community directory
 * (http://dojobayeryasshgghz537de5ckgd5hhi4z5sdeil3roeh65fwhdnu2yd.onion/dojos.json).
 */
data class CommunityDojoDirectory(
    @SerializedName("generated_at")
    val generatedAt: String? = null,
    @SerializedName("nodes")
    val nodes: List<CommunityDojoNode>? = null
)

data class CommunityDojoNode(
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("network")
    val network: String? = null,
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("status")
    val status: String? = null,
    @SerializedName("jurisdiction")
    val jurisdiction: String? = null,
    @SerializedName("country")
    val country: String? = null,
    @SerializedName("version")
    val version: String? = null,
    @SerializedName("checked_at")
    val checkedAt: String? = null,
    @SerializedName("payload")
    val payload: CommunityDojoPayload? = null
) {
    val isOnline: Boolean
        get() = status.equals("active", ignoreCase = true)

    /**
     * The pairing payload as the exact `{"pairing":{...}}` JSON string that
     * [DojoUtility.validate] and the rest of the existing pairing flow expect,
     * built from the same [Pairing] model used for manual/QR pairing.
     */
    fun pairingPayloadJson(): String? {
        val pairing = payload?.pairing ?: return null
        return DojoPairing(pairing = pairing).toJSON()
    }

    /**
     * Renders [country] (an ISO 3166-1 alpha-2 code, e.g. "SG") as its Unicode
     * flag emoji by combining the two Regional Indicator Symbol letters - null
     * for anything that isn't a plain two-letter code, rather than showing a
     * placeholder glyph for bad directory data.
     */
    val flagEmoji: String?
        get() {
            val code = country?.trim()?.uppercase() ?: return null
            if (code.length != 2 || code.any { it !in 'A'..'Z' }) return null
            val regionalIndicatorBase = 0x1F1E6 // Regional Indicator Symbol Letter A
            return code.map { letter ->
                String(Character.toChars(regionalIndicatorBase + (letter - 'A')))
            }.joinToString("")
        }
}

data class CommunityDojoPayload(
    @SerializedName("pairing")
    val pairing: Pairing? = null
)
