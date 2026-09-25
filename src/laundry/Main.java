package laundry;

import javax.swing.Timer;

/**
 * Entry point of the Smart Laundry Facility simulation.
 *
 * <pre>
 * Usage:  java laundry.Main [normal|congested] [--headless] [--fast] [--seed=&lt;n&gt;]
 *
 *   normal     basic requirements (default) - 6 washers, 4 dryers, 2 kiosks, 50 customers
 *   congested  BONUS scenario - both payment kiosks are dead for the day, the owner is
 *              called in once 30 customers pile up at the payment queue
 *   --headless console only (no Swing window); auto-selected when no display exists
 *   --fast     accelerated clock (0.5 s per simulated second) - useful for demos/tests
 * </pre>
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        boolean congested = false;
        boolean headlessFlag = false;
        boolean fast = false;
        long seed = System.currentTimeMillis() % 1_000_000L;

        for (String a : args) {
            switch (a) {
                case "congested" -> congested = true;
                case "normal"    -> congested = false;
                case "--headless", "-headless" -> headlessFlag = true;
                case "--fast"    -> fast = true;
                default -> {
                    if (a.startsWith("--seed=")) seed = Long.parseLong(a.substring(7));
                    else System.out.println("Ignoring unknown option: " + a);
                }
            }
        }

        SimulationConfig cfg = congested ? SimulationConfig.congested(seed) : SimulationConfig.normal();
        cfg.seed = seed;
        if (fast) cfg.msPerSecond = Math.min(cfg.msPerSecond, 500);

        boolean gui = !headlessFlag && !java.awt.GraphicsEnvironment.isHeadless();

        System.out.println("SMART LAUNDRY FACILITY SIMULATION");
        System.out.printf("scenario=%s | %d washers, %d dryers, %d kiosks, %d customers | seed=%d | %s%n",
                congested ? "CONGESTED (bonus)" : "normal", cfg.washers, cfg.dryers, cfg.kiosks,
                cfg.customers, cfg.seed, gui ? "GUI enabled" : "console only");
        System.out.println("simulated time scale: " + cfg.msPerSecond + " ms per simulated second");
        System.out.println("---------------------------------------------------------------------");

        Laundromat shop = new Laundromat(cfg);
        LaundryGUI swingGui = null;
        if (gui) {
            final LaundryGUI[] holder = new LaundryGUI[1];
            java.awt.EventQueue.invokeAndWait(() -> {
                LaundryGUI g = new LaundryGUI(shop);
                g.showOnEdt();
                holder[0] = g;
            });
            swingGui = holder[0];
        }

        long wallStart = System.currentTimeMillis();
        shop.run();                                   // blocks until all 50 customers have paid & left
        double wallSec = (System.currentTimeMillis() - wallStart) / 1000.0;

        System.out.println(shop.statistics().report(cfg, shop.washers(), shop.dryers(), shop.kiosks(),
                shop.elapsedSimSeconds()));
        System.out.printf("REAL WALL-CLOCK DURATION OF THE RUN: %.1f seconds%n", wallSec);
        System.out.printf("SIMULATED DURATION               : %.1f seconds%n", shop.elapsedSimSeconds());

        if (swingGui != null) {
            final LaundryGUI g = swingGui;
            java.awt.EventQueue.invokeLater(() -> {
                Timer t = new Timer((int) cfg.guiCloseDelayMs, e -> g.dispose());
                t.setRepeats(false);
                t.start();
            });
            // keep the JVM alive so the finished state can be admired for a moment
            Thread.sleep(cfg.guiCloseDelayMs + 500);
        }
        System.exit(0);
    }

}
