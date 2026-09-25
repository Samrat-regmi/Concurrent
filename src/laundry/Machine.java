package laundry;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One physical device of the laundromat: a washing machine, a tumble dryer or a
 * payment kiosk.
 *
 * <h2>Mutual exclusion</h2>
 * Access is guarded by a fair one-permit {@link Semaphore}: a customer must
 * acquire it before using the device and always releases it in a
 * {@code finally} block, so neither an exception nor an interrupt can leak the
 * lock. A small monitor object ({@code stateMonitor}) protects the mutable
 * bookkeeping (occupant / broken flag) that both worker threads and the GUI read.
 *
 * <h2>Breakdowns</h2>
 * When a device fails mid-cycle {@link #failAndRelease()} keeps the permit
 * un-released and flags the device as broken, i.e. it disappears from the pool
 * until {@link #repair()} hands the permit back. Waiting customers therefore
 * automatically migrate to another free device.
 */
public final class Machine {

    public enum Kind { WASHER, DRYER, KIOSK }

    private final int id;
    private final Kind kind;
    private final String label;

    private final Semaphore lock = new Semaphore(1, true); // fair => FIFO service
    private final Object stateMonitor = new Object();

    private volatile boolean occupied = false;
    private volatile boolean broken = false;
    private volatile int occupantId = -1;
    private volatile double cycleEstimateSec = Double.NaN;   // expected length of the running cycle (for the GUI progress bar)
    private volatile double busySinceSimSec = Double.NaN;

    private final AtomicInteger cyclesDone = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();

    public Machine(int id, Kind kind) {
        this.id = id;
        this.kind = kind;
        this.label = switch (kind) {
            case WASHER -> "Washer-" + id;
            case DRYER  -> "Dryer-"  + id;
            case KIOSK  -> "Kiosk-"  + id;
        };
    }

    public int id()               { return id; }
    public Kind kind()            { return kind; }
    public String label()         { return label; }
    public boolean isBroken()     { return broken; }
    public boolean isBusy()       { return occupied && !broken; }
    public int occupant()         { return occupantId; }
    public int cyclesDone()       { return cyclesDone.get(); }
    public int failures()         { return failures.get(); }
    public double busySinceSimSec(){ return busySinceSimSec; }
    public double cycleEstimateSec(){ return cycleEstimateSec; }

    /** Declares how long the current cycle is expected to take (GUI progress bar only). */
    public void expectCycle(double simSeconds) { this.cycleEstimateSec = simSeconds; }

    /** Blocks until this device is free, then occupies it for {@code customerId}. */
    public void acquire(int customerId, SimClock clock) throws InterruptedException {
        lock.acquire();
        occupied = true;
        occupantId = customerId;
        busySinceSimSec = clock.simSeconds();
    }

    /** Non-blocking variant used when polling for any free device. */
    public boolean tryAcquire(int customerId, SimClock clock) {
        if (!lock.tryAcquire()) {
            return false;
        }
        occupied = true;
        occupantId = customerId;
        busySinceSimSec = clock.simSeconds();
        return true;
    }

    /** Hands the device back after a completed cycle. */
    public void release() {
        occupied = false;
        occupantId = -1;
        busySinceSimSec = Double.NaN;
        cycleEstimateSec = Double.NaN;
        cyclesDone.incrementAndGet();
        lock.release();
        notifyStateChange();
    }

    /**
     * Device broke down while {@code customerId} was using it. The permit is
     * deliberately NOT returned, so the device leaves the usable pool until repaired.
     */
    public void failAndRelease() {
        occupied = false;
        occupantId = -1;
        busySinceSimSec = Double.NaN;
        cycleEstimateSec = Double.NaN;
        broken = true;
        failures.incrementAndGet();
        notifyStateChange();
    }

    /** Technician / owner puts the device back into service. */
    public void repair() {
        synchronized (stateMonitor) {
            broken = false;
            stateMonitor.notifyAll();
        }
        if (lock.availablePermits() == 0) {
            lock.release();
        }
    }

    /** Lets waiting threads wake up as soon as a repair completes. */
    public void awaitRepair(SimClock clock) throws InterruptedException {
        while (broken) {
            synchronized (stateMonitor) {
                stateMonitor.wait(200);
            }
            if (!broken) break;
            clock.sleepSim(0.2);
        }
    }

    private void notifyStateChange() {
        synchronized (stateMonitor) {
            stateMonitor.notifyAll();
        }
    }

    /** Short status string rendered by the GUI. */
    public String statusText() {
        if (broken) return "BROKEN";
        if (occupied) return "BUSY C" + String.format("%02d", occupantId);
        return "IDLE";
    }
}
