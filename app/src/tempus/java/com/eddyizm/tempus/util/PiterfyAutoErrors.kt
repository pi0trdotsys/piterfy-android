package com.eddyizm.tempus.util

import androidx.media3.common.PlaybackException

/**
 * Piterfy: krótkie komunikaty o błędach odtwarzania dla Android Auto. Kierowca nie czyta logów, więc zamiast „błąd 2004”
 * dostaje jedno zdanie i, gdy to pomaga, przycisk „Otwórz Piterfy”. Czysta logika bez Androida (testy jednostkowe).
 */
object PiterfyAutoErrors {
    enum class Kind { NETWORK, AUTH, UNAVAILABLE, OTHER }

    /** @param httpStatus kod odpowiedzi serwera, gdy błąd to zły status HTTP (inaczej null) */
    @JvmStatic
    fun classify(errorCode: Int, httpStatus: Int?): Kind = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> Kind.NETWORK
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when {
            httpStatus == null -> Kind.OTHER
            httpStatus == 401 || httpStatus == 403 -> Kind.AUTH
            httpStatus == 404 || httpStatus == 410 -> Kind.UNAVAILABLE
            httpStatus >= 500 -> Kind.NETWORK
            else -> Kind.OTHER
        }
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> Kind.UNAVAILABLE
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> Kind.AUTH
        else -> Kind.OTHER
    }

    /** Czy komunikat ma mieć przycisk otwierający aplikację (logowanie, sprawdzenie serwera). */
    @JvmStatic
    fun offersOpenApp(kind: Kind): Boolean = kind == Kind.AUTH || kind == Kind.NETWORK
}
