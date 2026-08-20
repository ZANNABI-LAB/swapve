package dev.swapve.csms.support

import java.util.Base64

object TestCredentials {

    const val PASSWORD = "swapve-test-password"

    const val PASSWORD_HASH = "\$2a\$04\$mCan1NKF/6S.F/rHukuGXOZ7608RO3Jyiekif4FAucuCsuTszwcei"

    fun basic(username: String, password: String = PASSWORD): String {
        val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        return "Basic $encoded"
    }
}
