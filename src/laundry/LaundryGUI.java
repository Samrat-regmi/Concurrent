package laundry;

import javax.swing.*;
import java.awt.*;
import java.io.Serial;
import java.util.List;

/**
 * BONUS requirement: a Swing GUI that visualises what is happening in the
 * laundromat while the simulation runs.
 *
 * <p>The GUI is completely decoupled from the simulation: a {@link Timer} on the
 * Swing event dispatch thread polls an immutable {@link Laundromat.Snapshot} every
 * {@code guiRefreshMs} milliseconds and repaints. No simulation thread ever touches
 * a Swing component, so there is no risk of a deadlock/UI freeze.</p>
 *
 * <p>What you can see:</p>
 * <ul>
 *   <li>6 washers, 4 dryers and 2 kiosks as boxes that turn blue (busy), grey
 *       (idle) or red (broken); busy devices show a progress bar of the running cycle</li>
 *   <li>the three queues (washer / dryer / payment) as rows of customer dots -
 *       in the congested scenario the payment row explodes to 30+ dots</li>
 *   <li>a live status bar with served customers, average time and max concurrency</li>
 *   <li>a scrolling activity log of every state change</li>
 * </ul>
 */
public final class LaundryGUI {

    private final Laundromat shop;
    private JFrame frame;
    private ShopPanel panel;

    public LaundryGUI(Laundromat shop) {
        this.shop = shop;
    }

    /** Builds and shows the window; must be called on the EDT. */
    public void showOnEdt() {
        frame = new JFrame("Smart Laundry Facility - live simulation"
                + (shop.config().congestedScenario ? "  [BONUS: CONGESTED DAY]" : ""));
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        panel = new ShopPanel();
        frame.add(panel);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        Timer timer = new Timer(shop.config().guiRefreshMs, e -> {
            Laundromat.Snapshot snap = shop.snapshot();
            panel.setSnapshot(snap);
            if (snap.finished()) {
                ((Timer) e.getSource()).setDelay(1500);   // slow down once everything is done
            }
        });
        timer.setRepeats(true);
        timer.start();
    }

    public void dispose() {
        if (frame != null) frame.dispose();
    }

    // ------------------------------------------------------------------ painter
    private static final Color IDLE   = new Color(235, 238, 245);
    private static final Color BUSY   = new Color(41, 121, 255);
    private static final Color BROKEN = new Color(211, 47, 47);
    private static final Color WASHQ  = new Color(255, 152, 0);
    private static final Color DRYQ   = new Color(142, 36, 170);
    private static final Color PAYQ   = new Color(0, 166, 90);

    private final class ShopPanel extends JPanel {
        @Serial
        private static final long serialVersionUID = 1L;

        private Laundromat.Snapshot snap;

        ShopPanel() {
            setPreferredSize(new Dimension(1180, 760));
            setBackground(new Color(24, 26, 32));
        }

        void setSnapshot(Laundromat.Snapshot s) {
            this.snap = s;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Laundromat.Snapshot s = snap;
            if (s == null) { g.dispose(); return; }

            int w = getWidth();
            drawHeader(g, s, w);
            int y = 96;
            y = drawDeviceRow(g, "WASHING MACHINES", s.washers(), s.queueWashers(), y, WASHQ, s);
            y += 14;
            y = drawDeviceRow(g, "TUMBLE DRYERS", s.dryers(), s.queueDryers(), y, DRYQ, s);
            y += 14;
            y = drawDeviceRow(g, "PAYMENT KIOSKS", s.kiosks(), s.queuePayment(), y, PAYQ, s);
            y += 22;
            drawQueueStrip(g, s, y, w);
            y += 150;
            drawActivityLog(g, s, y, w, getHeight() - y - 10);
            g.dispose();
        }

