/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.messaging.rcs.sip;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * GSMA Universal Profile CPM feature-tag strings, ported byte-for-byte from
 * what Google Messages puts on the wire.
 *
 * <p>Each ICSI tag is built as {@code +g.3gpp.icsi-ref="<urn>"} and each IARI
 * tag as {@code +g.3gpp.iari-ref="<urn>"}. The {@code urn} values are
 * URL-encoded ({@code urn%3Aurn-7%3A...}) exactly as Google Messages stores
 * them, because the
 * framework {@code DelegateRequest} compares them verbatim against the
 * carrier's {@code ims.rcs_feature_tag_allowed_string_array}.
 *
 * <p>For 1-1 RCS text messaging the load-bearing pair is
 * {@link #CPM_SESSION} (the 1-1 chat-session gate tag) + {@link #CPM_MSG}
 * (pager-mode MESSAGE). On a Shannon device,
 * {@link #CPM_SESSION} came back GRANTED and {@link #CPM_MSG} DENIED
 * (reason=INVALID) — a Shannon provisioning gap, not a policy block.
 */
public final class CpmFeatureTags {
    private CpmFeatureTags() {}

    private static final String ICSI_REF = "+g.3gpp.icsi-ref";
    private static final String IARI_REF = "+g.3gpp.iari-ref";

    /** {@code oma.cpm.session} (1-1 session, the gate tag). */
    public static final String CPM_SESSION =
            icsi("urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session");

    /** {@code oma.cpm.msg} (pager-mode 1-1 MESSAGE). */
    public static final String CPM_MSG =
            icsi("urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.msg");

    /** {@code oma.cpm.largemsg}. */
    public static final String CPM_LARGEMSG =
            icsi("urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.largemsg");

    /** {@code rcs.fthttp} (file transfer over HTTP, always advertised). */
    public static final String RCS_FTHTTP =
            iari("urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.fthttp");

    /** {@code oma.cpm.filetransfer}. */
    public static final String CPM_FILETRANSFER =
            icsi("urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.filetransfer");

    /**
     * The minimal CPM 1-1 text-messaging set (session + msg). This is what we
     * request for the messaging path; the larger set (file transfer, large
     * message) is deferred until 1-1 text is proven end-to-end.
     */
    public static Set<String> cpmOneToOne() {
        // LinkedHashSet to keep session before msg for log readability; the
        // framework treats the request as an unordered Set regardless.
        return new LinkedHashSet<>(Arrays.asList(CPM_SESSION, CPM_MSG));
    }

    private static String icsi(String urn) {
        return String.format(java.util.Locale.US, "%s=\"%s\"", ICSI_REF, urn);
    }

    private static String iari(String urn) {
        return String.format(java.util.Locale.US, "%s=\"%s\"", IARI_REF, urn);
    }
}
