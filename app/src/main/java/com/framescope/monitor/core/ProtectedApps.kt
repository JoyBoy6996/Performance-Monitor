package com.framescope.monitor.core

import android.content.Context

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
        "com.natwest.android",
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
        Regex(".*bank.*", RegexOption.IGNORE_CASE),
        Regex(".*payment.*", RegexOption.IGNORE_CASE),
        Regex(".*paypal.*", RegexOption.IGNORE_CASE),
        Regex(".*wallet.*", RegexOption.IGNORE_CASE),
        Regex(".*cashapp.*", RegexOption.IGNORE_CASE),
        Regex(".*crypto.*", RegexOption.IGNORE_CASE)
    )

    @Volatile
    private var detectedPackages: Set<String> = emptySet()

    /** Scans installed packages to detect banking and financial applications dynamically. */
    fun scanInstalled(context: Context) {
        runCatching {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(0).map { it.packageName }
            val matched = packages.filter { pkg ->
                pkg in KNOWN || PATTERNS.any { it.containsMatchIn(pkg) }
            }.toSet()
            detectedPackages = matched
        }
    }

    fun isProtected(packageName: String?, extra: Set<String> = emptySet()): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.lowercase()
        if (pkg in KNOWN) return true
        if (pkg in extra) return true
        if (pkg in detectedPackages) return true
        return PATTERNS.any { it.containsMatchIn(pkg) }
    }
}
