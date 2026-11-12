package com.framescope.monitor.core

/**
 * Banking, payment and security apps that should never be overlapped by the
 * FPS overlay.
 *
 * FrameScope never asks for root, accessibility or device-admin rights, so
 * these apps keep working normally - but a floating overlay on top of a
 * payment screen is a bad idea, so the overlay hides itself automatically.
 */
object ProtectedApps {

    /** Known package names of banking / payment apps (CA + international). */
    val KNOWN: Set<String> = setOf(
        // Canada
        "com.cibc.cibcmobile",
        "com.rbc.rbcmobile",
        "com.td.tdinsurance",
        "ca.td.app",
        "com.bmo.bmomobile",
        "com.scotiabank.scotiatrust",
        "com.desjardins.mobile",
        "com.tangerine.mobile",
        "com.wealth.one",
        "com.td.canada",
        "com.hrbc.hrbc",
        "com.icbc.canada",
        "com.jade.canada",
        "com.att.canada",
        // International
        "com.chase.sig.android",
        "com.bankofamerica.cashpro",
        "com.wf.wellsfargomobile",
        "com.usaa.mobile.android.usaa",
        "com.citi.citimobile",
        "com.paypal.android.p2pmobile",
        "com.venmo",
        "com.squareup.cash",
        "com.revolut.revolut",
        "com.monzo.monzo",
        "com.starlingbank.android",
        "com.barclays.android.barclays",
        "com.hsbc.hsbc",
        "com.lloydsbank.android",
        "com.lloydsbank.android",
        "com.anz.android",
        "com.cmb.android",
        "de.schildbach.wallet",
        "com.google.android.apps.walletnfcrel",
        "com.samsung.android.wallet",
        "com.mcafee.mcafee",
        "com.bitdefender.security"
    )

    /** Heuristics that catch regional / OEM variants. */
    private val PATTERNS: List<Regex> = listOf(
        Regex(".*\\.bank.*"),
        Regex(".*\\bbank\\b.*"),
        Regex(".*\\.payments?.*"),
        Regex(".*\\bpaypal\\b.*"),
        Regex(".*\\bwallet\\b.*"),
        Regex(".*\\bcashapp\\b.*")
    )

    fun isProtected(packageName: String?, extra: Set<String> = emptySet()): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (packageName in KNOWN) return true
        if (packageName in extra) return true
        return PATTERNS.any { it.matches(packageName) }
    }
}