        private void drawHeader(Graphics2D g, Laundromat.Snapshot s, int w) {
            g.setColor(new Color(33, 37, 45));
            g.fillRoundRect(12, 12, w - 24, 68, 14, 14);
            g.setColor(Color.WHITE);
            g.setFont(getFont().deriveFont(Font.BOLD, 20f));
            g.drawString("SMART LAUNDRY FACILITY", 28, 42);
            g.setFont(getFont().deriveFont(Font.PLAIN, 13f));
            g.setColor(new Color(170, 180, 200));
            String mode = s.congested() ? "MODE: BONUS congested day (both kiosks dead for the day)"
                                        : "MODE: normal operation";
            g.drawString(mode, 28, 64);

            g.setColor(new Color(210, 220, 235));
            g.setFont(getFont().deriveFont(Font.BOLD, 14f));
            String statsLine = String.format(
                    "sim t = %.1fs   |   arrived %d/%d   |   served %d   |   in shop %d   |   peak washers %d   |   peak dryers %d",
                    s.simTimeSec(), s.arrived(), s.expected(), s.served(), s.inShop(),
                    s.maxWashersInUse(), s.maxDryersInUse());
            int sw = g.getFontMetrics().stringWidth(statsLine);
            g.drawString(statsLine, w - sw - 30, 42);

            if (s.ownerCalled()) {
                g.setColor(BROKEN);
                g.setFont(getFont().deriveFont(Font.BOLD, 14f));
                String alert = s.ownerWorking()
                        ? "!! OWNER CALLED IN - repairing both kiosks !!"
                        : "!! Payment kiosks repaired - queue draining !!";
                int aw = g.getFontMetrics().stringWidth(alert);
                g.drawString(alert, w - aw - 30, 66);
            } else if (s.finished()) {
                g.setColor(PAYQ);
                g.setFont(getFont().deriveFont(Font.BOLD, 14f));
                String done = "SIMULATION FINISHED - all customers served";
                int dw = g.getFontMetrics().stringWidth(done);
                g.drawString(done, w - dw - 30, 66);
            }
        }

        private int drawDeviceRow(Graphics2D g, String title, List<Laundromat.MachineView> devices,
                                  int queued, int y, Color accent, Laundromat.Snapshot s) {
            int w = getWidth();
            g.setColor(new Color(33, 37, 45));
            int rowH = 118;
            g.fillRoundRect(12, y, w - 24, rowH, 12, 12);
            g.setColor(accent);
            g.fillRoundRect(12, y, 6, rowH, 12, 12);

            g.setColor(Color.WHITE);
            g.setFont(getFont().deriveFont(Font.BOLD, 15f));
            g.drawString(title, 30, y + 26);
            g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
            g.setColor(new Color(160, 170, 190));
            g.drawString(String.format("waiting in queue: %d", queued), 30, y + 46);

            int n = Math.max(devices.size(), 1);
            int availW = w - 60;
            int bw = Math.min(190, (availW - (n - 1) * 10) / n);
            int bh = 62;
            int x = 30;
            int by = y + 54;
            for (Laundromat.MachineView mv : devices) {
                Color fill = mv.broken() ? BROKEN : (mv.busy() ? BUSY : IDLE);
                g.setColor(fill);
                g.fillRoundRect(x, by, bw, bh, 10, 10);
                g.setColor(mv.broken() || mv.busy() ? Color.WHITE : new Color(40, 44, 52));
                g.setFont(getFont().deriveFont(Font.BOLD, 12f));
                g.drawString(mv.label(), x + 8, by + 18);
                g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
                g.drawString(mv.status(), x + 8, by + 34);

                if (mv.busy()) {
                    drawProgress(g, x + 8, by + 42, bw - 16, mv);
                } else if (mv.broken()) {
                    g.setColor(Color.WHITE);
                    g.fillRect(x + 8, by + 44, bw - 16, 6);
                    g.setFont(getFont().deriveFont(Font.BOLD, 11f));
                    g.drawString("OUT OF ORDER", x + 8, by + 58);
                }
                x += bw + 10;
            }
            return y + rowH;
        }

        /** Progress of the running wash/dry/pay cycle (both values come from the snapshot). */
        private void drawProgress(Graphics2D g, int px, int py, int pw, Laundromat.MachineView mv) {
            double frac;
            if (Double.isNaN(mv.expectedSec()) || mv.expectedSec() <= 0) {
                frac = 0.5;                                 // payment retried on a broken kiosk
            } else {
                frac = Math.max(0.0, Math.min(1.0, mv.usedSec() / mv.expectedSec()));
            }
            g.setColor(new Color(20, 24, 30));
            g.fillRoundRect(px, py, pw, 10, 6, 6);
            g.setColor(new Color(255, 214, 90));
            g.fillRoundRect(px, py, (int) (pw * frac), 10, 6, 6);
            g.setColor(mv.busy() ? Color.WHITE : new Color(40, 44, 52));
            g.setFont(getFont().deriveFont(Font.PLAIN, 10f));
            String t = Double.isNaN(mv.expectedSec()) ? "?" : String.format("%.1f/%.1fs", mv.usedSec(), mv.expectedSec());
            g.drawString(t, px + Math.max(0, pw - g.getFontMetrics().stringWidth(t)) , py - 3);
        }

