package com.samourai.sentinel.ui.dojo

/**
 * Shared Dojo Bay onion addresses, kept in one place so the directory fetch
 * (CommunityDojoRepository) and any user-facing links to the site (e.g. the
 * balance/rescan help text) can't drift apart.
 */
object DojoBayConstants {
    const val BASE_URL = "http://dojobayeryasshgghz537de5ckgd5hhi4z5sdeil3roeh65fwhdnu2yd.onion/"
    const val DIRECTORY_JSON_URL = BASE_URL + "data/dojos.json"
}
