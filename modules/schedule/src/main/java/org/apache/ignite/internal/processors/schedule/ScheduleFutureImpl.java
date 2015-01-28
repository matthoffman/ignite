/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.schedule;

import it.sauronsoftware.cron4j.*;
import org.apache.ignite.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.scheduler.*;
import org.apache.ignite.internal.processors.timeout.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.internal.util.future.*;
import org.apache.ignite.internal.util.tostring.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

import static java.util.concurrent.TimeUnit.*;
import static org.apache.ignite.IgniteSystemProperties.*;

/**
 * Implementation of {@link org.apache.ignite.scheduler.SchedulerFuture} interface.
 */
class ScheduleFutureImpl<R> implements SchedulerFuture<R>, Externalizable {
    /** */
    private static final long serialVersionUID = 0L;

    /** Empty time array. */
    private static final long[] EMPTY_TIMES = new long[] {};

    /** Identifier generated by cron scheduler. */
    private volatile String id;

    /** Scheduling pattern. */
    private String pat;

    /** Scheduling delay in seconds parsed from pattern. */
    private int delay;

    /** Number of maximum task calls parsed from pattern. */
    private int maxCalls;

    /** Mere cron pattern parsed from extended pattern. */
    private String cron;

    /** Cancelled flag. */
    private boolean cancelled;

    /** Done flag. */
    private boolean done;

    /** Task calls counter. */
    private int callCnt;

    /** De-schedule flag. */
    private final AtomicBoolean descheduled = new AtomicBoolean(false);

    /** Listeners. */
    private Collection<IgniteInClosure<? super IgniteFuture<R>>> lsnrs =
        new ArrayList<>(1);

    /** Statistics. */
    @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
    private GridScheduleStatistics stats = new GridScheduleStatistics();

    /** Latch synchronizing fetch of the next execution result. */
    @GridToStringExclude
    private CountDownLatch resLatch = new CountDownLatch(1);

    /** Cron scheduler. */
    @GridToStringExclude
    private Scheduler sched;

    /** Processor registry. */
    @GridToStringExclude
    private GridKernalContext ctx;

    /** Execution task. */
    @GridToStringExclude
    private Callable<R> task;

    /** Result of the last execution of scheduled task. */
    @GridToStringExclude
    private R lastRes;

    /** Keeps last execution exception or {@code null} if the last execution was successful. */
    @GridToStringExclude
    private Throwable lastErr;

    /** Listener call count. */
    private int lastLsnrExecCnt;

    /** Synchronous notification flag. */
    private volatile boolean syncNotify = IgniteSystemProperties.getBoolean(IGNITE_FUT_SYNC_NOTIFICATION, true);

    /** Concurrent notification flag. */
    private volatile boolean concurNotify = IgniteSystemProperties.getBoolean(IGNITE_FUT_CONCURRENT_NOTIFICATION, false);

    /** Mutex. */
    private final Object mux = new Object();

    /** Grid logger. */
    private IgniteLogger log;

    /** Runnable object to schedule with cron scheduler. */
    private final Runnable run = new Runnable() {
        @Nullable private CountDownLatch onStart() {
            synchronized (mux) {
                if (done || cancelled)
                    return null;

                if (stats.isRunning()) {
                    U.warn(log, "Task got scheduled while previous was not finished: " + this);

                    return null;
                }

                if (callCnt == maxCalls && maxCalls > 0)
                    return null;

                callCnt++;

                stats.onStart();

                assert resLatch != null;

                return resLatch;
            }
        }

        @SuppressWarnings({"ErrorNotRethrown"})
        @Override public void run() {
            CountDownLatch latch = onStart();

            if (latch == null)
                return;

            R res = null;

            Throwable err = null;

            try {
                res = task.call();
            }
            catch (Exception e) {
                err = e;
            }
            catch (Error e) {
                err = e;

                U.error(log, "Error occurred while executing scheduled task: " + this, e);
            }
            finally {
                if (!onEnd(latch, res, err, false))
                    deschedule();
            }
        }
    };

