package dev.swapve.swap

/**
 * An authorization token — the value pair `(identifier, kind)`.
 *
 * **Not treated as a foreign key into a local user table.** Roaming tokens are not in our
 * database, and binding them as a foreign key would bake in the irreversible assumption that
 * every token belongs to one of our users.
 *
 * [type] is a free string rather than an enum for two reasons. The standard makes the kind a
 * required field, so the value is carried as given — but a protocol enum has no place in the
 * domain. And a value outside the table is recorded rather than discarded.
 */
data class IdToken(
    val idToken: String,
    val type: String,
) {
    init {
        require(idToken.isNotBlank()) { "idToken is blank" }
        require(type.isNotBlank()) { "idToken type is blank" }
    }
}
