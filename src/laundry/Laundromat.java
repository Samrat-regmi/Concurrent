package laundry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The simulation engine: builds the facility, spawns one thread per customer and
 * coordinates arrival timing, the bonus "call the owner" reaction and shutdown.
 *
 * <p>Design notes</p>
 * <ul>
 *   <li>A fixed {@link ExecutorService} with as many worker threads as customers
 *       guarantees that all 50 customer threads can be alive simultaneously
 *       (requirement 4: machines must operate in parallel).</li>
 *   <li>The main thread plays the role of the front door: it sleeps a random
 *       0-3s between two arrivals and then submits the next customer task.</li>
 *   <li>{@link #snapshot()} is an immutable, read-only view consumed by the GUI so
 *       that painting never holds a lock used by the simulation threads.</li>
 * </ul>
 */
public final class Laundromat {

    /** Immutable copy of one device, as needed to paint a frame. */
    public record MachineView(String label, String status, boolean busy, boolean broken,
                              double usedSec, double expectedSec) {}

    /** One customer currently inside the shop. */
    public record ActiveCustomer(int id, CustomerState state, double sinceSimSec) {}

    /** Everything the GUI needs for one frame. */
    public record Snapshot(double simTimeSec,
                           int arrived, int served, int expected, int inShop,
                           List<MachineView> washers,
                           List<MachineView> dryers,
                           List<MachineView> kiosks,
                           int queueWashers, int queueDryers, int queuePayment,
                           int maxWashersInUse, int maxDryersInUse,
                           boolean congested, boolean ownerCalled, boolean ownerWorking,
                           boolean finished,
                           List<ActiveCustomer> active,
                           List<SimulationEvent> recentEvents) {}

    private final SimulationConfig cfg;
    private final SimClock clock;
    private final EventLog log;
    private final Statistics stats = new Statistics();
    private final Random rnd;

    private final MachinePool washerPool;
    private final MachinePool dryerPool;
    private final MachinePool kioskPool;

    private final AtomicInteger arrivedCount = new AtomicInteger();
    /** GUI bookkeeping; guarded by its own monitor with very short critical sections. */
    private final LinkedHashMap<Integer, ActiveCustomer> active = new LinkedHashMap<>();
    private final List<SimulationEvent> recentEvents = new ArrayList<>();
    private static final int EVENT_BUFFER = 250;

    private volatile boolean ownerCalled = false;
    private volatile boolean ownerWorking = false;
    private volatile boolean finished = false;

    public Laundromat(SimulationConfig cfg) {
        this.cfg = cfg;
        this.clock = new SimClock(cfg.msPerSecond);
        this.log = new EventLog();
        this.rnd = new Random(cfg.seed);
        this.washerPool = new MachinePool("washer", Machine.Kind.WASHER, cfg.washers, clock, log);
        this.dryerPool  = new MachinePool("dryer",  Machine.Kind.DRYER,  cfg.dryers,  clock, log);
        this.kioskPool  = new MachinePool("payment kiosk", Machine.Kind.KIOSK, cfg.kiosks, clock, log);
    }

    public SimulationConfig config() { return cfg; }
    public SimClock clock()          { return clock; }
    public EventLog eventLog()       { return log; }
    public Statistics statistics()   { return stats; }
    public MachinePool washers()     { return washerPool; }
    public MachinePool dryers()      { return dryerPool; }
    public MachinePool kiosks()      { return kioskPool; }
    public boolean isFinished()      { return finished; }

    // ------------------------------------------------------------- lifecycle
    public void run() throws InterruptedException {
        log.setListener(this::onEvent);
        log.start(clock);

        log.log(SimulationEvent.Type.INFO, 0, String.format(
                "=== Smart Laundry Facility opens: %d washers, %d dryers, %d payment kiosks, %d customers expected ===",
                cfg.washers, cfg.dryers, cfg.kiosks, cfg.customers));

        if (cfg.congestedScenario) {
            // BONUS: both kiosks are dead from opening time -> congestion guaranteed.
            kioskPool.breakAll();
            kioskPool.setQueueThreshold(cfg.ownerCallThreshold, this::callOwner);
            log.log(SimulationEvent.Type.ERROR, 0, String.format(
                    "OUT OF ORDER: BOTH payment kiosks failed overnight (%d/%d working). Customers will pile up!",
                    kioskPool.workingCount(), kioskPool.size()));
        }

        ExecutorService pool = Executors.newFixedThreadPool(cfg.customers, r -> {
            Thread t = new Thread(r, "customer-thread");
            t.setDaemon(false);
            return t;
        });
        long startedAtMillis = System.currentTimeMillis();
        for (int i = 1; i <= cfg.customers; i++) {
            if (i > 1) {
                double gap = cfg.arrivalMinSec + rnd.nextDouble() * (cfg.arrivalMaxSec - cfg.arrivalMinSec);
                clock.sleepSim(gap);                       // random 0-3 s between arrivals
            }
            long childSeed;
            synchronized (rnd) { childSeed = rnd.nextLong(); }
            pool.submit(new Customer(i, cfg, clock, log, new Random(childSeed),
                    washerPool, dryerPool, kioskPool, stats, this));
        }
        pool.shutdown();

        while (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
            if (System.currentTimeMillis() - startedAtMillis > cfg.maxRunMillis) {
                log.log(SimulationEvent.Type.ERROR, 0,
                        "Safety timeout reached - aborting run and reporting partial statistics");
                pool.shutdownNow();
                break;
            }
        }

        finished = true;
        log.log(SimulationEvent.Type.INFO, 0, String.format(
                "=== Shop closed after %.2f simulated seconds (%.1f real seconds): %d/%d customers served ===",
                clock.simSeconds(), (System.currentTimeMillis() - startedAtMillis) / 1000.0,
                stats.servedCount(), cfg.customers));
        try {
            Thread.sleep(500);                             // let the last events reach console/GUI
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.shutdown();
    }

    /** Called by a customer thread when it leaves the shop. */
    void customerFinished(Customer c) {
        synchronized (active) {
            active.remove(c.id());
        }
    }

    /** BONUS: the payment queue exploded - phone the owner, who repairs both kiosks. */
    private void callOwner() {
        ownerCalled = true;
        stats.ownerCalledIn();
        log.log(SimulationEvent.Type.OWNER, 0, String.format(
                "*** CONGESTION ALERT: %d customers stuck at the payment queue -> the OWNER is called in! ***",
                kioskPool.waitingCount()));
        Thread owner = new Thread(() -> {
            ownerWorking = true;
            try {
                clock.sleepSim(cfg.ownerRepairTimeSec);
                kioskPool.repairAll();
                log.log(SimulationEvent.Type.REPAIR, 0, String.format(
                        "The owner fixed BOTH payment kiosks after %.1fs - the payment queue now drains quickly",
                        cfg.ownerRepairTimeSec));
            } catch (InterruptedException e) {
                kioskPool.repairAll();
                Thread.currentThread().interrupt();
            } finally {
                ownerWorking = false;
            }
        }, "shop-owner");
        owner.setDaemon(true);
        owner.start();
    }

    // ------------------------------------------------------- GUI support code
    void registerActive(int customerId, CustomerState state, double sinceSimSec) {
        if (state == CustomerState.ARRIVING) arrivedCount.incrementAndGet();
        synchronized (active) {
            active.put(customerId, new ActiveCustomer(customerId, state, sinceSimSec));
        }
    }

    /**
     * Single consumer of the event bus: mirrors every event to the console and
     * keeps a bounded ring buffer for the GUI activity feed. Doing the printing
     * here (instead of inside the customer threads) guarantees that log lines can
     * never be interleaved/torn, even with 50 threads running concurrently.
     */
    private void onEvent(SimulationEvent e) {
        synchronized (System.out) {
            System.out.println(e);
        }
        synchronized (recentEvents) {
            recentEvents.add(e);
            while (recentEvents.size() > EVENT_BUFFER) recentEvents.remove(0);
        }
    }

    /** Read-only snapshot for one GUI frame. */
    public Snapshot snapshot() {
        List<ActiveCustomer> act;
        List<SimulationEvent> ev;
        synchronized (active) {
            act = new ArrayList<>(active.values());
            act.sort(Comparator.comparingInt(ActiveCustomer::id));
        }
        synchronized (recentEvents) {
            ev = new ArrayList<>(recentEvents);
        }
        return new Snapshot(clock.simSeconds(), arrivedCount.get(), stats.servedCount(), cfg.customers,
                act.size(), views(washerPool), views(dryerPool), views(kioskPool),
                washerPool.waitingCount(), dryerPool.waitingCount(), kioskPool.waitingCount(),
                washerPool.maxConcurrentInUse(), dryerPool.maxConcurrentInUse(),
                cfg.congestedScenario, ownerCalled, ownerWorking, finished, act, ev);
    }

    private List<MachineView> views(MachinePool p) {
        List<MachineView> out = new ArrayList<>();
        for (Machine m : p.machines()) {
            out.add(new MachineView(m.label(), m.statusText(), m.isBusy(), m.isBroken(),
                    Double.isNaN(m.busySinceSimSec()) ? 0 : clock.simSeconds() - m.busySinceSimSec(),
                    m.cycleEstimateSec()));
        }
        return out;
    }

    public double elapsedSimSeconds() {
        return clock.simSeconds();
    }
}
