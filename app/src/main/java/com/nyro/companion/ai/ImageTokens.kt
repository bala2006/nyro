package com.nyro.companion.ai

/**
 * Private message key holding the exact image-token cost of the picture in that message. Azure reports usage
 * only after the request, so the client records what it knows when it builds the message; keys with this prefix
 * are removed before the body is sent.
 */
const val IMAGE_TOKENS_KEY = "_nyro_image_tokens"

internal fun isPrivateKey(key: String) = key.startsWith("_nyro_")
