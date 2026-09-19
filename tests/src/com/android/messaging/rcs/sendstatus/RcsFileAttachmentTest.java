/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sendstatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.RcsFileAttachment;
import com.android.messaging.rcs.SourceScan;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

/**
 * A received file is kept and shown whatever its type and whether or not it was downloaded. See
 * "Files" in docs/rcs/provider-contract.md.
 *
 * <p>The decisions are tested directly; where they are used (the receive action, the router, the
 * message view and the tap) needs a device, so those halves are source scans.
 */
public class RcsFileAttachmentTest {

    private static final String SCRATCH =
            "content://com.android.messaging.datamodel.MediaScratchFileProvider/42";
    private static final String ACTION =
            "src/com/android/messaging/datamodel/action/ReceiveRcsMediaAction.java";
    private static final String ROUTER = "src/com/android/messaging/rcs/RcsCallbackRouter.java";
    private static final String VIEW =
            "src/com/android/messaging/ui/conversation/ConversationMessageView.java";
    private static final String FRAGMENT =
            "src/com/android/messaging/ui/conversation/ConversationFragment.java";
    private static final String ROW =
            "src/com/android/messaging/datamodel/data/ConversationMessageData.java";
    private static final String MARK =
            "src/com/android/messaging/datamodel/action/MarkRcsFileUnavailableAction.java";
    private static final String FILE_VIEW =
            "src/com/android/messaging/ui/conversation/RcsFileAttachmentView.java";
    private static final String CONSTANTS = "src/com/android/messaging/rcs/RcsConstants.java";
    private static final String INSERT =
            "src/com/android/messaging/datamodel/action/InsertNewMessageAction.java";

    /** A delivery with no handle was dropped, and the drop confirmed. */
    @Test
    public void aDeliveryWithNoHandleIsStoredPending() {
        assertEquals(RcsFileAttachment.Stored.PLACEHOLDER, RcsFileAttachment.storedAs(null, null));
        assertEquals(RcsFileAttachment.Stored.PLACEHOLDER, RcsFileAttachment.storedAs("", ""));
        assertEquals(RcsFileAttachment.Stored.THUMBNAIL, RcsFileAttachment.storedAs(null, "t"));
        assertEquals(RcsFileAttachment.Stored.FILE, RcsFileAttachment.storedAs("f", "t"));
    }

    @Test
    public void aPlaceholderCarriesTheNameAndSizeAndIsMarked() {
        final String p = RcsFileAttachment.placeholder(SCRATCH, "big.zip", 6_000_000L);
        assertTrue(p, p.startsWith(SCRATCH + "?"));
        assertTrue(RcsFileAttachment.isPlaceholder(p));
        assertEquals("big.zip", RcsFileAttachment.nameOf(p));
        assertEquals(6_000_000L, RcsFileAttachment.sizeOf(p));
        assertFalse(RcsFileAttachment.isPlaceholder(SCRATCH + "?ext=zip"));
        assertFalse(RcsFileAttachment.isPlaceholder(null));
    }

    /** The name survives any characters, and the provider's own parameter is kept. */
    @Test
    public void aDescribedUriKeepsItsParametersAndRoundTripsTheName() {
        final String name = "Q3 report & notes=final #2 100% été 📄.pdf";
        final String d = RcsFileAttachment.describe(SCRATCH + "?ext=pdf", name, 604L);
        assertTrue(d, d.startsWith(SCRATCH + "?ext=pdf&rcs_name="));
        assertEquals(name, RcsFileAttachment.nameOf(d));
        assertEquals(604L, RcsFileAttachment.sizeOf(d));
        final String value = d.substring(d.indexOf("rcs_name=") + 9, d.indexOf("&rcs_size="));
        assertTrue("the name must be percent-encoded: " + value,
                value.matches("[A-Za-z0-9._~%-]+"));
    }

    @Test
    public void anUndescribedUriHasNoNameAndNoSize() {
        assertNull(RcsFileAttachment.nameOf(SCRATCH + "?ext=pdf"));
        assertEquals(-1L, RcsFileAttachment.sizeOf(SCRATCH + "?ext=pdf"));
        final String d = RcsFileAttachment.describe(SCRATCH, null, -1L);
        assertEquals(SCRATCH, d);
        assertEquals(-1L, RcsFileAttachment.sizeOf(SCRATCH + "?rcs_size=x"));
    }