    /**
     * Empty constructor required by {@link Externalizable}.
     */
    public ScheduleFutureImpl() {
        // No-op.
    }

    /**
     * Creates descriptor for task scheduling. To start scheduling call {@link #schedule(Callable)}.
     *
     * @param sched Cron scheduler.
     * @param ctx Kernal context.
     * @param pat Cron pattern.
     */
    ScheduleFutureImpl(Scheduler sched, GridKernalContext ctx, String pat) {
        assert sched != null;
        assert ctx != null;
        assert pat != null;

        this.sched = sched;
        this.ctx = ctx;
        this.pat = pat.trim();

        log = ctx.log(getClass());

        try {
            parsePatternParameters();
        }
        catch (IgniteCheckedException e) {
            onEnd(resLatch, null, e, true);
        }
    }

    /**
     * @param latch Latch.
     * @param res Result.
     * @param err Error.
     * @return {@code False} if future should be unschedule
     */
    private boolean onEnd(CountDownLatch latch, R res, Throwable err, boolean initErr) {
        assert latch != null;

        boolean notifyLsnr = false;

        CountDownLatch resLatchCp = null;

        try {
            synchronized (mux) {
                lastRes = res;
                lastErr = err;

                if (initErr) {
                    assert err != null;

                    notifyLsnr = true;
                }
                else {
                    stats.onEnd();

                    int cnt = stats.getExecutionCount();

                    if (lastLsnrExecCnt != cnt) {
                        notifyLsnr = true;

                        lastLsnrExecCnt = cnt;
                    }
                }

                if ((callCnt == maxCalls && maxCalls > 0) || cancelled || initErr) {
                    done = true;

                    resLatchCp = resLatch;

                    resLatch = null;

                    return false;
                }

                resLatch = new CountDownLatch(1);

                return true;
            }
        }
        finally {
            // Unblock all get() invocations.
            latch.countDown();

            // Make sure that none will be blocked on new latch if this
            // future will not be executed any more.
            if (resLatchCp != null)
                resLatchCp.countDown();

            if (notifyLsnr)
                notifyListeners(res, err);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean concurrentNotify() {
        return concurNotify;
    }

    /** {@inheritDoc} */
    @Override public void concurrentNotify(boolean concurNotify) {
        this.concurNotify = concurNotify;
    }

    /** {@inheritDoc} */
    @Override public boolean syncNotify() {
        return syncNotify;
    }

    /** {@inheritDoc} */
    @Override public void syncNotify(boolean syncNotify) {
        this.syncNotify = syncNotify;
    }

    /**
     * Sets execution task.
     *
     * @param task Execution task.
     */
    void schedule(Callable<R> task) {
        assert task != null;
        assert this.task == null;

        // Done future on this step means that there was error on init.
        if (isDone())
            return;

        this.task = task;

        ((IgniteScheduleProcessor)ctx.schedule()).onScheduled(this);

        if (delay > 0) {
            // Schedule after delay.
            ctx.timeout().addTimeoutObject(new GridTimeoutObjectAdapter(delay * 1000) {
                @Override public void onTimeout() {
                    assert id == null;

                    try {
                        id = sched.schedule(cron, run);
                    }
                    catch (InvalidPatternException e) {
                        // This should never happen as we validated the pattern during parsing.
                        e.printStackTrace();

                        assert false : "Invalid scheduling pattern: " + cron;
                    }
                }
            });
        }
        else {
            assert id == null;

            try {
                id = sched.schedule(cron, run);
            }
            catch (InvalidPatternException e) {
                // This should never happen as we validated the pattern during parsing.
                e.printStackTrace();

                assert false : "Invalid scheduling pattern: " + cron;
            }
        }
    }

    /**
     * De-schedules scheduled task.
     */
    void deschedule() {
        if (descheduled.compareAndSet(false, true)) {
            sched.deschedule(id);

            ((IgniteScheduleProcessor)ctx.schedule()).onDescheduled(this);
        }
    }

    /**
     * Parse delay, number of task calls and mere cron expression from extended pattern
     *  that looks like  "{n1,n2} * * * * *".
     * @throws IgniteCheckedException Thrown if pattern is invalid.
     */
    private void parsePatternParameters() throws IgniteCheckedException {
        assert pat != null;

        String regEx = "(\\{(\\*|\\d+),\\s*(\\*|\\d+)\\})?(.*)";

        Matcher matcher = Pattern.compile(regEx).matcher(pat.trim());

        if (matcher.matches()) {
            String delayStr = matcher.group(2);

            if (delayStr != null)
                if ("*".equals(delayStr))
                    delay = 0;
                else
                    try {
                        delay = Integer.valueOf(delayStr);
                    }
                    catch (NumberFormatException e) {
                        throw new IgniteCheckedException("Invalid delay parameter in schedule pattern [delay=" +
                            delayStr + ", pattern=" + pat + ']', e);
                    }

            String numOfCallsStr = matcher.group(3);

            if (numOfCallsStr != null) {
                int maxCalls0;

                if ("*".equals(numOfCallsStr))
                    maxCalls0 = 0;
                else {
                    try {
                        maxCalls0 = Integer.valueOf(numOfCallsStr);
                    }
                    catch (NumberFormatException e) {
                        throw new IgniteCheckedException("Invalid number of calls parameter in schedule pattern [numOfCalls=" +
                            numOfCallsStr + ", pattern=" + pat + ']', e);
                    }

                    if (maxCalls0 <= 0)
                        throw new IgniteCheckedException("Number of calls must be greater than 0 or must be equal to \"*\"" +
                            " in schedule pattern [numOfCalls=" + maxCalls0 + ", pattern=" + pat + ']');
                }

                synchronized (mux) {
                    maxCalls = maxCalls0;
                }
            }

            cron = matcher.group(4);

            if (cron != null)
                cron = cron.trim();

            // Cron expression should never be empty and should be of correct format.
            if (cron.isEmpty() || !SchedulingPattern.validate(cron))
                throw new IgniteCheckedException("Invalid cron expression in schedule pattern: " + pat);
        }
        else
            throw new IgniteCheckedException("Invalid schedule pattern: " + pat);
    }

    /** {@inheritDoc} */
    @Override public long startTime() {
        return stats.getCreateTime();
    }

    /** {@inheritDoc} */
    @Override public long duration() {
        return stats.getTotalExecutionTime() + stats.getTotalIdleTime();
    }

    /** {@inheritDoc} */
    @Override public String pattern() {
        return pat;
    }

    /** {@inheritDoc} */
    @Override public String id() {
        return id;
    }

    /** {@inheritDoc} */
    @Override public long[] nextExecutionTimes(int cnt, long start) throws IgniteCheckedException {
        assert cnt > 0;
        assert start > 0;

        if (isDone() || isCancelled())
            return EMPTY_TIMES;

        synchronized (mux) {
            if (maxCalls > 0)
                cnt = Math.min(cnt, maxCalls);
        }

        long[] times = new long[cnt];

        if (start < createTime() + delay * 1000)
            start = createTime() + delay * 1000;

        SchedulingPattern ptrn = new SchedulingPattern(cron);

        Predictor p = new Predictor(ptrn, start);

        for (int i = 0; i < cnt; i++)
            times[i] = p.nextMatchingTime();

        return times;
    }

    /** {@inheritDoc} */
    @Override public long nextExecutionTime() throws IgniteCheckedException {
        return nextExecutionTimes(1, U.currentTimeMillis())[0];
    }

    /** {@inheritDoc} */
    @Override public boolean cancel() {
        synchronized (mux) {
            if (done)
                return false;

            if (cancelled)
                return true;

            if (!stats.isRunning())
                done = true;

            cancelled = true;
        }

        deschedule();

        return true;
    }

    /** {@inheritDoc} */
    @Override public long createTime() {
        synchronized (mux) {
            return stats.getCreateTime();
        }
    }

    /** {@inheritDoc} */
    @Override public long lastStartTime() {
        synchronized (mux) {
            return stats.getLastStartTime();
        }
    }

    /** {@inheritDoc} */
    @Override public long lastFinishTime() {
        synchronized (mux) {
            return stats.getLastEndTime();
        }
    }

    /** {@inheritDoc} */
    @Override public double averageExecutionTime() {
        synchronized (mux) {
            return stats.getLastExecutionTime();
        }
    }

    /** {@inheritDoc} */
    @Override public long lastIdleTime() {
        synchronized (mux) {
            return stats.getLastIdleTime();
        }
    }

    /** {@inheritDoc} */
    @Override public double averageIdleTime() {
        synchronized (mux) {
            return stats.getAverageIdleTime();
        }
    }

    /** {@inheritDoc} */
    @Override public int count() {
        synchronized (mux) {
            return stats.getExecutionCount();
        }
    }

    /** {@inheritDoc} */
    @Override public boolean isRunning() {
        synchronized (mux) {
            return stats.isRunning();
        }
    }

    /** {@inheritDoc} */
    @Override public R last() throws IgniteCheckedException {
        synchronized (mux) {
            if (lastErr != null)
                throw U.cast(lastErr);

            return lastRes;
        }
    }

    /** {@inheritDoc} */
    @Override public boolean isCancelled() {
        synchronized (mux) {
            return cancelled;
        }
    }

    /** {@inheritDoc} */
    @Override public boolean isDone() {
        synchronized (mux) {
            return done;
        }
    }

    /** {@inheritDoc} */
    @Override public void listenAsync(@Nullable IgniteInClosure<? super IgniteFuture<R>> lsnr) {
        if (lsnr != null) {
            Throwable err;
            R res;

            boolean notifyLsnr = false;

            synchronized (mux) {
                lsnrs.add(lsnr);

                err = lastErr;
                res = lastRes;

                int cnt = stats.getExecutionCount();

                if (cnt > 0 && lastLsnrExecCnt != cnt) {
                    lastLsnrExecCnt = cnt;

                    notifyLsnr = true;
                }
            }

            // Avoid race condition in case if listener was added after
            // first execution completed.
            if (notifyLsnr)
                notifyListener(lsnr, res, err, syncNotify);
        }
    }

    /** {@inheritDoc} */
    @Override public void stopListenAsync(@Nullable IgniteInClosure<? super IgniteFuture<R>>... lsnr) {
        if (!F.isEmpty(lsnr))
            synchronized (mux) {
                lsnrs.removeAll(F.asList(lsnr));
            }
    }

    /** {@inheritDoc} */
    @SuppressWarnings("ExternalizableWithoutPublicNoArgConstructor")
    @Override public <T> IgniteFuture<T> chain(final IgniteClosure<? super IgniteFuture<R>, T> doneCb) {
        final GridFutureAdapter<T> fut = new GridFutureAdapter<T>(ctx, syncNotify) {
            @Override public String toString() {
                return "ChainFuture[orig=" + ScheduleFutureImpl.this + ", doneCb=" + doneCb + ']';
            }
        };

        listenAsync(new GridFutureChainListener<>(ctx, fut, doneCb));

        return fut;
    }

    /**
     * @param lsnr Listener to notify.
     * @param res Last execution result.
     * @param err Last execution error.
     * @param syncNotify Synchronous notification flag.
     */
    private void notifyListener(final IgniteInClosure<? super IgniteFuture<R>> lsnr, R res, Throwable err,
        boolean syncNotify) {
        assert lsnr != null;
        assert !Thread.holdsLock(mux);
        assert ctx != null;

        final SchedulerFuture<R> snapshot = snapshot(res, err);

        if (syncNotify)
            lsnr.apply(snapshot);
        else {
            try {
                ctx.closure().runLocalSafe(new Runnable() {
                    @Override public void run() {
                        lsnr.apply(snapshot);
                    }
                }, true);
            }
            catch (Throwable e) {
                U.error(log, "Failed to notify listener: " + this, e);
            }
        }
    }

    /**
     * @param res Last execution result.
     * @param err Last execution error.
     */
    private void notifyListeners(R res, Throwable err) {
        final Collection<IgniteInClosure<? super IgniteFuture<R>>> tmp;

        synchronized (mux) {
            tmp = new ArrayList<>(lsnrs);
        }

        final SchedulerFuture<R> snapshot = snapshot(res, err);

        if (concurNotify) {
            for (final IgniteInClosure<? super IgniteFuture<R>> lsnr : tmp)
                ctx.closure().runLocalSafe(new GPR() {
                    @Override public void run() {
                        lsnr.apply(snapshot);
                    }
                }, true);
        }
        else {
            ctx.closure().runLocalSafe(new GPR() {
                @Override public void run() {
                    for (IgniteInClosure<? super IgniteFuture<R>> lsnr : tmp)
                        lsnr.apply(snapshot);
                }
            }, true);
        }
    }

    /**
     * Checks that the future is in valid state for get operation.
     *
     * @return Latch or {@code null} if future has been finished.
     * @throws org.apache.ignite.lang.IgniteFutureCancelledException If was cancelled.
     */
    @Nullable private CountDownLatch ensureGet() throws IgniteFutureCancelledException {
        synchronized (mux) {
            if (cancelled)
                throw new IgniteFutureCancelledException("Scheduling has been cancelled: " + this);

            if (done)
                return null;

            return resLatch;
        }
    }

    /** {@inheritDoc} */
    @Nullable @Override public R get() throws IgniteCheckedException {
        CountDownLatch latch = ensureGet();

        if (latch != null) {
            try {
                latch.await();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                if (isCancelled())
                    throw new IgniteFutureCancelledException(e);

                if (isDone())
                    return last();

                throw new IgniteInterruptedException(e);
            }
        }

        return last();
    }

    /** {@inheritDoc} */
    @Nullable @Override public R get(long timeout) throws IgniteCheckedException {
        return get(timeout, MILLISECONDS);
    }

    /** {@inheritDoc} */
    @Nullable @Override public R get(long timeout, TimeUnit unit) throws IgniteCheckedException {
        CountDownLatch latch = ensureGet();

        if (latch != null) {
            try {
                if (latch.await(timeout, unit))
                    return last();
                else
                    throw new IgniteFutureTimeoutException("Timed out waiting for completion of next " +
                        "scheduled computation: " + this);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                if (isCancelled())
                    throw new IgniteFutureCancelledException(e);

                if (isDone())
                    return last();

                throw new IgniteInterruptedException(e);
            }
        }

        return last();
    }

    /**
     * Creates a snapshot of this future with fixed last result.
     *
     * @param res Last result.
     * @param err Last error.
     * @return Future snapshot.
     */
    private SchedulerFuture<R> snapshot(R res, Throwable err) {
        return new ScheduleFutureSnapshot<>(this, res, err);
    }

    /**
     * Future snapshot.
     *
     * @param <R>
     */
    private static class ScheduleFutureSnapshot<R> implements SchedulerFuture<R> {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        private ScheduleFutureImpl<R> ref;

        /** */
        private R res;

        /** */
        private Throwable err;

        /**
         *
         * @param ref Referenced implementation.
         * @param res Last result.
         * @param err Throwable.
         */
        ScheduleFutureSnapshot(ScheduleFutureImpl<R> ref, R res, Throwable err) {
            assert ref != null;

            this.ref = ref;
            this.res = res;
            this.err = err;
        }

        /** {@inheritDoc} */
        @Override public R last() throws IgniteCheckedException {
            if (err != null)
                throw U.cast(err);

            return res;
        }

        /** {@inheritDoc} */
        @Override public long startTime() {
            return ref.startTime();
        }

        /** {@inheritDoc} */
        @Override public long duration() {
            return ref.duration();
        }

        /** {@inheritDoc} */
        @Override public boolean concurrentNotify() {
            return ref.concurrentNotify();
        }

        /** {@inheritDoc} */
        @Override public void concurrentNotify(boolean concurNotify) {
            ref.concurrentNotify(concurNotify);
        }

        /** {@inheritDoc} */
        @Override public void syncNotify(boolean syncNotify) {
            ref.syncNotify(syncNotify);
        }

        /** {@inheritDoc} */
        @Override public boolean syncNotify() {
            return ref.syncNotify();
        }

        /** {@inheritDoc} */
        @Override public String id() {
            return ref.id();
        }

        /** {@inheritDoc} */
        @Override public String pattern() {
            return ref.pattern();
        }

        /** {@inheritDoc} */
        @Override public long createTime() {
            return ref.createTime();
        }

        /** {@inheritDoc} */
        @Override public long lastStartTime() {
            return ref.lastStartTime();
        }

        /** {@inheritDoc} */
        @Override public long lastFinishTime() {
            return ref.lastFinishTime();
        }

        /** {@inheritDoc} */
        @Override public double averageExecutionTime() {
            return ref.averageExecutionTime();
        }

        /** {@inheritDoc} */
        @Override public long lastIdleTime() {
            return ref.lastIdleTime();
        }

        /** {@inheritDoc} */
        @Override public double averageIdleTime() {
            return ref.averageIdleTime();
        }

        /** {@inheritDoc} */
        @Override public long[] nextExecutionTimes(int cnt, long start) throws IgniteCheckedException {
            return ref.nextExecutionTimes(cnt, start);
        }

        /** {@inheritDoc} */
        @Override public int count() {
            return ref.count();
        }

        /** {@inheritDoc} */
        @Override public boolean isRunning() {
            return ref.isRunning();
        }

        /** {@inheritDoc} */
        @Override public long nextExecutionTime() throws IgniteCheckedException {
            return ref.nextExecutionTime();
        }

        /** {@inheritDoc} */
        @Nullable @Override public R get() throws IgniteCheckedException {
            return ref.get();
        }

        /** {@inheritDoc} */
        @Nullable @Override public R get(long timeout) throws IgniteCheckedException {
            return ref.get(timeout);
        }

        /** {@inheritDoc} */
        @Nullable @Override public R get(long timeout, TimeUnit unit) throws IgniteCheckedException {
            return ref.get(timeout, unit);
        }

        /** {@inheritDoc} */
        @Override public boolean cancel() {
            return ref.cancel();
        }

        /** {@inheritDoc} */
        @Override public boolean isDone() {
            return ref.isDone();
        }

        /** {@inheritDoc} */
        @Override public boolean isCancelled() {
            return ref.isCancelled();
        }

        /** {@inheritDoc} */
        @Override public void listenAsync(@Nullable IgniteInClosure<? super IgniteFuture<R>> lsnr) {
            ref.listenAsync(lsnr);
        }

        /** {@inheritDoc} */
        @Override public void stopListenAsync(@Nullable IgniteInClosure<? super IgniteFuture<R>>... lsnr) {
            ref.stopListenAsync(lsnr);
        }

        /** {@inheritDoc} */
        @Override public <T> IgniteFuture<T> chain(IgniteClosure<? super IgniteFuture<R>, T> doneCb) {
            return ref.chain(doneCb);
        }
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        boolean cancelled;
        R lastRes;
        Throwable lastErr;
        GridScheduleStatistics stats;

        synchronized (mux) {
            cancelled = this.cancelled;
            lastRes = this.lastRes;
            lastErr = this.lastErr;
            stats = this.stats;
        }

        out.writeBoolean(cancelled);
        out.writeObject(lastRes);
        out.writeObject(lastErr);
        out.writeObject(stats);

        out.writeBoolean(syncNotify);
        out.writeBoolean(concurNotify);
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        CountDownLatch latch = new CountDownLatch(0);

        boolean cancelled = in.readBoolean();
        R lastRes = (R)in.readObject();
        Throwable lastErr = (Throwable)in.readObject();
        GridScheduleStatistics stats = (GridScheduleStatistics)in.readObject();

        syncNotify = in.readBoolean();
        concurNotify = in.readBoolean();

        synchronized (mux) {
            done = true;

            resLatch = latch;

            this.cancelled = cancelled;
            this.lastRes = lastRes;
            this.lastErr = lastErr;
            this.stats = stats;
        }
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(ScheduleFutureImpl.class, this);
    }
}
