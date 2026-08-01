package com.buddiestime.watchparty

object Config {
    const val SERVER_URL = "wss://buddiestime-watch-party-2elk.onrender.com"

    fun baseHttpUrl(wsUrl: String = SERVER_URL): String =
        wsUrl.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").trimEnd('/')

    fun healthUrl(wsUrl: String = SERVER_URL): String = baseHttpUrl(wsUrl) + "/health"

    fun effectiveServerUrl(override: String?): String =
        override?.trim().takeUnless { it.isNullOrEmpty() } ?: SERVER_URL
}
