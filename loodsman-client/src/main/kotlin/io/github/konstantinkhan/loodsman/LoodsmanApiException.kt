package io.github.konstantinkhan.loodsman

class LoodsmanApiException(
    message: String,
    cause: Throwable? = null,
    val status: Int? = null
) : RuntimeException(message, cause) {

    val isSessionExpired: Boolean
        get() = status == 401 || status == 419
}
