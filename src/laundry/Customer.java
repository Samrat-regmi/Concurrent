package laundry;

import java.util.Random;

/**
 * One customer = one thread (basic requirement 1).
 *
 * <p>The thread walks through the whole lifecycle on its own: it arrives, queues
 * for a washer, washes, queues for a dryer, dries, queues for a kiosk and pays.
 * All coordination is done by blocking on the device pools, therefore no central
 * scheduler is needed and many customers are genuinely in different stages at the
 * same time (visible in the log and in the GUI).</p>
 *
 * <h2>Error handling</h2>
 * <ul>
 *   <li>Washing: with {@code washerFailProbability} the machine breaks mid-cycle.
 *       The customer loses the machine, waits for the technician and then re-joins
 *       the washer queue (retry loop).</li>
 *   <li>Payment: with {@code kioskFailProbability} the kiosk rejects the card. The
 *       customer releases the kiosk, waits {@code kioskRetryDelaySec} seconds and
 *       tries again - as required.</li>
 * </ul>
 */
public final class Customer implements Runnable {

    private final int id;
    private final SimulationConfig cfg;
    private final SimClock clock;
    private final EventLog log;
    private final Random rnd;

    private final MachinePool washers;
    private final MachinePool dryers;
    private final MachinePool kiosks;
    private final Statistics stats;
    private final Laundromat laundromat;

    /** Measured timings (simulated seconds) used for the statistics report. */
    private double arrivalSimSec;
    private double waitingSec;

    public Customer(int id, SimulationConfig cfg, SimClock clock, EventLog log, Random rnd,
                    MachinePool washers, MachinePool dryers, MachinePool kiosks,
                    Statistics stats, Laundromat laundromat) {
        this.id = id;
        this.cfg = cfg;
        this.clock = clock;
        this.log = log;
        this.rnd = rnd;
        this.washers = washers;
        this.dryers = dryers;
        this.kiosks = kiosks;
        this.stats = stats;
        this.laundromat = laundromat;
    }

    public int id() { return id; }
    public double arrivalSimSec() { return arrivalSimSec; }

    @Override
    public void run() {
        try {
            arrive();
            double washSec = doLaundry();
            double drySec  = doDry();
            double paySec  = doPay();
            finish(washSec, drySec, paySec);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.log(SimulationEvent.Type.ERROR, id, "C" + id + " interrupted, leaving shop");
        } finally {
            laundromat.customerFinished(this);
        }
    }

    // ------------------------------------------------------------------ arrival
    private void arrive() throws InterruptedException {
        arrivalSimSec = clock.simSeconds();
        stats.customerArrived();
        laundromat.registerActive(id, CustomerState.ARRIVING, arrivalSimSec);
        log.log(SimulationEvent.Type.ARRIVAL, id,
                String.format("Customer C%02d enters the laundromat with a full basket", id));
    }

    // ------------------------------------------------------------------ washing
    private double doLaundry() throws InterruptedException {
        double total = 0;
        int attempt = 0;
        while (true) {
            attempt++;
            laundromat.registerActive(id, CustomerState.WAITING_WASHER, Double.NaN);
            long q0 = System.nanoTime();
            Machine w = washers.acquireAny(id);
            waitingSec += (System.nanoTime() - q0) / 1_000_000_000.0 / (cfg.msPerSecond / 1000.0);
            double t0 = clock.simSeconds();
            laundromat.registerActive(id, CustomerState.WASHING, t0);
            log.log(SimulationEvent.Type.WASH_START, id,
                    String.format("%s started (attempt %d)", w.label(), attempt));

            // decide the outcome up-front, but reveal the breakdown mid-cycle
            boolean willFail = rnd.nextDouble() < cfg.washerFailProbability;
            double duration = uniform(cfg.washMinSec, cfg.washMaxSec);
            w.expectCycle(duration);                       // GUI progress bar reference
            double failAt = willFail ? duration * (0.4 + rnd.nextDouble() * 0.4) : duration;
            clock.sleepSim(failAt);

            if (willFail) {
                double spent = clock.simSeconds() - t0;
                total += spent;
                washers.fail(w, id);
                stats.washerFailed();
                log.log(SimulationEvent.Type.WASH_FAIL, id,
                        String.format("%s BROKE DOWN after %.2fs - clothes ruined! Re-queueing for another washer",
                                w.label(), spent));
                // technician repairs the washer after a short while (background thread)
                scheduleRepair(w);
                stats.washerRetried();
                Thread.sleep(Math.max(20, clock.toRealMillis(cfg.washerRepairTimeSec)));
                continue;                                   // wait and retry on another washer
            }

            clock.sleepSim(duration - failAt);
            washers.release(w, id);
            total += clock.simSeconds() - t0;
            laundromat.registerActive(id, CustomerState.WASH_DONE, clock.simSeconds());
            log.log(SimulationEvent.Type.WASH_DONE, id,
                    String.format("%s finished after %.2fs -> looking for a dryer", w.label(), total));
            return total;
        }
    }

