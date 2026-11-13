package dev.fughetta.dshcompose.domain.images

const val MAX_REPLY_IMAGE_BYTES = 20L * 1024 * 1024

class ReplyImageException(val code: String) : Exception(code)
