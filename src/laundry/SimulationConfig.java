package laundry;

/**
 * All tunable parameters of the smart-laundry simulation live here so that both
 * the normal scenario and the bonus "congested" scenario can be produced from a
 * single, consistent configuration object.
 *
 * <h2>Stated assumptions (normal scenario)</h2>
 * <ul>
 *   <li>A customer is modelled by exactly one thread. 50 threads are created.</li>
 *   <li>Arrival inter-time is uniform in [0,3]s measured from the arrival of the
 *       previous customer, i.e. customers trickle in on average every 1.5s.</li>
 *   <li>Wash time is uniform in [4,6]s, dry time uniform in [3,5]s and payment
 *       processing time uniform in [1,2]s (all inclusive ranges).</li>
 *   <li>Every machine/dryer/kiosk holds ONE load at a time (mutual exclusion) and
 *      is released as soon as its phase finishes - waiting for the next stage
 *      happens in the queue, not on the machine, which keeps throughput high.</li>
 *   <li>A customer's flow is strictly: washer -&gt; dryer -&gt; payment kiosk.</li>
 *   <li>Failure probabilities are evaluated per attempt: 5% chance that a washer
 *       breaks mid-cycle (the customer loses the machine, re-joins the washer
 *       queue and retries) and 5% chance that a kiosk rejects a payment (the
 *       customer waits 2 seconds and retries). Failures are logged but never
 *       abort a customer - everybody eventually gets served.</li>
 *   <li>Simulated time == wall-clock time, therefore the run lasts about
 *       50*1.5s of arrivals + service time ~ 75-90s (see "about 60s" note in
 *       README: the requirement "about 1-2 minutes" governs the final length).</li>
 *   <li>All randomness uses a seeded {@link java.util.Random} so a run with a
 *       given seed is fully reproducible.</li>
 * </ul>
 */
public class SimulationConfig {

    /** Number of washing machines in the facility. */
    public int washers = 6;
    /** Number of tumble dryers in the facility. */
    public int dryers = 4;
    /** Number of self-service payment kiosks. */
    public int kiosks = 2;
    /** Total number of customers that arrive during the simulation. */
    public int customers = 50;

    /** Minimum random delay between two consecutive arrivals (seconds). */
    public double arrivalMinSec = 0.0;
    /** Maximum random delay between two consecutive arrivals (seconds). */
    public double arrivalMaxSec = 3.0;

    /** Minimum washing duration (seconds). */
    public double washMinSec = 4.0;
    /** Maximum washing duration (seconds). */
    public double washMaxSec = 6.0;
    /** Minimum drying duration (seconds). */
    public double dryMinSec = 3.0;
    /** Maximum drying duration (seconds). */
    public double dryMaxSec = 5.0;
    /** Minimum payment-processing duration (seconds). */
    public double payMinSec = 1.0;
    /** Maximum payment-processing duration (seconds). */
    public double payMaxSec = 2.0;

    /** Probability that a washer fails during a cycle (per attempt). */
    public double washerFailProbability = 0.05;
    /** Probability that a kiosk fails while taking a payment (per attempt). */
    public double kioskFailProbability = 0.05;
    /** Cooldown before a failed kiosk accepts the next payment attempt (seconds). */
    public double kioskRetryDelaySec = 2.0;
    /** Repair time of a broken washer before it can be used again (seconds). */
    public double washerRepairTimeSec = 1.0;

    /**
     * BONUS - congested scenario: when true both kiosks are permanently broken for
     * the day, the shop owner walks in once {@link #ownerCallThreshold} customers
     * are stuck in the payment queue and repairs them.
     */
    public boolean congestedScenario = false;
    /** BONUS - number of queued customers that triggers the "call the owner" event. */
    public int ownerCallThreshold = 30;
    /** BONUS - time the owner needs to repair both kiosks once called (seconds). */
    public double ownerRepairTimeSec = 3.0;

    /** Seed for all random decisions; guarantees reproducible runs. */
    public long seed = 20260925L;

    /** Milliseconds represented by one simulated second. */
    public int msPerSecond = 1000;
    /** GUI refresh interval in milliseconds. */
    public int guiRefreshMs = 120;
    /** If true, log lines are printed with the SIMULATED timestamp instead of real elapsed time. */
    public boolean useSimulatedClockInLogs = true;

    /** How long after the last customer finished the GUI window stays open (ms). */
    public long guiCloseDelayMs = 8000;
    /** Safety net: maximum wall-clock duration of a whole run (ms). */
    public long maxRunMillis = 5 * 60 * 1000L;

    public static SimulationConfig normal() {
        return new SimulationConfig();
    }

    /**
     * BONUS scenario factory: both payment kiosks fail for the whole day, so the
     * payment queue explodes until the owner is called in after 30 waiting
     * customers. The clock runs 3x faster so the congestion still resolves inside
     * the requested 1-2 minute window.
     */
    public static SimulationConfig congested(long seed) {
        SimulationConfig c = new SimulationConfig();
        c.seed = seed;
        c.congestedScenario = true;
        c.kioskFailProbability = 1.0;      // every kiosk is dead for the day
        c.kioskRetryDelaySec = 1.0;        // customers retry quickly -> visible chaos
        c.ownerCallThreshold = 30;         // owner is called after 30 in the queue
        c.ownerRepairTimeSec = 3.0;
        c.msPerSecond = 333;               // accelerated clock (~3x)
        return c;
    }

    public int ms(double seconds) {
        return (int) Math.round(seconds * msPerSecond);
    }
}
