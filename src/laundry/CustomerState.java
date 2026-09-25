package laundry;

/**
 * Lifecycle states a customer thread can be in. The GUI renders one dot per
 * active customer using this state, which makes the concurrency (many customers
 * in different stages at the same time) directly visible.
 */
public enum CustomerState {
    ARRIVING,
    WAITING_WASHER,
    WASHING,
    WASH_DONE,
    WAITING_DRYER,
    DRYING,
    DRY_DONE,
    WAITING_PAYMENT,
    PAYING,
    LEAVING,
    DONE;

    /** Human readable short label used in the GUI legend/table. */
    public String shortLabel() {
        return switch (this) {
            case ARRIVING        -> "enter";
            case WAITING_WASHER  -> "q-wash";
            case WASHING         -> "WASH";
            case WASH_DONE       -> "wash ok";
            case WAITING_DRYER   -> "q-dry";
            case DRYING          -> "DRY";
            case DRY_DONE        -> "dry ok";
            case WAITING_PAYMENT -> "Q-PAY";
            case PAYING          -> "PAY";
            case LEAVING         -> "leaving";
            case DONE            -> "done";
        };
    }
}