    @Test
    public void aLongNameKeepsItsExtension() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) sb.append('a');
        final String n = RcsFileAttachment.nameOf(
                RcsFileAttachment.describe(SCRATCH, sb + ".pdf", 1L));
        assertTrue(n, n.length() <= 120 && n.endsWith(".pdf"));
    }

    /** A PDF was stored but nothing drew it. */
    @Test
    public void anyNonMediaTypeOrPlaceholderRendersAsAFile() {
        assertTrue(RcsFileAttachment.rendersAsFile(false, SCRATCH));
        assertTrue(RcsFileAttachment.rendersAsFile(true,
                RcsFileAttachment.placeholder(SCRATCH, "p.jpg", 9_000_000L)));
        assertFalse(RcsFileAttachment.rendersAsFile(true, SCRATCH + "?ext=jpg"));
        assertEquals("PDF", RcsFileAttachment.extensionLabel("a.b.pdf"));
        assertNull(RcsFileAttachment.extensionLabel("noext"));
        assertNull(RcsFileAttachment.extensionLabel(".hidden"));
        assertNull(RcsFileAttachment.extensionLabel(null));
    }

    /** The receive action stores a placeholder rather than returning, and names the file. */
    @Test
    public void theReceiveActionNeverDropsAFileForWantOfAHandle() throws IOException {
        final String store = SourceScan.bodyOf(
                SourceScan.codeOnly(SourceScan.read(ACTION)), "store");
        assertTrue("no hits: ReceiveRcsMediaAction.store not found", !store.isEmpty());
        final int decide = store.indexOf("RcsFileAttachment.storedAs(fileUriStr, thumbUriStr)");
        final int placeholder = store.indexOf("RcsFileAttachment.placeholder(");
        final int described = store.indexOf("RcsFileAttachment.describe(fileUriStr, fileName");
        final int insert = store.indexOf("MessageData.createReceivedRcsMediaMessage(");
        assertTrue("store must decide by RcsFileAttachment.storedAs", decide >= 0);
        assertTrue("a file with no handle must be stored as a placeholder before the insert",
                placeholder > decide && placeholder < insert);
        assertTrue("a downloaded file's part must carry its name and size",
                described > decide && described < insert);
        // The only early returns: a secondary user, and a pending copy of a row with its file.
        assertEquals("store has an unexpected early return: " + store, 2,
                SourceScan.count(store.substring(0, insert), "return null;"));
    }

    /** A file that arrived and could not be copied is neither stored nor confirmed. */
    @Test
    public void theRouterDoesNotConfirmAFileItCouldNotCopy() throws IOException {
        final String media = SourceScan.bodyOf(
                SourceScan.codeOnly(SourceScan.read(ROUTER)), "onIncomingMedia")
                .replaceAll("\\s+", " ");
        final int lost = media.indexOf("if (file.fd != null && TextUtils.isEmpty(file.contentUri)"
                + " && TextUtils.isEmpty(durableFileUri)) {");
        final int ret = media.indexOf("return;", lost);
        final int confirm = media.indexOf(".confirming(ticket)");
        assertTrue("onIncomingMedia must stop on a descriptor it could not copy", lost >= 0);
        assertTrue("and return before the action that would confirm it",
                ret > lost && ret < confirm && media.indexOf("}", lost) > ret);
    }

    /** The view draws file rows, and no media view is handed a placeholder. */
    @Test
    public void theMessageViewDrawsFileRowsAndKeepsPlaceholdersFromMediaViews()
            throws IOException {
        final String raw = SourceScan.codeOnly(SourceScan.read(VIEW));
        final String code = raw.replaceAll("\\s+", " ");
        assertTrue("updateMessageAttachments must bind the RCS file row",
                code.contains("bindAttachmentsOfSameType(mRcsFileFilter, "
                        + "R.layout.message_rcs_file_attachment, mRcsFileViewBinder, "
                        + "RcsFileAttachmentView.class);"));
        for (final String f : new String[] {"Video", "Audio", "VCard", "Image"}) {
            assertTrue("s" + f + "Filter must exclude placeholders", code.contains(
                    "Predicate<MessagePartData> s" + f + "Filter = p -> p.is" + f + "() && "
                            + "!isRcsPlaceholder(p);"));
        }
        assertTrue("the file row filter must be isRcsFileRow",
                code.contains("Predicate<MessagePartData> mRcsFileFilter = this::isRcsFileRow;"));
        assertTrue("the file row must be RCS-only and follow RcsFileAttachment.rendersAsFile",
                SourceScan.bodyOf(raw, "isRcsFileRow").replaceAll("\\s+", " ").contains(
                        "return mData.getIsRcs() && RcsFileAttachment.rendersAsFile("));
    }

    /** A tap accepted only images and videos. Any pending file is accepted now. */
    @Test
    public void aTapAcceptsAnyPendingFileAndOpensTheRest() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(FRAGMENT));
        final String click = SourceScan.bodyOf(code, "onAttachmentClick").replaceAll("\\s+", " ");
        final int rcs = click.indexOf("if (messageView.getData().getIsRcs()) {");
        final int file = click.indexOf("if (RcsFileAttachment.rendersAsFile(", rcs);
        final int open = click.indexOf("maybeAcceptOrViewRcsMedia(messageView.getData(), () -> "
                + "openRcsFile(uri, attachment.getContentType()));", file);
        assertTrue("onAttachmentClick must send an RCS file row through the accept check first",
                rcs >= 0 && file > rcs && open > file);
        final String accept = SourceScan.bodyOf(code, "maybeAcceptOrViewRcsMedia");
        assertTrue("the accept check must not depend on the attachment's type",
                !accept.isEmpty() && !accept.contains("isImage") && !accept.contains("isVideo")
                        && accept.contains(".acceptIncomingFile(subId, meta.rcsMessageId)"));
        final String view = SourceScan.bodyOf(code, "openRcsFile").replaceAll("\\s+", " ");
        assertTrue("openRcsFile must grant read access to the file it opens",
                view.contains("new Intent(Intent.ACTION_VIEW) .setDataAndType(uri, contentType) "
                        + ".addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)"));
        assertTrue("openRcsFile must not open a placeholder",
                view.contains("RcsFileAttachment.isPlaceholder(uri.toString())"));
    }

    /** A caption rides on its media part; the bubble shows it after the row's text. */
    @Test
    public void aMediaCaptionIsShownAfterTheText() {
        assertEquals("look", RcsFileAttachment.withCaptions(null, Arrays.asList("look")));
        assertEquals("hi\nlook\nand this", RcsFileAttachment.withCaptions("hi",
                Arrays.asList("look", null, "", "and this")));
        assertEquals("hi", RcsFileAttachment.withCaptions("hi", Collections.emptyList()));
        assertEquals("", RcsFileAttachment.withCaptions("", Arrays.asList(null, "")));
        assertNull(RcsFileAttachment.withCaptions(null, Arrays.asList((String) null)));
    }

    /** The row dropped every attachment part's text, so no caption reached any view. */
    @Test
    public void anRcsRowKeepsItsAttachmentsCaptions() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(ROW));
        final String bind = SourceScan.bodyOf(code, "bind").replaceAll("\\s+", " ");
        final int keep = bind.indexOf("final boolean keepCaptions = "
                + "cursor.getInt(INDEX_TRANSPORT_TYPE) == RcsConstants.TRANSPORT_RCS;");
        final int parts = bind.indexOf("mParts = makeParts(");
        assertTrue("bind must keep captions on RCS rows only, decided before makeParts",
                keep >= 0 && parts > keep
                        && bind.indexOf("mPartsCount, mMessageId, keepCaptions);", parts) > 0);
        final String part = SourceScan.bodyOfDeclaredAs(code,
                "static MessagePartData makePartData(").replaceAll("\\s+", " ");
        assertTrue("makePartData must give an attachment its text only when asked",
                part.contains("keepCaptions ? MessagePartData.createMediaMessagePart(text, "
                        + "contentType, contentUri, width, height) : "
                        + "MessagePartData.createMediaMessagePart(contentType, contentUri, width, "
                        + "height);"));
        assertEquals("makeParts and unpackMessageParts must pass keepCaptions on", 2,
                SourceScan.count(code.replaceAll("\\s+", " "), "messageId, keepCaptions));"));
    }

    /**
     * An image or video row showed no caption: the bubble text read text parts only. A file row
     * shows its own caption, so only parts a media view draws add theirs.
     */
    @Test
    public void theBubbleShowsTheCaptionOfAnRcsMediaPart() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(ROW));
        final String captions = SourceScan.bodyOf(code, "getRcsMediaCaptions")
                .replaceAll("\\s+", " ");
        final int rcs = captions.indexOf("if (!getIsRcs()) { return captions; }");
        final int media = captions.indexOf("if (part.isAttachment() && "
                + "!RcsFileAttachment.rendersAsFile(part.isMedia(), uri)) {", rcs);
        final int add = captions.indexOf("captions.add(part.getText());", media);
        assertTrue("getRcsMediaCaptions must take, on RCS rows only, the caption of each part "
                + "a media view draws: " + captions, rcs >= 0 && media > rcs && add > media);
        assertTrue("getBubbleText must add those captions to the text",
                SourceScan.bodyOf(code, "getBubbleText").replaceAll("\\s+", " ").contains(
                        "return RcsFileAttachment.withCaptions(getText(), "
                                + "getRcsMediaCaptions());"));

        final String view = SourceScan.codeOnly(SourceScan.read(VIEW));
        assertTrue("updateMessageText must show the bubble text",
                SourceScan.bodyOf(view, "updateMessageText")
                        .contains("String text = mData.getBubbleText();"));
        assertTrue("the text bubble must show for a caption alone",
                SourceScan.bodyOf(view, "shouldShowMessageTextBubble")
                        .contains("if (mData.hasBubbleText()) {"));
        assertEquals("the view must not size its text area by text parts alone", 0,
                SourceScan.count(view, "mData.hasText()"));
    }

    /** A report of an unavailable file moves a pending row only; a row with its file keeps it. */
    @Test
    public void anUnavailableReportMarksOnlyAPendingRow() {
        final int pending = RcsFileAttachment.STATUS_PENDING;
        final int unavailable = RcsFileAttachment.STATUS_UNAVAILABLE;
        assertEquals(unavailable, RcsFileAttachment.statusWhenUnavailable(pending));
        assertEquals(unavailable, RcsFileAttachment.statusWhenUnavailable(unavailable));
        for (final int has : new int[] {0, 1, 2, 3, 4}) {
            assertEquals(has, RcsFileAttachment.statusWhenUnavailable(has));
            assertFalse(RcsFileAttachment.lacksFile(has));
        }
        assertTrue(RcsFileAttachment.lacksFile(pending));
        assertTrue(RcsFileAttachment.lacksFile(unavailable));
        assertTrue("the status codes must stay clear of IRcsProviderCallback.STATUS_*",
                pending > 4 && unavailable > 4 && pending != unavailable);
    }

    /**
     * A pending row offered a download the provider could no longer make, forever. The router
     * now marks it unavailable and confirms nothing: the file message was confirmed when its
     * pending row was stored.
     */
    @Test
    public void theRouterMarksAnUnavailableFileAndConfirmsNothing() throws IOException {
        final String cb = SourceScan.bodyOf(SourceScan.codeOnly(SourceScan.read(ROUTER)),
                "onIncomingFileUnavailable").replaceAll("\\s+", " ");
        assertTrue("onIncomingFileUnavailable not found in the router", !cb.isEmpty());
        assertTrue("it must start MarkRcsFileUnavailableAction: " + cb,
                cb.contains("new MarkRcsFileUnavailableAction(messageId, reason).start();"));
        assertTrue("it must not confirm anything: " + cb,
                !cb.contains("RcsInboundConfirmation") && !cb.contains("confirming("));

        final String mark = SourceScan.bodyOf(SourceScan.codeOnly(SourceScan.read(MARK)),
                "executeAction").replaceAll("\\s+", " ");
        final int next = mark.indexOf(
                "final int next = RcsFileAttachment.statusWhenUnavailable(meta.rcsStatus);");
        final int same = mark.indexOf("if (next == meta.rcsStatus) {", next);
        final int write = mark.indexOf("BugleDatabaseOperations.updateMessageRow(db, localId, "
                + "RcsMessageStore.rcsMetaValues(rcsMessageId, next));", same);
        assertTrue("the action must write only the status statusWhenUnavailable gives, and only "
                + "when it changes: " + mark, next >= 0 && same > next && write > same);
        assertTrue("the constants must be RcsFileAttachment's", SourceScan.read(CONSTANTS)
                .replaceAll("\\s+", " ").contains("public static final int RCS_FILE_UNAVAILABLE = "
                        + "RcsFileAttachment.STATUS_UNAVAILABLE;"));
    }

    /** A pending copy offered again after the report makes the row pending again. */
    @Test
    public void anUnavailableRowStillTakesItsFile() throws IOException {
        final String has = SourceScan.bodyOf(SourceScan.codeOnly(SourceScan.read(ACTION)),
                "hasItsFile").replaceAll("\\s+", " ");
        assertTrue("hasItsFile must count an unavailable row as lacking its file: " + has,
                has.contains("return meta != null "
                        + "&& !RcsFileAttachment.lacksFile(meta.rcsStatus);"));
    }

    /** The row says the file is gone instead of "Tap to download", and a tap does not accept. */
    @Test
    public void anUnavailableRowSaysSoAndATapDoesNotAccept() throws IOException {
        final String view = SourceScan.codeOnly(SourceScan.read(VIEW)).replaceAll("\\s+", " ");
        assertTrue("the message view must pass the unavailable state to the file row",
                view.contains("((RcsFileAttachmentView) view).bind(attachment, "
                        + "mData.getIsRcsFilePending(), mData.getIsRcsFileUnavailable(),"));
        final String bind = SourceScan.bodyOf(SourceScan.codeOnly(SourceScan.read(FILE_VIEW)),
                "bind").replaceAll("\\s+", " ");
        final int gone = bind.indexOf("if (unavailable) {");
        final int says = bind.indexOf("R.string.rcs_file_unavailable", gone);
        final int tap = bind.indexOf("} else if (pending) {", says);
        assertTrue("the file row must say the file is unavailable instead of offering the "
                + "download: " + bind, gone >= 0 && says > gone && tap > says);

        final String accept = SourceScan.bodyOf(SourceScan.codeOnly(SourceScan.read(FRAGMENT)),
                "maybeAcceptOrViewRcsMedia").replaceAll("\\s+", " ");
        final int refuse = accept.indexOf(
                "if (meta != null && meta.rcsStatus == RcsConstants.RCS_FILE_UNAVAILABLE) {");
        final int toast = accept.indexOf("R.string.rcs_file_unavailable", refuse);
        final int pending = accept.indexOf("} else if (pending) {", toast);
        final int call = accept.indexOf(".acceptIncomingFile(subId, meta.rcsMessageId)");
        assertTrue("a tap on an unavailable row must say so before, and instead of, the accept: "
                + accept, refuse >= 0 && toast > refuse && pending > toast && call > pending);
    }

    /** Text typed with a file rides as its caption, after the attachment's own. */
    @Test
    public void typedTextBecomesTheOutgoingCaption() {
        assertEquals("look", RcsFileAttachment.outgoingCaption(null, "look"));
        assertEquals("look", RcsFileAttachment.outgoingCaption("", "look"));
        assertEquals("cap", RcsFileAttachment.outgoingCaption("cap", ""));
        assertEquals("cap", RcsFileAttachment.outgoingCaption("cap", null));
        assertNull(RcsFileAttachment.outgoingCaption(null, ""));
        assertNull(RcsFileAttachment.outgoingCaption("", null));
        assertEquals("cap\nlook", RcsFileAttachment.outgoingCaption("cap", "look"));
        // The send button already copied the caption to the front of the typed text.
        assertEquals("cap\nlook", RcsFileAttachment.outgoingCaption("cap", "cap\nlook"));
    }

    /**
     * A file send took only the media part's own text as its caption, and the composer keeps the
     * typed text in a separate text part, so the typed text was neither sent nor kept.
     */
    @Test
    public void aFileSendCarriesTheTypedText() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(INSERT));
        final String uses = "final String caption = "
                + "RcsFileAttachment.outgoingCaption(media.getText(), content.getMessageText());";
        for (final String m : new String[] {"tryInsertSendingRcsFile",
                "tryInsertSendingRcsGroupFile"}) {
            final String body = SourceScan.bodyOf(code, m).replaceAll("\\s+", " ");
            assertTrue(m + " not found", !body.isEmpty());
            final int caption = body.indexOf(uses);
            final int send = body.indexOf("transport.sendFile(");
            final int row = body.indexOf("MessageData.createOutgoingRcsMediaMessage(");
            assertTrue(m + " must send and store the typed text as the caption: " + body,
                    caption >= 0 && send > caption && row > send
                            && body.indexOf("caption", send) < body.indexOf(";", send)
                            && body.indexOf("contentUri, caption);", row) > row);
        }
    }
}
