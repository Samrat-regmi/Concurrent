package laundry;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Central event bus / logger.
 *
 * <p>Threads never write to {@code System.out} directly (that would interleave
 * half-lines). Instead they publish a {@link SimulationEvent}; one consumer
 * (the console printer) and optionally the GUI pick them up. The queue is
 * drained by a dedicated daemon thread, so publishing never blocks a customer
 * thread.</p>
 */
public final class EventLog {

    private final ConcurrentLinkedQueue<SimulationEvent> queue = new ConcurrentLinkedQueue<>();
    private volatile Consumer<SimulationEvent> listener;
    private volatile boolean running = true;
    private int published;

    public void setListener(Consumer<SimulationEvent> listener) {
        this.listener = listener;
    }

    /** Non-blocking: customers only hand the event over and continue. */
    public void publish(SimulationEvent e) {
        queue.add(e);
        synchronized (this) {
            published++;
        }
    }

    public void log(SimulationEvent.Type type, int customerId, String msg) {
        // Simulated time is stamped here so that every event carries a consistent timestamp.
        publish(new SimulationEvent(Double.NaN, type, customerId, msg));
    }

    public int publishedCount() {
        synchronized (this) {
            return published;
        }
    }

    /** Starts the background drain loop; returns immediately. */
    public Thread start(SimClock clock) {
        Thread t = new Thread(() -> {
            while (running || !queue.isEmpty()) {
                SimulationEvent e = queue.poll();
                if (e == null) {
                    try {
                        Thread.sleep(15);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                SimulationEvent stamped = new SimulationEvent(clock.simSeconds(),
                        e.type, e.customerId, e.message);
                Consumer<SimulationEvent> l = listener;
                if (l != null) {
                    l.accept(stamped);
                }
            }
        }, "event-log");
        t.setDaemon(true);
        running = true;
        t.start();
        return t;
    }

    public void shutdown() {
        running = false;
    }
}
