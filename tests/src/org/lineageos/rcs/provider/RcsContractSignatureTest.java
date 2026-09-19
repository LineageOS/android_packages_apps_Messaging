/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.rcs.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pins the in-place widenings of the contract: parameters a oneway callback or a parcelable gained
 * at the end, which must leave the ordinal layout, and so the probe digest, unchanged.
 */
public class RcsContractSignatureTest {

    private static final String AIDL_DIR = "aidl/src/aidl/org/lineageos/rcs/provider/";

    /**
     * The callback layout digest before and after onMessageStatus gained e2eeSchemeId, and four
     * callbacks gained confirmId.
     */
    private static final String CALLBACK_DIGEST = "f0f650b242f3";

    /** The provider layout digest, unchanged by the CONTRACT_CONFIRMS_STORED constant. */
    private static final String PROVIDER_DIGEST = "2c276073493a";

    /** The callbacks that gained a trailing confirmId, with their parameter count after it. */
    private static final String[][] CONFIRM_ID_CALLBACKS = {
        {"onImdnReceipt", "4"},
        {"onGroupEvent", "9"},
        {"onGroupImdnReceipt", "5"},
        {"onIncomingReaction", "7"},
    };

    @Test
    public void onMessageStatus_hasTheTrailingSchemeParameter() throws IOException {
        final List<String> params = paramsOf(aidl("IRcsProviderCallback.aidl"), "onMessageStatus");
        assertEquals(params.toString(), 5, params.size());
        assertEquals("@nullable String e2eeSchemeId", params.get(4));
        assertEquals("@nullable String errorReason", params.get(3));
    }

    @Test
    public void callbackLayout_isUnchangedByTheWidening() throws IOException {
        final String[] names = methodNames(aidl("IRcsProviderCallback.aidl"));
        assertEquals("onMessageStatus", names[1]);
        assertEquals(RcsContractLayout.CALLBACK_ANCHOR_METHOD, names[0]);
        assertEquals(CALLBACK_DIGEST, RcsContractLayout.digest(names));
    }

    @Test
    public void confirmIdCallbacks_endWithTheNullableConfirmId() throws IOException {
        final String src = aidl("IRcsProviderCallback.aidl");
        for (final String[] c : CONFIRM_ID_CALLBACKS) {
            final List<String> params = paramsOf(src, c[0]);
            assertEquals(c[0] + ": " + params, Integer.parseInt(c[1]), params.size());
            assertEquals(c[0], "@nullable String confirmId", params.get(params.size() - 1));
        }
    }

    @Test
    public void providerLayout_isUnchangedByTheConfirmationRevision() throws IOException {
        final String src = aidl("IRcsProvider.aidl");
        assertTrue("the revision constant is declared",
                src.replaceAll("\\s+", " ").contains("const int CONTRACT_CONFIRMS_STORED = 3;"));
        assertEquals(PROVIDER_DIGEST, RcsContractLayout.digest(methodNames(src)));
    }

    @Test
    public void providerOrdinal64_isGetMlsTrustAnchors() throws IOException {
        final String[] names = methodNames(aidl("IRcsProvider.aidl"));
        assertEquals("getMlsTrustAnchors", names[64 - RcsContractLayout.FIRST_ORDINAL]);
    }

    @Test
    public void incomingFile_roundTripsTheScheme() {
        final RcsIncomingFile in = file("google.etouffee");
        final Parcel p = Parcel.obtain();
        in.writeToParcel(p, 0);
        p.setDataPosition(0);
        final RcsIncomingFile out = RcsIncomingFile.CREATOR.createFromParcel(p);
        assertEquals("google.etouffee", out.e2eeSchemeId);
        assertEquals(in.groupId, out.groupId);
        assertEquals(in.fdThumbnail.fd, out.fdThumbnail.fd);
        assertEquals(in.fileName, out.fileName);
        assertEquals(0, p.dataAvail());
    }

    @Test
    public void incomingFile_fromAnOlderWriter_readsNullScheme() {
        final Parcel p = Parcel.obtain();
        file("google.etouffee").writeToParcel(p, 0);
        p.truncateTo(p.valueCount() - 1);   // a writer that ended at fdThumbnail
        p.setDataPosition(0);
        final RcsIncomingFile out = RcsIncomingFile.CREATOR.createFromParcel(p);
        assertNull(out.e2eeSchemeId);
        assertEquals("gid", out.groupId);
        assertEquals(7, out.fdThumbnail.fd);
    }

    @Test
    public void incomingFile_oldConstructor_meansPlaintext() {
        final RcsIncomingFile f = new RcsIncomingFile(1, "m", "+1", null, null, "image/jpeg",
                "a.jpg", 3L, null, null, null, -1L, 0L, true, true, null, null);
        assertNull(f.e2eeSchemeId);
    }

    private static RcsIncomingFile file(final String scheme) {
        return new RcsIncomingFile(1, "mid", "+15550100", null, new ParcelFileDescriptor(5),
                "image/jpeg", "a.jpg", 42L, "cap", null, "image/jpeg", 9L, 123L, true, false,
                "gid", new ParcelFileDescriptor(7), scheme);
    }

    private static String aidl(final String name) throws IOException {
        final String rel = AIDL_DIR + name;
        for (final String c : new String[] {rel, "packages/apps/Messaging/" + rel, "../" + rel}) {
            final File f = new File(c);
            if (f.isFile()) {
                return stripComments(new String(Files.readAllBytes(f.toPath()),
                        StandardCharsets.UTF_8));
            }
        }
        throw new IOException(rel + " not found from " + new File(".").getAbsolutePath());
    }

    private static String stripComments(final String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    /** Method names in declaration order, which is transaction-code order. */
    private static String[] methodNames(final String src) {
        final String body = src.substring(src.indexOf('{', src.indexOf("interface")) + 1);
        final Matcher m = Pattern.compile(
                "(?:^|[;}])\\s*(?:@\\w+\\s+)*[\\w.<>\\[\\]]+\\s+(\\w+)\\s*\\(").matcher(body);
        final List<String> out = new ArrayList<>();
        while (m.find()) out.add(m.group(1));
        assertTrue("no methods parsed", out.size() > 10);
        return out.toArray(new String[0]);
    }

    private static List<String> paramsOf(final String src, final String method) {
        final Matcher m = Pattern.compile("\\b" + method + "\\s*\\(([^)]*)\\)").matcher(src);
        assertTrue(method + " not declared", m.find());
        final List<String> out = new ArrayList<>();
        for (final String p : m.group(1).split(",")) out.add(p.trim().replaceAll("\\s+", " "));
        return out;
    }
}
