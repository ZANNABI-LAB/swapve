package dev.swapve.csms.support

import java.util.Base64

object TestCredentials {

    const val PASSWORD = "swapve-test-password"

    const val PASSWORD_HASH = "\$2a\$04\$mCan1NKF/6S.F/rHukuGXOZ7608RO3Jyiekif4FAucuCsuTszwcei"

    const val API_USER = "api-test"

    const val API_PASSWORD = "swapve-api-test-password"

    const val API_PASSWORD_HASH = "\$2a\$04\$8HCi1D7Gi45f0P3ugcl9duOrwIrVe3RJh5DgFxaT2ag2BcHCC6e6K"

    fun basic(username: String, password: String = PASSWORD): String {
        val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        return "Basic $encoded"
    }
}
