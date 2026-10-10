package com.thakaan.audit

import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * AndroidKeyStore EC P-256 signer, StrongBox-backed when the device has it (iQOO 15: to be confirmed on the phone).
 * If a StrongBox key cannot be created it falls back to the TEE-backed keystore, and if that fails the caller should
 * use [SoftwareSigner] and the export says hardwareBackedKey=false. DEVICE-UNTESTED.
 */
class KeystoreSigner(private val alias: String = "thakaan_exposure_chain", context: android.content.Context? = null) : ChainSigner {
    private val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override var hardwareBacked: Boolean = false; private set

    init {
        if (!ks.containsAlias(alias)) {
            val strongBox = context?.packageManager?.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE) == true && Build.VERSION.SDK_INT >= 28
            val created = if (strongBox) tryCreate(true) else false
            if (!created) tryCreate(false)
        }
        hardwareBacked = runCatching {
            val key = ks.getKey(alias, null) as PrivateKey
            val info = java.security.KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, android.security.keystore.KeyInfo::class.java)
            info.securityLevel >= KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
        }.getOrDefault(false)
    }

    private fun tryCreate(strongBox: Boolean): Boolean = try {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        true
    } catch (_: Exception) { false }

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(ks.getKey(alias, null) as PrivateKey); update(data); sign() }

    override fun verify(data: ByteArray, sig: ByteArray): Boolean = verifyWith(ks.getCertificate(alias).publicKey, data, sig)

    override fun publicKeyBytes(): ByteArray = ks.getCertificate(alias).publicKey.encoded
}