    private void scheduleRepair(Machine brokenMachine) {
        Thread t = new Thread(() -> {
            try {
                clock.sleepSim(cfg.washerRepairTimeSec);
                brokenMachine.repair();
                log.log(SimulationEvent.Type.REPAIR, id,
                        String.format("Technician repaired %s - back in service", brokenMachine.label()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                brokenMachine.repair();
            }
        }, "washer-repair-" + brokenMachine.id());
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------- drying
    private double doDry() throws InterruptedException {
        laundromat.registerActive(id, CustomerState.WAITING_DRYER, Double.NaN);
        long q0 = System.nanoTime();
        Machine d = dryers.acquireAny(id);
        waitingSec += elapsedSim(q0);
        double t0 = clock.simSeconds();
        laundromat.registerActive(id, CustomerState.DRYING, t0);
        double duration = uniform(cfg.dryMinSec, cfg.dryMaxSec);
        d.expectCycle(duration);                           // GUI progress bar reference
        log.log(SimulationEvent.Type.DRY_START, id,
                String.format("%s started (%.2fs)", d.label(), duration));
        clock.sleepSim(duration);
        dryers.release(d, id);
        laundromat.registerActive(id, CustomerState.DRY_DONE, clock.simSeconds());
        log.log(SimulationEvent.Type.DRY_DONE, id,
                String.format("%s finished after %.2fs -> heading to a payment kiosk", d.label(), duration));
        return duration;
    }

    // ------------------------------------------------------------------- payment
    private double doPay() throws InterruptedException {
        double total = 0;
        int attempt = 0;
        while (true) {
            attempt++;
            laundromat.registerActive(id, CustomerState.WAITING_PAYMENT, Double.NaN);
            long q0 = System.nanoTime();
            Machine k = kiosks.acquireAny(id);
            waitingSec += elapsedSim(q0);
            double t0 = clock.simSeconds();
            laundromat.registerActive(id, CustomerState.PAYING, t0);
            double duration = uniform(cfg.payMinSec, cfg.payMaxSec);
            k.expectCycle(duration);                       // GUI progress bar reference
            log.log(SimulationEvent.Type.PAY_START, id,
                    String.format("%s: paying (attempt %d)", k.label(), attempt));
            clock.sleepSim(duration);

            // a permanently broken kiosk can never complete a payment; with working
            // kiosks the failure is decided by the configured probability
            boolean failed = k.isBroken() || rnd.nextDouble() < cfg.kioskFailProbability;
            if (failed) {
                total += clock.simSeconds() - t0;
                kiosks.release(k, id);
                stats.kioskFailed();
                log.log(SimulationEvent.Type.PAY_FAIL, id,
                        String.format("%s REJECTED the payment - retrying in %.1fs",
                                k.label(), cfg.kioskRetryDelaySec));
                clock.sleepSim(cfg.kioskRetryDelaySec);
                stats.kioskRetried();
                continue;
            }

            kiosks.release(k, id);
            total += duration;
            laundromat.registerActive(id, CustomerState.LEAVING, clock.simSeconds());
            log.log(SimulationEvent.Type.PAY_DONE, id,
                    String.format("%s: payment accepted after %.2fs (%d attempt(s)) - leaving happy",
                            k.label(), total, attempt));
            return total;
        }
    }

    // -------------------------------------------------------------------- finish
    private void finish(double washSec, double drySec, double paySec) {
        double total = clock.simSeconds() - arrivalSimSec;
        stats.customerServed(id, total, washSec, drySec, paySec, waitingSec);
        laundromat.registerActive(id, CustomerState.DONE, clock.simSeconds());
        log.log(SimulationEvent.Type.INFO, id,
                String.format("C%02d COMPLETED: total %.2fs (wash %.2fs, dry %.2fs, pay %.2fs, queue %.2fs)",
                        id, total, washSec, drySec, paySec, waitingSec));
    }

    // ------------------------------------------------------------------- helpers
    private double elapsedSim(long startNano) {
        double realSec = (System.nanoTime() - startNano) / 1_000_000_000.0;
        return realSec / (cfg.msPerSecond / 1000.0);
    }

    private double uniform(double min, double max) {
        return min + rnd.nextDouble() * (max - min);
    }
}
