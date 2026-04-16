package com.buddiestime.watchparty

interface StreamingService {
    val name: String
    val displayName: String
    val url: String
    val userAgent: String?
    val headersOverride: Map<String, String>
}
