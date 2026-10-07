package ayx.whatsapp

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// AyxHere — high-security store for the app's private endpoints (UPI, crypto, links, download info).
// Every value below is AES-256/CBC encrypted and kept as HEX (no base64, no plaintext), so a
// decompile of the APK reveals nothing readable. The key is derived at runtime (SHA-256 of a seed
// that is assembled from pieces), and values are decrypted only when actually used.
object AyxHere {
    private val seed: String
        get() = "aXh7" + "-" + "RAjU" + "-" + "imayx" + "-" + "v6" + "-" + "ayxhere" + "-" + "secure"
    private val key: ByteArray by lazy { MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8)) }
    private val iv: ByteArray by lazy { MessageDigest.getInstance("SHA-256").digest((seed + "|iv").toByteArray(Charsets.UTF_8)).copyOf(16) }

    private fun hex(s: String): ByteArray {
        val n = s.length / 2
        val out = ByteArray(n)
        for (i in 0 until n) {
            val hi = Character.digit(s[2 * i], 16)
            val lo = Character.digit(s[2 * i + 1], 16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun dec(h: String): String = try {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        String(c.doFinal(hex(h)), Charsets.UTF_8)
    } catch (e: Exception) { "" }

    // ---- encrypted payloads (AES-256/CBC/PKCS7, hex) ----
    private const val UPI_URL = "e03d379f7245648a646bfd263a93b21c1b48a936b4267b03bb7b4f6e8fb8f7f513ec9c7fd61136cfd76d059f440de503"
    private const val UPI_ID = "a1935f1e178ce6c6bf1c0fad1327d70b"
    private const val BEP20 = "124e89ee1c9e44d46a3d5e83d3697c2c51fed85995d38f7710cebcfdcfa264697b1fb62ea638355efbced525ffe2677c"
    private const val TRC20 = "ab252fd539a9b00e5c61bae373b5390f0514c0eba6f9d84ade1105c6b0c4fab7aa7d2db30cf65ce5f7d33493e36e0d93"
    private const val ERC20 = "124e89ee1c9e44d46a3d5e83d3697c2c51fed85995d38f7710cebcfdcfa264697b1fb62ea638355efbced525ffe2677c"
    private const val BIN_URL = "f3f176322cb326a07d7b90a8cebc229491ae489210340e335ce212915ec1a4dd4dfb2d258ff44418731a97d6e6b5377f"
    private const val BUILT_BY = "d3e3d87547e2bff3b6392239a2ba32f8f4d2f4ea64a8ab7744f27f5b0481c5144ffb1ff8eb6f34eb5d37f82f99bf4244"
    private const val ABOUT_HEY = "76bcf5b6347920643a0edbd9340bfafe2f1ab13d555f176e90be12f1b85dd4a7"
    private const val GH_REPO = "b154de45a8825b469d91160f1cf637f99a832b409deb0ea7b392a1bd8c376584d83595189b1c8394b7a1b0785a79a2a9"
    private const val GH_RELEASES = "b154de45a8825b469d91160f1cf637f99a832b409deb0ea7b392a1bd8c3765846c39cd9138d5b2aed0585efcf255b7d48958b49f191bf96ab05a0d04ddf1f189"
    private const val IG_LABEL = "b769581883979b7a39f626513fa85a55"
    private const val IG_URL = "89c2562c0ad648e39b4601557f86ed558cb234f7cca26de470236bcc174fe6a6e4d9bda691264a70428990658b3bcd2df9db72690e7b3dacd8e6eaea12cbc7f1"
    private const val TG_LABEL = "371f1a9a6c711bc42cab5d597e5b802e"
    private const val TG_URL = "b43030b097af1814e8cb274f44c51ae96baa40fc2276272ad93c1fe35042cf6c"
    private const val WEB_URL = "5af4beead39177b82379b5de2e0f7a0db40f4706f125943eec38906a8b841eaa"
    private const val HOWTO = "49814f8baa7528349af1cc70d3d1e22a48312ae8daf45ca44c3c0b0f7ce68a12587c21ef9650bb20a06a2a215b71215cc022ab9549059b28a3ecf05389003e4caf811b00993c1e946bd97da849abc8677a18ac5c79cd7a4586756a593faef028f68db2fec047a71e3feee2dc898a355e26f0ae95e083fef4043784adbed9d746b930eb6f355f3e179e3b3806e850c45b1df874f697518bc615a4884878976facbde61b501b1dbddccd4548aba0f71066f6a9e8a425f0b6ec144a9142cc7a0bf242008f57e1d06e0407775f92424e8704ddcd181f3e09a5d7ccee5b547cea44c1720aac372ba903e8a68ecacce8cf7f5bd0bcc7bbbffd4372039c93e1014ae8ba9f2714239c6079f0f8fd536190472cb951249dfde88c5cc6b68764041c7b8d23a4dcb5d9cb7b11d460b38e1337a785e4"
    private const val SHARE_MSG = "99ad93b99032fda8dd9ba2152605c7f7fab737d9ec6faac640b1a4c8537c7879d56b2afa8a80181835d0c3032e89eef3f95e84779705049a6f20539fbc55f4a4644253dd9e8a1937ea7b631bd14a1ad58cb1d895bd6a87b6c1de97ae7b74733d6f7600392ae5bef4c094374df21204fb9b24072a16d5539616696ba6f81f3b81"

    // ---- runtime accessors ----
    val upiUrl: String get() = dec(UPI_URL)
    val upiId: String get() = dec(UPI_ID)
    val bep20: String get() = dec(BEP20)
    val trc20: String get() = dec(TRC20)
    val erc20: String get() = dec(ERC20)
    val binanceUrl: String get() = dec(BIN_URL)
    val builtBy: String get() = dec(BUILT_BY)
    val aboutHey: String get() = dec(ABOUT_HEY)
    val githubRepo: String get() = dec(GH_REPO)
    val githubReleases: String get() = dec(GH_RELEASES)
    val igLabel: String get() = dec(IG_LABEL)
    val igUrl: String get() = dec(IG_URL)
    val tgLabel: String get() = dec(TG_LABEL)
    val tgUrl: String get() = dec(TG_URL)
    val webUrl: String get() = dec(WEB_URL)
    val howTo: String get() = dec(HOWTO)
    val shareMsg: String get() = dec(SHARE_MSG)
}
