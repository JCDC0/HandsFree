package com.example.handsfree.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/**
 * Whether the device currently has a usable internet connection.
 *
 * Only SMART_SEARCH cares. Capture, transcription, normalisation, matching and
 * every other command run entirely on-device and work with the phone in
 * airplane mode. A search does not: handing a results URL to the browser with
 * no connection lands the tester on the browser's own error page, which during
 * a test round is indistinguishable from the command having failed. We would
 * then get a bug report about the lexicon for something that was never a
 * lexicon problem.
 *
 * This reads `ACCESS_NETWORK_STATE`. It **opens no socket and transmits
 * nothing** — the offline claim in `CLAUDE.md` §1 is about network traffic, and
 * asking the OS whether a network exists produces none.
 */
object Connectivity {

    private const val TAG = "Connectivity"

    /**
     * Fails **open**: a missing service, a missing permission or any thrown
     * error reports "online". A false "you have no internet" on a working
     * connection is the worse error — it would teach testers to ignore the
     * banner, and then it is worth nothing when it is right.
     *
     * `NET_CAPABILITY_VALIDATED` is required as well as `_INTERNET`, so a
     * campus Wi-Fi sitting behind an unaccepted captive portal reads as offline.
     * That is the correct answer: the search will not load on it either.
     */
    fun hasInternet(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: error("no ConnectivityManager")
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrElse {
        Log.w(TAG, "connectivity check failed — assuming online", it)
        true
    }
}
