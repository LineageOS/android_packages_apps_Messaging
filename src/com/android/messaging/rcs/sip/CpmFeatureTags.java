/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sip;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * GSMA Universal Profile CPM feature tags, as {@code +g.3gpp.icsi-ref="<urn>"} or
 * {@code +g.3gpp.iari-ref="<urn>"}. The URNs are URL-encoded because the framework's
 * {@code DelegateRequest} compares them verbatim against the carrier's
 * {@code ims.rcs_feature_tag_allowed_string_array}.
 */
public final class CpmFeatureTags {
    private CpmFeatureTags() {}

    private static final String ICSI_REF = "+g.3gpp.icsi-ref";
    private static final String IARI_REF = "+g.3gpp.iari-ref";

    /** {@code oma.cpm.session}: 1:1 chat sessions. */
    public static final String CPM_SESSION =
            icsi("urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session");

    /** {@code oma.cpm.msg}: 1:1 pager mode. */
    public static final String CPM_MSG =
            icsi("urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.msg");

    public static final String CPM_LARGEMSG =
            icsi("urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.largemsg");

    /** {@code rcs.fthttp}: file transfer over HTTP. */
    public static final String RCS_FTHTTP =
            iari("urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.fthttp");

    public static final String CPM_FILETRANSFER =
            icsi("urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.filetransfer");

    /** The tags requested for 1:1 text: session and pager mode. */
    public static Set<String> cpmOneToOne() {
        // Ordered only for readable logs; the framework treats the request as a set.
        return new LinkedHashSet<>(Arrays.asList(CPM_SESSION, CPM_MSG));
    }

    private static String icsi(String urn) {
        return String.format(java.util.Locale.US, "%s=\"%s\"", ICSI_REF, urn);
    }

    private static String iari(String urn) {
        return String.format(java.util.Locale.US, "%s=\"%s\"", IARI_REF, urn);
    }
}
