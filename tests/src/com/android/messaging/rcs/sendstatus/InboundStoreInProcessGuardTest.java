/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sendstatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.SourceScan;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A provider callback's store runs in the app's process at once, never as a JobScheduler job.
 *
 * <p>{@code Action.start()} queues the action on {@code ActionServiceImpl}, a JobIntentService, so
 * each burst of inbound starts a new job. A backgrounded app is held to 20 jobs a minute
 * ({@code qc_max_job_count_per_rate_limiting_window}); the 21st waits with WITHIN_QUOTA
 * unsatisfied for a minute or more (measured 5+ min on a dozing device), and the provider's ack
 * waits for the store's confirmation. So every action the router starts, and the reaction a
 * tapback hands its confirmation to, goes through {@code startInProcess()}.
 *
 * <p>Second reader of the same rule: {@link InboundStoreConfirmGuardTest} pins the router's
 * hand-offs by their exact spelling, which includes {@code startInProcess()}.
 */
public class InboundStoreInProcessGuardTest {

    private static final String ROUTER = "src/com/android/messaging/rcs/RcsCallbackRouter.java";
    private static final String ACTIONS = "src/com/android/messaging/datamodel/action/";

    /** The action starts the router makes: messages, media, file unavailable, reaction, bot,
     *  send status, IMDN, group event, group IMDN. */
    private static final int ROUTER_ACTION_STARTS = 9;

    @Test
    public void theRouterStartsEveryActionInProcess() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(ROUTER));
        final List<String> queued = jobStarts(code);
        assertTrue("RcsCallbackRouter queues an action as a JobScheduler job (.start()); "
                + "use .startInProcess(): " + queued, queued.isEmpty());
        assertEquals("RcsCallbackRouter's action starts changed; check each new one runs in "
                + "process and update the count", ROUTER_ACTION_STARTS,
                SourceScan.count(code, ".startInProcess()"));
    }

    @Test
    public void aTapbackStartsItsReactionInProcess() throws IOException {
        final String store = SourceScan.bodyOf(SourceScan.codeOnly(
                SourceScan.read(ACTIONS + "ReceiveRcsMessageAction.java")), "store");
        assertTrue("no hits: the tapback arm no longer starts a reaction",
                store.contains("reaction.start"));
        assertTrue("the tapback's reaction carries the message's confirmation and must run in "
                + "process", store.contains("reaction.startInProcess();"));
        assertFalse(store.contains("reaction.start();"));
    }

    @Test
    public void startInProcessNeverGoesThroughJobScheduler() throws IOException {
        final String action = SourceScan.codeOnly(SourceScan.read(ACTIONS + "Action.java"));
        final String start = SourceScan.bodyOf(action, "startInProcess");
        assertTrue("no Action.startInProcess()", !start.isEmpty());
        assertTrue("Action.startInProcess must use the action service's in-process path",
                start.replaceAll("\\s+", "").contains(".startActionInProcess(this)"));

        final String service =
                SourceScan.codeOnly(SourceScan.read(ACTIONS + "ActionService.java"));
        assertTrue("ActionService.startActionInProcess must reach ActionServiceImpl's",
                SourceScan.bodyOf(service, "startActionInProcess")
                        .contains("ActionServiceImpl.startActionInProcess(action)"));

        final String impl =
                SourceScan.codeOnly(SourceScan.read(ACTIONS + "ActionServiceImpl.java"));
        final String enqueue = SourceScan.bodyOf(impl, "startActionInProcess");
        final String run = SourceScan.bodyOf(impl, "runInProcess");
        assertTrue("no ActionServiceImpl.startActionInProcess", !enqueue.isEmpty());
        assertTrue("no ActionServiceImpl.runInProcess", !run.isEmpty());
        for (final String body : new String[] {enqueue, run}) {
            assertFalse("the in-process path must not queue a job",
                    body.contains("enqueueWork(") || body.contains("startServiceWithIntent(")
                            || body.contains("makeIntent("));
        }
        assertTrue("startActionInProcess must hand the action to the in-process queue",
                enqueue.contains("IN_PROCESS.execute(") && enqueue.contains("runInProcess("));
        final int begin = run.indexOf("markBeginExecute()");
        final int exec = run.indexOf("action.executeAction()");
        final int end = run.indexOf("markEndExecute(");
        final int bg = run.indexOf("sendBackgroundActions(");
        assertTrue("runInProcess must execute the action as onHandleWork does: begin, execute, "
                + "end, then its background actions", begin >= 0 && begin < exec && exec < end
                && end < bg);
    }

    /** The scan must be able to fail. */
    @Test
    public void theScanTellsAJobStartFromAThreadStart() {
        assertEquals(1, jobStarts("{ new A(m).confirming(t).start(); }").size());
        assertEquals(1, jobStarts("{ new A(m)\n  .confirming(t)\n  .start(); }").size());
        assertEquals(1, jobStarts("{ a.start(); }").size());
        assertEquals(0, jobStarts("{ new Thread(() -> { x(); }, \"worker-x\").start(); }").size());
        assertEquals(0,
                jobStarts("{ new Thread(() -> { x(); }, \"worker-x\")\n  .start(); }").size());
        assertEquals(0, jobStarts("{ new A(m).confirming(t).startInProcess(); }").size());
    }

    /**
     * Each {@code .start()} that is not a thread's: a thread is started on the
     * {@code new Thread(..., "name")} it was built by, so its {@code .start()} follows a string
     * literal and a closing parenthesis.
     */
    private static List<String> jobStarts(final String code) {
        final List<String> out = new ArrayList<>();
        for (final int at : SourceScan.indicesOf(code, ".start()")) {
            final String before = code.substring(0, at).replaceAll("\\s+$", "");
            if (before.endsWith("\")")) continue;
            final int from = Math.max(0, before.length() - 80);
            out.add(before.substring(from).replaceAll("\\s+", " ") + ".start()");
        }
        return out;
    }
}
