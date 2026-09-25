package laundry;

/**
 * Immutable log record produced by every state change of the simulation.
 * Events are consumed twice: once by the console logger and once by the GUI
 * (which renders them as a scrolling activity feed). Keeping them as data
 * instead of printing directly is what allows the same run to drive both views.
 */
public final class SimulationEvent {

    /** Severity/kind of an event - used for colouring in the GUI. */
    public enum Type {
        ARRIVAL, WASH_START, WASH_DONE, WASH_FAIL, DRY_START, DRY_DONE,
        PAY_START, PAY_DONE, PAY_FAIL, QUEUE, REPAIR, OWNER, INFO, ERROR
    }

    public final double simTimeSec;
    public final Type type;
    public final int customerId;
    public final String message;

    public SimulationEvent(double simTimeSec, Type type, int customerId, String message) {
        this.simTimeSec = simTimeSec;
        this.type = type;
        this.customerId = customerId;
        this.message = message;
    }

    @Override
    public String toString() {
        return String.format("[t=%7.2fs] C%02d %-10s %s", simTimeSec, customerId, type, message);
    }
}
