package dev.zain.releaseshelf.data

import java.security.Security
import net.schmizz.sshj.DefaultConfig
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Android ships a partial/stale "BC" JCE provider that cannot satisfy sshj's
 * Curve25519/X25519 path (`no such algorithm: X25519 for provider BC`).
 * Replace it with the full BouncyCastle jar and drop Curve25519 KEX as a fallback.
 */
object AndroidSshSupport {
    @Volatile
    private var providerInstalled = false

    fun ensureCryptoProviders() {
        if (providerInstalled) return
        synchronized(this) {
            if (providerInstalled) return
            // Remove every BC entry Android may have registered (often incomplete).
            while (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) != null) {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            }
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            providerInstalled = true
        }
    }

    fun clientConfig(): DefaultConfig {
        ensureCryptoProviders()
        val config = DefaultConfig()
        val withoutCurve25519 = config.keyExchangeFactories.filterNot { factory ->
            val name = factory.name
            name.contains("curve25519", ignoreCase = true) ||
                name.contains("x25519", ignoreCase = true)
        }
        if (withoutCurve25519.isNotEmpty()) {
            config.setKeyExchangeFactories(withoutCurve25519)
        }
        return config
    }
}
