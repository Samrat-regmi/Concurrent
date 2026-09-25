package laundry;

/**
 * A single, monotonically increasing simulated clock shared by every component.
 *
 * <p>Why is this needed? The wall clock and the simulated clock only coincide in
 * the normal scenario ({@code msPerSecond == 1000}). In the congested bonus
 * scenario the simulation runs on an accelerated clock (3 simulated seconds per
 * real second), so all durations (washing, drying, payment, retry delays) are
 * expressed in <em>simulated</em> seconds while the thread scheduling still
 * happens in real milliseconds.</p>
 *
 * <p>The clock is derived from {@link System#nanoTime()} of the run start, hence
 * it needs no locking: reads are atomic enough for logging/statistics purposes.</p>
 */
public final class SimClock {

    private final long startNanos;
    private final double msPerSecond;

    public SimClock(double msPerSecond) {
        this.startNanos = System.nanoTime();
        this.msPerSecond = msPerSecond;
    }

    /** Real milliseconds elapsed since the run started. */
    public long realMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** Elapsed SIMULATED seconds. */
    public double simSeconds() {
        return realMillis() / msPerSecond;
    }

    /** Convert a duration given in simulated seconds into real milliseconds to sleep. */
    public long toRealMillis(double simSeconds) {
        return Math.round(simSeconds * msPerSecond);
    }

    /** Sleep for the given amount of simulated seconds (interruptible). */
    public void sleepSim(double simSeconds) throws InterruptedException {
        long ms = toRealMillis(simSeconds);
        if (ms <= 0) {
            Thread.sleep(1);
        } else {
            Thread.sleep(ms);
        }
    }

    public static String fmt(double seconds) {
        return String.format("%6.2fs", seconds);
    }
}
