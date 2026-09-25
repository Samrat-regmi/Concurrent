package laundry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-safe collection of the required statistics:
 *  - total customers served
 *  - average total time per customer (arrival -> payment finished)
 *  - max concurrent washers / dryers in use (tracked inside {@link MachinePool})
 * plus a few extra numbers that make the report more useful (per-stage averages,
 * failure counters, longest stay). Every mutation happens under its own monitor so
 * concurrent customer threads can safely contribute their timings.
 */
public final class Statistics {

    private final Object monitor = new Object();

    private int arrived;
    private int served;
    private double totalTimeSumSec;
    private double washTimeSumSec;
    private double dryTimeSumSec;
    private double payTimeSumSec;
    private double waitTimeSumSec;
    private double longestStaySec;

    private int washerFailures;
    private int kioskFailures;
    private int washerRetries;
    private int kioskRetries;
    private int ownerCalls;

    private final List<String> perCustomer = new ArrayList<>();

    public void customerArrived() {
        synchronized (monitor) { arrived++; }
    }

    /** A customer finished the whole flow: record all measured durations (simulated seconds). */
    public void customerServed(int id, double totalSec, double washSec, double drySec,
                               double paySec, double waitSec) {
        synchronized (monitor) {
            served++;
            totalTimeSumSec += totalSec;
            washTimeSumSec  += washSec;
            dryTimeSumSec   += drySec;
            payTimeSumSec   += paySec;
            waitTimeSumSec  += waitSec;
            longestStaySec = Math.max(longestStaySec, totalSec);
            perCustomer.add(String.format("C%02d total=%6.2fs (wash %5.2fs, dry %5.2fs, pay %4.2fs, waiting %6.2fs)",
                    id, totalSec, washSec, drySec, paySec, waitSec));
        }
    }

    public void washerFailed()      { synchronized (monitor) { washerFailures++; } }
    public void washerRetried()     { synchronized (monitor) { washerRetries++; } }
    public void kioskFailed()       { synchronized (monitor) { kioskFailures++; } }
    public void kioskRetried()      { synchronized (monitor) { kioskRetries++; } }
    public void ownerCalledIn()     { synchronized (monitor) { ownerCalls++; } }

    public int servedCount() {
        synchronized (monitor) { return served; }
    }

    public String report(SimulationConfig cfg, MachinePool washers, MachinePool dryers,
                         MachinePool kiosks, double elapsedSimSec) {
        synchronized (monitor) {
            double avgTotal = served == 0 ? 0 : totalTimeSumSec / served;
            double avgWash  = served == 0 ? 0 : washTimeSumSec  / served;
            double avgDry   = served == 0 ? 0 : dryTimeSumSec   / served;
            double avgPay   = served == 0 ? 0 : payTimeSumSec   / served;
            double avgWait  = served == 0 ? 0 : waitTimeSumSec  / served;
            StringBuilder sb = new StringBuilder();
            sb.append('\n');
            sb.append("================== SIMULATION STATISTICS ==================\n");
            sb.append(String.format("Scenario                     : %s%n",
                    cfg.congestedScenario ? "BONUS - congested (kiosks dead for the day)" : "Normal operation"));
            sb.append(String.format("Simulated duration           : %.2f s (%.1f min)%n",
                    elapsedSimSec, elapsedSimSec / 60.0));
            sb.append(String.format("Customers arrived            : %d%n", arrived));
            sb.append(String.format("TOTAL CUSTOMERS SERVED       : %d%n", served));
            sb.append(String.format("AVERAGE TOTAL TIME / CUSTOMER: %.2f s%n", avgTotal));
            sb.append(String.format("   - average washing time     : %.2f s%n", avgWash));
            sb.append(String.format("   - average drying time      : %.2f s%n", avgDry));
            sb.append(String.format("   - average payment time     : %.2f s%n", avgPay));
            sb.append(String.format("   - average queue waiting    : %.2f s%n", avgWait));
            sb.append(String.format("Longest single stay          : %.2f s%n", longestStaySec));
            sb.append(String.format("MAX CONCURRENT WASHERS IN USE: %d of %d%n",
                    washers.maxConcurrentInUse(), washers.size()));
            sb.append(String.format("MAX CONCURRENT DRYERS  IN USE: %d of %d%n",
                    dryers.maxConcurrentInUse(), dryers.size()));
            sb.append(String.format("Max concurrent kiosks in use : %d of %d%n",
                    kiosks.maxConcurrentInUse(), kiosks.size()));
            sb.append(String.format("Washer breakdowns (retries)  : %d (%d)%n", washerFailures, washerRetries));
            sb.append(String.format("Kiosk failures   (retries)   : %d (%d)%n", kioskFailures, kioskRetries));
            sb.append(String.format("Owner call-outs              : %d%n", ownerCalls));
            sb.append(String.format("Peak queues: washers=%d, dryers=%d, payment=%d%n",
                    washers.peakQueueLength(), dryers.peakQueueLength(), kiosks.peakQueueLength()));
            sb.append("---------------------------------------------------------------\n");
            sb.append("Per-machine cycles: ");
            for (Machine m : washers.machines()) sb.append(m.label()).append('=').append(m.cyclesDone()).append(' ');
            sb.append("| ");
            for (Machine m : dryers.machines()) sb.append(m.label()).append('=').append(m.cyclesDone()).append(' ');
            sb.append("| ");
            for (Machine m : kiosks.machines()) sb.append(m.label()).append('=').append(m.cyclesDone()).append(' ');
            sb.append('\n');
            sb.append("---------------------------------------------------------------\n");
            List<String> copy = new ArrayList<>(perCustomer);
            Collections.sort(copy);
            for (String line : copy) sb.append(line).append('\n');
            sb.append("===============================================================\n");
            return sb.toString();
        }
    }
}