        /** Rows of dots: one dot per waiting customer, so congestion is obvious at a glance. */
        private void drawQueueStrip(Graphics2D g, Laundromat.Snapshot s, int y, int w) {
            g.setColor(new Color(33, 37, 45));
            g.fillRoundRect(12, y, w - 24, 132, 12, 12);
            g.setColor(Color.WHITE);
            g.setFont(getFont().deriveFont(Font.BOLD, 15f));
            g.drawString("CUSTOMER FLOW / QUEUES (each dot = one customer thread)", 30, y + 26);

            drawDots(g, "Washer queue", s.queueWashers(), WASHQ, 30, y + 44);
            drawDots(g, "Dryer queue",  s.queueDryers(),  DRYQ,  w / 3 + 20, y + 44);
            drawDots(g, "Payment queue", s.queuePayment(), PAYQ, 2 * w / 3 + 10, y + 44);

            g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
            g.setColor(new Color(170, 180, 200));
            int px = 30;
            int py = y + 112;
            for (Laundromat.ActiveCustomer ac : s.active()) {
                String tag = "C" + String.format("%02d", ac.id()) + ":" + ac.state().shortLabel();
                int tw = g.getFontMetrics().stringWidth(tag) + 10;
                if (px + tw > w - 30) { px = 30; py += 16; if (py > y + 124) break; }
                g.setColor(stateColor(ac.state()));
                g.drawString(tag, px, py);
                px += tw;
            }
        }

        private void drawDots(Graphics2D g, String title, int count, Color color, int x, int y) {
            g.setColor(color);
            g.setFont(getFont().deriveFont(Font.BOLD, 13f));
            g.drawString(String.format("%s: %d", title, count), x, y);
            int shown = Math.min(count, 40);
            for (int i = 0; i < shown; i++) {
                int col = i % 20;
                int row = i / 20;
                g.setColor(color);
                g.fillOval(x + col * 14, y + 8 + row * 16, 11, 11);
            }
            if (count > shown) {
                g.setColor(Color.WHITE);
                g.setFont(getFont().deriveFont(Font.BOLD, 12f));
                g.drawString("+" + (count - shown) + " more", x + 20 * 14 + 6, y + 20);
            }
        }

        private Color stateColor(CustomerState st) {
            return switch (st) {
                case WASHING, WASH_DONE -> new Color(90, 170, 255);
                case DRYING, DRY_DONE   -> new Color(200, 140, 255);
                case PAYING, LEAVING    -> new Color(110, 230, 160);
                case WAITING_PAYMENT    -> new Color(255, 90, 90);
                case WAITING_WASHER     -> new Color(255, 190, 90);
                case WAITING_DRYER      -> new Color(230, 200, 120);
                default                 -> new Color(150, 160, 180);
            };
        }

        private void drawActivityLog(Graphics2D g, Laundromat.Snapshot s, int y, int w, int h) {
            g.setColor(new Color(28, 31, 38));
            g.fillRoundRect(12, y, w - 24, h, 12, 12);
            g.setColor(Color.WHITE);
            g.setFont(getFont().deriveFont(Font.BOLD, 14f));
            g.drawString("ACTIVITY LOG (most recent at bottom)", 30, y + 22);

            Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);
            g.setFont(mono);
            int lineH = 15;
            int lines = Math.max(1, (h - 40) / lineH);
            List<SimulationEvent> ev = s.recentEvents();
            int from = Math.max(0, ev.size() - lines);
            int ly = y + 40;
            for (int i = from; i < ev.size(); i++) {
                SimulationEvent e = ev.get(i);
                g.setColor(colorFor(e.type));
                String text = e.toString();
                if (text.length() > 150) text = text.substring(0, 147) + "...";
                g.drawString(text, 30, ly);
                ly += lineH;
            }
        }

        private Color colorFor(SimulationEvent.Type t) {
            return switch (t) {
                case ARRIVAL -> new Color(150, 200, 255);
                case WASH_START, WASH_DONE -> new Color(90, 170, 255);
                case DRY_START, DRY_DONE -> new Color(200, 140, 255);
                case PAY_START, PAY_DONE -> new Color(110, 230, 160);
                case WASH_FAIL, PAY_FAIL, ERROR -> new Color(255, 100, 100);
                case QUEUE -> new Color(255, 200, 100);
                case REPAIR -> new Color(120, 220, 220);
                case OWNER -> new Color(255, 80, 80);
                case INFO -> Color.WHITE;
            };
        }
    }
}
