package laundry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pool of identical devices (all washers, all dryers or all kiosks) plus the
 * queueing bookkeeping that the requirements ask for:
 *
 *  <ul>
 *    <li>{@link #acquireAny} blocks until ANY device of the pool is free and then
 *        occupies it - this realises "customers must wait if all washing machines
 *        are occupied" without a central scheduler.</li>
 *    <li>An explicit waiting-counter lets us log "queued" events and detect the
 *        congested scenario ("owner is called in after 30 customers at the payment
 *        queue").</li>
 *    <li>The maximum number of simultaneously busy devices is tracked here, which
 *        is exactly the "max concurrent washers/dryers in use" statistic.</li>
 *  </ul>
 */
public final class MachinePool {

    private final String name;
    private final Machine.Kind kind;
    private final List<Machine> machines = new ArrayList<>();
    private final AtomicInteger waiters = new AtomicInteger();
    private final AtomicInteger peakQueueLength = new AtomicInteger();
    private final AtomicInteger maxConcurrentInUse = new AtomicInteger();
    private final EventLog log;
    private final SimClock clock;

    /** Optional hook used by the bonus scenario to call the shop owner. */
    private volatile Runnable onQueueThreshold;
    private volatile int queueThreshold = Integer.MAX_VALUE;
    private volatile boolean thresholdFired = false;

    public MachinePool(String name, Machine.Kind kind, int count, SimClock clock, EventLog log) {
        this.name = name;
        this.kind = kind;
        this.clock = clock;
        this.log = log;
        for (int i = 1; i <= count; i++) {
            machines.add(new Machine(i, kind));
        }
    }

    public String name()                 { return name; }
    public List<Machine> machines()      { return machines; }
    public int size()                    { return machines.size(); }
    public int waitingCount()            { return waiters.get(); }
    public int peakQueueLength()         { return peakQueueLength.get(); }
    public int maxConcurrentInUse()      { return maxConcurrentInUse.get(); }

    public int busyCount() {
        int n = 0;
        for (Machine m : machines) if (m.isBusy()) n++;
        return n;
    }

    public int workingCount() {
        int n = 0;
        for (Machine m : machines) if (!m.isBroken()) n++;
        return n;
    }

    public void setQueueThreshold(int threshold, Runnable callback) {
        this.queueThreshold = threshold;
        this.onQueueThreshold = callback;
        this.thresholdFired = false;
    }

    /** Marks a device as broken from the outside (bonus: both kiosks dead for the day). */
    public void breakAll() {
        for (Machine m : machines) {
            if (!m.isBusy()) {           // idle device can simply be switched off
                m.failAndRelease();
            }
        }
    }

    public void repairAll() {
        for (Machine m : machines) {
            m.repair();
        }
    }

    /**
     * Blocks until any device of this pool is free, occupies it and returns it.
     * Breakdowns of other devices never deadlock the pool: a broken device holds
     * its permit until it is repaired, so {@code tryAcquire} simply fails on it.
     */
    private final java.util.concurrent.atomic.AtomicInteger scanOffset = new AtomicInteger();

    public Machine acquireAny(int customerId) throws InterruptedException {
        boolean queued = false;
        while (true) {
            int offset = scanOffset.getAndIncrement() % Math.max(1, machines.size());
            for (int i = 0; i < machines.size(); i++) {
                Machine m = machines.get((offset + i) % machines.size());
                if (m.isBroken()) continue;
                if (m.tryAcquire(customerId, clock)) {
                    if (queued) {
                        waiters.decrementAndGet();
                        log.log(SimulationEvent.Type.QUEUE, customerId,
                                String.format("%s queue cleared for C%02d (%d still waiting)",
                                        name, customerId, waiters.get()));
                        queued = false;
                    }
                    trackConcurrency();
                    return m;
                }
            }
            if (!queued) {
                int w = waiters.incrementAndGet();
                int prevPeak = peakQueueLength.get();
                while (w > prevPeak && !peakQueueLength.compareAndSet(prevPeak, w)) {
                    prevPeak = peakQueueLength.get();
                }
                log.log(SimulationEvent.Type.QUEUE, customerId,
                        String.format("C%02d joins %s queue (waiting=%d, devices=%d/%d working)",
                                customerId, name, w, busyCount(), size()));
                maybeCallOwner(w);
                queued = true;
            }
            Thread.sleep(Math.max(20, clock.toRealMillis(0.05)));   // short back-off poll
        }
    }

    /** Called by a customer thread when its cycle ended successfully. */
    public void release(Machine m, int customerId) {
        m.release();
        trackConcurrency();
    }

    /** Called by a customer thread when its device failed mid-cycle. */
    public void fail(Machine m, int customerId) {
        m.failAndRelease();
        trackConcurrency();
    }

    private void trackConcurrency() {
        int busy = busyCount();
        int prev = maxConcurrentInUse.get();
        while (busy > prev && !maxConcurrentInUse.compareAndSet(prev, busy)) {
            prev = maxConcurrentInUse.get();
        }
    }

    private void maybeCallOwner(int queueLength) {
        Runnable r = onQueueThreshold;
        if (r != null && queueLength >= queueThreshold && !thresholdFired) {
            synchronized (this) {
                if (!thresholdFired) {
                    thresholdFired = true;
                    r.run();
                }
            }
        }
    }

    /** Diagnostic helper for the final report. */
    public int totalFailures() {
        int n = 0;
        for (Machine m : machines) n += m.failures();
        return n;
    }

    public Machine.Kind kind() { return kind; }
}
