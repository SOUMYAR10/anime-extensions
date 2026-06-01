package eu.kanade.tachiyomi.animeextension.en.animenosub.extractors

import android.util.Base64
import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MoonExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val siteUrl: String,
) {
    private val playlistUtils by lazy { PlaylistUtils(client) }
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun videosFromUrl(url: String, prefix: String): List<Video> {
        return try {
            val userAgent = headers["User-Agent"]
                ?.takeIf(String::isNotBlank)
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

            val httpUrl = url.toHttpUrl()
            val host = httpUrl.host

            val videoId = httpUrl.pathSegments
                .lastOrNull { it.isNotEmpty() } ?: return emptyList()

            val detailsHeaders = headers.newBuilder()
                .set("Referer", "$siteUrl/")
                .set("Origin", siteUrl)
                .set("User-Agent", userAgent)
                .build()

            // Step 1: Fetch embed details to get embed_frame_url
            val detailsBody = client.newCall(
                GET("https://$host/api/videos/$videoId/embed/details", detailsHeaders),
            ).execute().bodyString()

            val detailsResponse = try {
                detailsBody.parseAs<DetailsResponse>(json)
            } catch (e: Exception) {
                Log.e("MoonExtractor", "Failed to parse details JSON: ${e.message}")
                return emptyList()
            }

            val embedUrl = detailsResponse.embedFrameUrl?.takeIf { it.isNotBlank() }
                ?: return emptyList()

            val embedHost = embedUrl.toHttpUrl().host
            // Get video path prefix (e.g. "3vm", "vest") from embed URL
            val embedPathPrefix = embedUrl.toHttpUrl().pathSegments.firstOrNull { it.isNotEmpty() } ?: "e"
            val embedReferer = "https://$embedHost/$embedPathPrefix/$videoId"

            val viewerId = generateUrlSafeId()
            val deviceId = generateUrlSafeId()

            // Headers for challenge/attest/playback on embed host
            val embedApiHeaders = headers.newBuilder()
                .set("Referer", embedReferer)
                .set("Origin", "https://$embedHost")
                .set("User-Agent", userAgent)
                .set("Content-Type", "application/json")
                .build()

            // Step 2: POST challenge to get nonce
            val challengeResponse = client.newCall(
                POST(
                    "https://$embedHost/api/videos/access/challenge",
                    embedApiHeaders,
                    "{}".toJsonRequestBody(),
                ),
            ).execute().parseAs<ChallengeResponse>(json)

            val challengeId = challengeResponse.challengeId
            val nonce = challengeResponse.nonce

            // Step 3: Generate ECDSA P-256 keypair, sign nonce, POST attest
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val keyPair = kpg.generateKeyPair()
            val ecPublicKey = keyPair.public as ECPublicKey

            // Sign the nonce bytes with the private key (SHA256withECDSA)
            val nonceBytes = decodeB64Url(nonce)
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(keyPair.private)
            signer.update(nonceBytes)
            val rawDerSig = signer.sign()
            // Convert DER signature to raw R||S (64 bytes) for the server
            val rawSig = derToRaw(rawDerSig)
            val signatureB64 = encodeB64Url(rawSig)

            // Build public key JWK
            val w = ecPublicKey.w
            val xBytes = unsignedBigIntBytes(w.affineX, 32)
            val yBytes = unsignedBigIntBytes(w.affineY, 32)
            val xB64 = encodeB64Url(xBytes)
            val yB64 = encodeB64Url(yBytes)

            val attestBody = AttestRequest(
                viewerId = viewerId,
                deviceId = deviceId,
                challengeId = challengeId,
                nonce = nonce,
                signature = signatureB64,
                publicKey = JwkPublicKey(
                    crv = "P-256",
                    ext = true,
                    keyOps = listOf("verify"),
                    kty = "EC",
                    x = xB64,
                    y = yB64,
                ),
            )

            val attestResponse = client.newCall(
                POST(
                    "https://$embedHost/api/videos/access/attest",
                    embedApiHeaders,
                    json.encodeToString(attestBody).toJsonRequestBody(),
                ),
            ).execute().parseAs<AttestResponse>(json)

            val fingerprintToken = attestResponse.token
                ?.takeIf(String::isNotBlank)
                ?: run {
                    Log.e("MoonExtractor", "Attest did not return a token")
                    return emptyList()
                }

            val fingerprintConfidence = attestResponse.confidence ?: 0.93

            // Step 4: POST playback with the server-issued token
            val playbackHeaders = headers.newBuilder()
                .set("Referer", embedReferer)
                .set("Origin", "https://$embedHost")
                .set("User-Agent", userAgent)
                .set("X-Embed-Origin", siteUrl.removePrefix("https://"))
                .set("X-Embed-Parent", "https://$host/e/$videoId")
                .set("X-Embed-Referer", "$siteUrl/")
                .build()

            val fingerprintBody = FingerprintRequest(
                fingerprint = FingerprintData(
                    token = fingerprintToken,
                    viewerId = viewerId,
                    deviceId = deviceId,
                    confidence = fingerprintConfidence,
                ),
            )

            val playbackUrl = "https://$embedHost/api/videos/$videoId/embed/playback"
            val response = client.newCall(
                POST(playbackUrl, playbackHeaders, json.encodeToString(fingerprintBody).toJsonRequestBody()),
            ).execute().parseAs<PlaybackResponse>(json)

            val masterUrl = (
                response.sources?.firstOrNull()?.let { it.url ?: it.file }
                    ?: response.playback?.let { playback ->
                        val decrypted = decryptPayload(playback)
                        decrypted.parseAs<InnerResponse>(json)
                            .sources?.firstOrNull()?.let { it.url ?: it.file }
                    }
                )?.takeIf(String::isNotBlank)
                ?: run {
                    Log.e("MoonExtractor", "No masterUrl found.")
                    return emptyList()
                }

            val videoHeaders = Headers.Builder()
                .set("Referer", "https://$embedHost/")
                .set("Origin", "https://$embedHost")
                .set("User-Agent", userAgent)
                .build()

            playlistUtils.extractFromHls(
                playlistUrl = masterUrl,
                masterHeaders = videoHeaders,
                videoHeaders = videoHeaders,
                videoNameGen = { quality ->
                    listOfNotNull(
                        prefix.trim().takeIf(String::isNotBlank),
                        "Moon -".takeIf { !prefix.contains("Moon", true) },
                        quality.trim(),
                    ).joinToString(" ")
                },
            )
        } catch (e: Exception) {
            Log.e("MoonExtractor", "MoonExtractor failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun decryptPayload(pb: PlaybackData): String {
        val keyBytes = decodeB64Url(pb.keyParts[0]) + decodeB64Url(pb.keyParts[1])
        val ivBytes = decodeB64Url(pb.iv)
        val cipherBytes = decodeB64Url(pb.payload)
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ivBytes))
        return cipher.doFinal(cipherBytes).toString(Charsets.UTF_8)
    }

    private fun decodeB64Url(input: String): ByteArray {
        val padding = when (input.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        return Base64.decode(input + padding, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun encodeB64Url(input: ByteArray): String = Base64.encodeToString(input, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** Generate a URL-safe base64 random ID (same format as viewer_hint from server) */
    private fun generateUrlSafeId(): String {
        val uuid = UUID.randomUUID().toString().replace("-", "")
        // Convert hex UUID to bytes then base64url — matches server's format
        val bytes = uuid.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return encodeB64Url(bytes)
    }

    /**
     * Convert a DER-encoded ECDSA signature to raw R||S format (64 bytes for P-256).
     * DER format: 0x30 [total-len] 0x02 [r-len] [r-bytes] 0x02 [s-len] [s-bytes]
     */
    private fun derToRaw(der: ByteArray): ByteArray {
        var offset = 2 // skip 0x30 and total length
        val rLen = der[offset + 1].toInt() and 0xFF
        offset += 2
        val rBytes = der.copyOfRange(offset, offset + rLen)
        offset += rLen
        val sLen = der[offset + 1].toInt() and 0xFF
        offset += 2
        val sBytes = der.copyOfRange(offset, offset + sLen)
        // Pad/trim to 32 bytes each
        return pad32(rBytes) + pad32(sBytes)
    }

    private fun pad32(b: ByteArray): ByteArray = when {
        b.size == 32 -> b
        b.size > 32 -> b.copyOfRange(b.size - 32, b.size) // trim leading zero
        else -> ByteArray(32 - b.size) + b // left-pad with zeros
    }

    private fun unsignedBigIntBytes(n: java.math.BigInteger, size: Int): ByteArray {
        val raw = n.toByteArray()
        return when {
            raw.size == size + 1 && raw[0] == 0.toByte() -> raw.copyOfRange(1, raw.size)
            raw.size < size -> ByteArray(size - raw.size) + raw
            else -> raw
        }
    }

    // ========================== Data Classes ==========================

    @Serializable
    data class DetailsResponse(
        @SerialName("embed_frame_url") val embedFrameUrl: String? = null,
    )

    @Serializable
    data class ChallengeResponse(
        @SerialName("challenge_id") val challengeId: String,
        val nonce: String,
        @SerialName("viewer_hint") val viewerHint: String? = null,
    )

    @Serializable
    data class AttestRequest(
        @SerialName("viewer_id") val viewerId: String,
        @SerialName("device_id") val deviceId: String,
        @SerialName("challenge_id") val challengeId: String,
        val nonce: String,
        val signature: String,
        @SerialName("public_key") val publicKey: JwkPublicKey,
    )

    @Serializable
    data class JwkPublicKey(
        val crv: String,
        val ext: Boolean,
        @SerialName("key_ops") val keyOps: List<String>,
        val kty: String,
        val x: String,
        val y: String,
    )

    @Serializable
    data class AttestResponse(
        val token: String? = null,
        val confidence: Double? = null,
        @SerialName("viewer_id") val viewerId: String? = null,
        @SerialName("device_id") val deviceId: String? = null,
    )

    @Serializable
    data class FingerprintRequest(val fingerprint: FingerprintData)

    @Serializable
    data class FingerprintData(
        val token: String,
        @SerialName("viewer_id") val viewerId: String,
        @SerialName("device_id") val deviceId: String,
        val confidence: Double,
    )

    @Serializable
    data class PlaybackResponse(
        val sources: List<VideoSource>? = null,
        val playback: PlaybackData? = null,
    )

    @Serializable
    data class PlaybackData(
        val iv: String,
        val payload: String,
        @SerialName("key_parts") val keyParts: List<String>,
    )

    @Serializable
    data class InnerResponse(val sources: List<VideoSource>? = null)

    @Serializable
    data class VideoSource(val file: String? = null, val url: String? = null)
}
