package dev.fughetta.dshcompose.domain

/** Stable reason why an action was rejected. */
class OperationFailure(val code: String, cause: Throwable? = null) : Exception(code, cause)

fun operationErrorCode(error: Throwable): String? = (error as? OperationFailure)?.code
