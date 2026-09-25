# Smart Laundry Facility — Concurrent Java Simulation

A multi-threaded **Java 17 / Swing** simulation of a self-service laundromat.
Every customer is modelled as **its own thread** that walks through the full lifecycle
*arrive → wash → dry → pay → leave* while genuinely competing for shared devices
(washing machines, tumble dryers, payment kiosks). The run is logged to the console and,
optionally, visualised live in a Swing GUI. A bonus **"congested day"** scenario exercises
a larger failure/recovery protocol: both payment kiosks are dead for the whole day until
the shop **owner** is called in once 30 customers pile up at the payment queue.

```
Project layout
└── src/laundry/
    ├── Main.java             – entry point, CLI parsing, scenario selection, shutdown
    ├── Laundromat.java       – simulation engine (thread pool, arrivals, owner hook, snapshots)
    ├── Customer.java         – one customer = one thread (full lifecycle + error handling)
    ├── Machine.java          – one device, mutual exclusion via a fair Semaphore
    ├── MachinePool.java      – pool of identical devices + queue bookkeeping & thresholds
    ├── SimClock.java         – simulated clock (wall-time ↔ sim-time conversion, sleeping)
    ├── SimulationConfig.java – all tunable parameters + normal()/congested() factories
    ├── EventLog.java         – asynchronous event bus / logger (never blocks customers)
    ├── SimulationEvent.java  – immutable log record (timestamp, type, customer, message)
    ├── CustomerState.java    – enum of the 11 lifecycle states rendered by the GUI
    ├── Statistics.java       – thread-safe metrics collector + final report generator
    └── LaundryGUI.java       – BONUS: live Swing visualisation (decoupled via snapshots)
```

---

## 1. Quick start

Requires **JDK 17+** (uses `switch` expressions, records, text patterns).

```bash
# compile
javac -d out src/laundry/*.java

# run the normal scenario (GUI if a display exists)
java -cp out laundry.Main

# console-only, accelerated clock, reproducible seed
java -cp out laundry.Main normal --headless --fast --seed=7

# BONUS congested-day scenario
java -cp out laundry.Main congested --headless --seed=7
```

### Command-line arguments (`Main`)

| Argument | Effect |
|---|---|
| `normal` | Basic requirements scenario (default): 6 washers, 4 dryers, 2 kiosks, 50 customers |
| `congested` | Bonus scenario: both kiosks broken overnight, owner called after 30 in the payment queue, ~3× accelerated clock |
| `--headless` / `-headless` | Console only; auto-selected when `GraphicsEnvironment.isHeadless()` |
| `--fast` | Clamp `msPerSecond` to ≤ 500 ms (demo/test speed-up) |
| `--seed=<n>` | RNG seed → fully reproducible runs |

Example output header:

```
SMART LAUNDRY FACILITY SIMULATION
scenario=normal | 6 washers, 4 dryers, 2 kiosks, 50 customers | seed=7 | console only
simulated time scale: 500 ms per simulated second
---------------------------------------------------------------------
[t=   0.05s] C01 ARRIVAL    Customer C01 enters the laundromat with a full basket
[t=   0.05s] C01 WASH_START Washer-1 started (attempt 1)
[t=   4.28s] C01 WASH_DONE  Washer-1 finished after 4.23s -> looking for a dryer
...
```

---

## 2. Complete end-to-end flow

```
Main.main()
 │  parse args → build SimulationConfig (normal | congested)
 │  new Laundromat(cfg)                    – creates SimClock, EventLog, 3 MachinePools
 │  (optional) LaundryGUI.showOnEdt()      – Swing window + refresh Timer on the EDT
 │  shop.run()                             – BLOCKS until every customer has paid & left
 │      ├─ EventLog.start(clock)           – daemon drain thread prints/GUI-feeds events
 │      ├─ congested? kioskPool.breakAll() + setQueueThreshold(30, this::callOwner)
 │      ├─ ExecutorService(50 threads)     – main thread = "front door":
 │      │     loop i = 1..50: sleep random 0–3 sim-seconds, submit Customer(i)
 │      ├─ each Customer thread:
 │      │     arrive() → doLaundry() → doDry() → doPay() → finish()
 │      │     (blocking on MachinePool.acquireAny / release / fail)
 │      ├─ pool.awaitTermination(1 s loop) + maxRunMillis safety timeout
 │      └─ callOwner() (bonus): daemon "shop-owner" thread sleeps repair time,
 │                            then kioskPool.repairAll() → queue drains
 │  statistics().report(...)               – final console report
 │  GUI stays open guiCloseDelayMs, then dispose + System.exit(0)
```

### Customer state machine (one thread, strictly sequential stages)

```
ARRIVING
   │
WAITING_WASHER ──(all washers busy/broken ⇒ poll-wait in pool queue)──┐
   │ acquire a free washer                                            │
WASHING  ──5% chance mid-cycle──► WASH_FAIL: machine breaks, permit held,
   │                                technician repairs (daemon thread),
   ◄────────────────────────────── customer re-joins washer queue
   │ cycle completes
WASH_DONE → WAITING_DRYER → DRYING → DRY_DONE
   │
WAITING_PAYMENT ──(kiosk rejects 5%, or permanently broken in bonus)──┐
   │ PAY_START … PAY_FAIL: release kiosk, wait kioskRetryDelaySec ────┘
   │ payment accepted
PAY_DONE → LEAVING → DONE  (customerFinished() removes it from the active map)
```

### Concurrency model (how the requirements are met)

| Requirement | Mechanism |
|---|---|
| Many customers at once | One OS thread per customer in a fixed 50-thread `ExecutorService`; several can be washing/drying/paying simultaneously |
| Mutual exclusion per machine | Each `Machine` owns a **fair 1-permit `Semaphore`**; `tryAcquire`/`acquire` before use, always `release()` afterwards |
| Waiting when all machines busy | `MachinePool.acquireAny()` polls all devices with a short back-off and maintains an explicit *waiter counter* (the logical queue) |
| Random arrival/service times | Seeded `java.util.Random`, uniform distributions from `SimulationConfig` |
| Washing-machine breakdown | Mid-cycle failure ⇒ `failAndRelease()` keeps the permit so the device leaves the pool; a daemon *technician* thread calls `repair()`; customer retries on another washer |
| Payment failure + retry after N seconds | Kiosk released, `sleepSim(kioskRetryDelaySec)`, loop re-acquires any kiosk |
| Statistics | `Statistics` (per-customer timings under a monitor) + `MachinePool.maxConcurrentInUse` / `peakQueueLength` (CAS-tracked) |
| Bonus: congestion & owner | `kioskFailProbability = 1.0` + `breakAll()` at opening; `setQueueThreshold(30, callOwner)`; owner daemon thread repairs both kiosks |
| Bonus: GUI | EDT `Timer` polls an immutable `Laundromat.Snapshot`; no simulation thread ever touches Swing components |
| Deadlock avoidance | Broken device holds its permit but never waits on anything; customers hold at most **one** device at a time and always release before acquiring the next stage's device; `maxRunMillis` watchdog + `shutdownNow()` as last resort |

### Simulated vs. real time

`SimClock` derives simulated seconds from `System.nanoTime()` scaled by
`cfg.msPerSecond`:

* normal scenario: `1000 ms/sim-sec` → simulated time == wall time (~75–90 s run),
* `--fast`: ≤ `500 ms/sim-sec`,
* congested bonus: `333 ms/sim-sec` (~3×) to compress the congestion drama (see §4 for the
  measured run length — the code comment claims a 1–2 minute window, in practice the
  watchdog caps it at 5 real minutes).

All durations (wash, dry, pay, retry delays, repairs) are expressed in **simulated seconds**
and converted to real milliseconds only when sleeping.

---

## 3. Class & function reference (inputs → outputs)

### 3.1 `Main` — program entry point

| Member | Input | Output / effect |
|---|---|---|
| `main(String[] args)` | CLI flags (`normal`/`congested`, `--headless`, `--fast`, `--seed=n`) | Builds config, creates `Laundromat` (+ optional GUI), runs the simulation to completion, prints the statistics report and wall/sim durations, closes the GUI after `guiCloseDelayMs`, exits with status 0 |

Flow detail: parses args → `SimulationConfig.normal()` or `.congested(seed)` → applies seed
and `--fast` clamp → decides GUI vs. console → constructs `Laundromat` → shows GUI on the
EDT via `invokeAndWait` → `shop.run()` (blocking) → prints `statistics().report(...)` →
one-shot Swing `Timer` disposes the frame → `Thread.sleep(delay+500)` keeps the JVM alive
so the finished state can be admired → `System.exit(0)`.

### 3.2 `Laundromat` — simulation engine

Records (immutable views handed to the GUI):

| Record | Fields | Purpose |
|---|---|---|
| `MachineView` | label, status, busy, broken, usedSec, expectedSec | One device as needed to paint a box + progress bar |
| `ActiveCustomer` | id, state, sinceSimSec | One customer currently inside the shop |
| `Snapshot` | simTimeSec, arrived, served, expected, inShop, washer/dryer/kiosk views, 3 queue lengths, peak washer/dryer usage, congested/ownerCalled/ownerWorking/finished flags, active list, recent events | Everything one GUI frame needs |

Methods:

| Method | Input | Output / effect |
|---|---|---|
| `Laundromat(SimulationConfig cfg)` | config | Creates `SimClock`, `EventLog`, seeded `Random`, and the three `MachinePool`s (washers/dryers/kiosks) |
| `run()` | — | Starts the event-log drain thread; logs the OPEN banner; in the bonus scenario breaks all kiosks and arms the 30-customer owner threshold; spawns the fixed thread pool and submits all 50 `Customer` tasks separated by random 0–3 sim-second gaps (child seeds drawn under `synchronized(rnd)`); waits for termination with a `maxRunMillis` watchdog (`shutdownNow()` on expiry); sets `finished`, logs the CLOSE banner, sleeps 500 ms so trailing events flush, shuts the log down. Blocks until the whole shop is done |
| `customerFinished(Customer c)` | the leaving customer | Removes the customer from the `active` map (called from the customer's `finally` block, so even interrupts clean up) |
| `callOwner()` *(private)* | — | BONUS hook fired once when the payment queue reaches the threshold: raises `ownerCalled`, counts the stat, logs the CONGESTION ALERT, starts a daemon `"shop-owner"` thread that sleeps `ownerRepairTimeSec` then `kioskPool.repairAll()` and logs the REPAIR event |
| `registerActive(int id, CustomerState st, double sinceSimSec)` | customer id + new state + timestamp | Increments `arrivedCount` on `ARRIVING`; updates the GUI's active-customer map under its monitor |
| `onEvent(SimulationEvent e)` *(private)* | stamped event | Single consumer of the event bus: prints to `System.out` under a monitor (prevents torn lines from 50 threads) and appends to a bounded 250-event ring buffer feeding the GUI |
| `snapshot()` | — | Returns an immutable `Snapshot` (copies of active list, event buffer, per-device `MachineView`s, queue counters) — painting never holds a simulation lock |
| `views(MachinePool p)` *(private)* | a pool | Converts each `Machine` into a `MachineView` incl. elapsed busy time vs. expected cycle length |
| Getters `config() clock() eventLog() statistics() washers() dryers() kiosks() isFinished() elapsedSimSeconds()` | — | Accessors used by `Main`, GUI and statistics |

### 3.3 `Customer implements Runnable` — one customer = one thread

Constructor input: `id, cfg, clock, log, rnd, washers, dryers, kiosks, stats, laundromat`.
Fields also track `arrivalSimSec` and accumulated `waitingSec` (queue time in sim seconds).

| Method | Input | Output / effect |
|---|---|---|
| `run()` | — (thread entry) | Executes `arrive → doLaundry → doDry → doPay → finish`; on `InterruptedException` restores the interrupt flag and logs ERROR; `finally` always calls `laundromat.customerFinished(this)` |
| `arrive()` *(private)* | — | Stamps arrival sim-time, `stats.customerArrived()`, registers `ARRIVING` state, logs the ARRIVAL line |
| `doLaundry()` *(private)* | — | **Returns total washing seconds.** Retry loop: register `WAITING_WASHER` → `washers.acquireAny(id)` (queue time added to `waitingSec`) → register `WASHING` → draw uniform duration `[washMinSec,washMaxSec]` → roll the 5% breakdown up-front, revealing it at 40–80 % of the cycle → **fail path:** release-with-break (`washers.fail`), stats++, log WASH_FAIL, spawn technician repair thread, brief cooldown, `continue` (re-queue); **ok path:** sleep the remainder, `washers.release`, log WASH_DONE, return |
| `scheduleRepair(Machine m)` *(private)* | broken washer | Starts a daemon `"washer-repair-<n>"` thread: sleeps `washerRepairTimeSec` then `m.repair()` and logs REPAIR (interrupt-safe: repairs anyway) |
| `doDry()` *(private)* | — | **Returns drying seconds.** `WAITING_DRYER` → `dryers.acquireAny` → uniform `[dryMin,dryMax]` → sleep → `release` → DRY_DONE log. No failure mode for dryers |
| `doPay()` *(private)* | — | **Returns payment seconds.** Retry loop: `WAITING_PAYMENT` → `kiosk.acquireAny` → uniform `[payMin,payMax]` → sleep → fail if `k.isBroken()` (permanent bonus-scenario breakage) **or** 5% coin flip → **fail:** release kiosk, log PAY_FAIL, `sleepSim(kioskRetryDelaySec)`, count retry, `continue`; **ok:** release, log PAY_DONE with attempt count, return |
| `finish(double w,double d,double p)` *(private)* | per-stage seconds | Computes total stay = now − arrival, feeds `stats.customerServed(...)`, registers `DONE`, logs the COMPLETED summary line |
| `elapsedSim(long startNano)` / `uniform(min,max)` *(private helpers)* | nano timestamp / range | Convert real nanos to sim seconds; draw a uniform random value |
| `id()` / `arrivalSimSec()` | — | Accessors |

### 3.4 `Machine` — one physical device (washer / dryer / kiosk)

State: `occupied`, `broken`, `occupantId`, `busySinceSimSec`, `cycleEstimateSec`
(volatile), `cyclesDone`/`failures` (`AtomicInteger`), a **fair 1-permit `Semaphore`**
(FIFO service) and a `stateMonitor` object guarding bookkeeping shared with the GUI.

| Method | Input | Output / effect |
|---|---|---|
| `Machine(int id, Kind kind)` | ordinal + WASHER/DRYER/KIOSK | Assigns label `Washer-n` / `Dryer-n` / `Kiosk-n` |
| `acquire(int customerId, SimClock clock)` | who + clock | **Blocks** on the semaphore, then marks occupied and stamps `busySinceSimSec` |
| `tryAcquire(int customerId, SimClock clock)` | who + clock | Non-blocking variant returning `true/false` — used by the pool's polling loop |
| `release()` | — | Clears occupancy + cycle estimate, increments `cyclesDone`, returns the permit, notifies waiters |
| `failAndRelease()` | — | Device broke mid-cycle: clears occupancy, sets `broken`, counts the failure, notifies — **deliberately does NOT return the permit**, so the device leaves the usable pool and waiting customers migrate elsewhere |
| `repair()` | — | Technician/owner path: clears `broken`, notifies, and re-adds the missing permit if needed → device back in service |
| `awaitRepair(SimClock clock)` | — | Blocks (with periodic wake-ups) until the device is repaired again |
| `expectCycle(double simSeconds)` | planned duration | Stores the reference length for the GUI progress bar |
| Accessors `id() kind() label() isBroken() isBusy() occupant() cyclesDone() failures() busySinceSimSec() cycleEstimateSec()` | — | Read-only state for pools, GUI, statistics |
| `statusText()` | — | `"BROKEN"` / `"BUSY C07"` / `"IDLE"` string rendered by the GUI |

### 3.5 `MachinePool` — pool of identical devices + queue logic

Constructor input: `name, kind, count, clock, log` → creates `count` `Machine`s.

| Method | Input | Output / effect |
|---|---|---|
| `acquireAny(int customerId)` | customer id | **Blocks until ANY working device of the pool is free**, occupies it and returns the `Machine`. Round-robin scan offset spreads load; on first miss it increments the waiter counter, updates `peakQueueLength` (CAS), logs a QUEUE join event and checks the owner threshold; then backs off ≥20 ms and retries. When a previously-queued customer finally gets a device it decrements the counter and logs "queue cleared" |
| `release(Machine m, int customerId)` | device + user | Normal end-of-cycle: `m.release()` + concurrency tracking |
| `fail(Machine m, int customerId)` | broken device + user | Mid-cycle failure: `m.failAndRelease()` + concurrency tracking |
| `breakAll()` | — | Marks every *idle* device broken (bonus: kiosks dead from opening time) |
| `repairAll()` | — | Repairs every device (called by the shop-owner thread) |
| `setQueueThreshold(int n, Runnable cb)` | 30 + `Laundromat::callOwner` | Arms the congestion trip-wire; fires the callback **exactly once** (guarded by `thresholdFired` + monitor) |
| `trackConcurrency()` *(private)* | — | CAS-updates `maxConcurrentInUse` = high-water mark of simultaneously busy devices (feeds the required statistic) |
| `maybeCallOwner(int queueLen)` *(private)* | current queue length | Double-checked-locking trigger for the owner callback |
| Counters/getters `waitingCount() peakQueueLength() maxConcurrentInUse() busyCount() workingCount() size() machines() name() kind() totalFailures()` | — | Live queue depth, peaks, device lists for GUI/statistics |

### 3.6 `SimClock` — shared monotonic simulated clock

Built once from `System.nanoTime()` + `msPerSecond`; lock-free (derived from nanotime).

| Method | Input | Output |
|---|---|---|
| `realMillis()` | — | Real ms elapsed since run start |
| `simSeconds()` | — | Elapsed **simulated** seconds (`realMillis / msPerSecond`) |
| `toRealMillis(double simSeconds)` | duration in sim seconds | Equivalent real milliseconds (rounded) |
| `sleepSim(double simSeconds)` | duration | Interruptible `Thread.sleep` of the converted real time (min 1 ms) |
| `static fmt(double s)` | seconds | `"  6.20s"`-style formatted string |

### 3.7 `SimulationConfig` — every tunable parameter

Public fields (defaults = normal scenario): `washers=6, dryers=4, kiosks=2, customers=50`;
uniform ranges `arrival [0,3]s`, `wash [4,6]s`, `dry [3,5]s`, `pay [1,2]s`;
`washerFailProbability=0.05`, `kioskFailProbability=0.05`, `kioskRetryDelaySec=2`,
`washerRepairTimeSec=1`; bonus knobs `congestedScenario=false`, `ownerCallThreshold=30`,
`ownerRepairTimeSec=3`; timing/engine `seed`, `msPerSecond=1000`, `guiRefreshMs=120`,
`guiCloseDelayMs=8000`, `maxRunMillis=300000`.

| Factory / method | Input | Output |
|---|---|---|
| `normal()` | — | Default configuration (simulated time == wall time) |
| `congested(long seed)` | seed | Bonus preset: `kioskFailProbability=1.0` (every kiosk dead), 1 s fast retries, owner after 30 waiting, 3 s repair, `msPerSecond=333` (~3× clock) |
| `ms(double seconds)` | sim seconds | Integer real milliseconds for that duration |

### 3.8 `EventLog` — asynchronous event bus / logger

| Method | Input | Output / effect |
|---|---|---|
| `publish(SimulationEvent e)` | event | Non-blocking enqueue (`ConcurrentLinkedQueue`) + counter; customers never wait for printing |
| `log(Type t, int customerId, String msg)` | type/id/message | Convenience wrapper creating and publishing an event |
| `start(SimClock clock)` | clock | Spawns the daemon `"event-log"` drain thread: polls the queue (15 ms idle sleep), **stamps each event with the current sim time**, forwards it to the listener; drains remaining events on shutdown. Returns the thread |
| `setListener(Consumer<SimulationEvent> l)` | consumer | Hook used by `Laundromat.onEvent` (console print + GUI ring buffer) |
| `publishedCount()` / `shutdown()` | — | Diagnostics / stop flag |

### 3.9 `SimulationEvent` — immutable log record

Fields: `simTimeSec`, `type`, `customerId`, `message`.
`Type` enum: `ARRIVAL, WASH_START, WASH_DONE, WASH_FAIL, DRY_START, DRY_DONE, PAY_START,
PAY_DONE, PAY_FAIL, QUEUE, REPAIR, OWNER, INFO, ERROR` (drives GUI colouring).
`toString()` renders `[t=  10.42s] C01 PAY_DONE Kiosk-1: payment accepted ...`.

### 3.10 `CustomerState` — lifecycle enum

`ARRIVING, WAITING_WASHER, WASHING, WASH_DONE, WAITING_DRYER, DRYING, DRY_DONE,
WAITING_PAYMENT, PAYING, LEAVING, DONE` plus `shortLabel()` returning compact GUI tags
(`enter, q-wash, WASH, wash ok, q-dry, DRY, dry ok, Q-PAY, PAY, leaving, done`).

### 3.11 `Statistics` — thread-safe metrics collector

All mutations under a private monitor so 50 concurrent threads can contribute safely.

| Method | Input | Output / effect |
|---|---|---|
| `customerArrived()` | — | Arrival counter++ |
| `customerServed(id,total,wash,dry,pay,wait)` | per-customer sim-second timings | Adds sums, tracks `longestStaySec`, stores the per-customer line |
| `washerFailed/washerRetried/kioskFailed/kioskRetried/ownerCalledIn()` | — | Individual event counters |
| `servedCount()` | — | Customers completed so far (used by GUI + close banner) |
| `report(cfg, washers, dryers, kiosks, elapsedSimSec)` | config + pools + duration | **Returns the full statistics string**: scenario, simulated duration, arrived/served totals, average total & per-stage times, average queue waiting, longest stay, **max concurrent washers/dryers/kiosks in use**, breakdown/failure counts with retries, owner call-outs, peak queue lengths, per-machine cycle counts, sorted per-customer table |

### 3.12 `LaundryGUI` — BONUS live visualisation (Swing)

| Method | Input | Output / effect |
|---|---|---|
| `LaundryGUI(Laundromat shop)` | engine | Stores reference; does not touch Swing yet |
| `showOnEdt()` | — | Must run on the EDT: builds the `JFrame` (title flags the bonus mode), adds the `ShopPanel`, starts a repeating `javax.swing.Timer` every `guiRefreshMs` ms that pulls `shop.snapshot()` and repaints (slows to 1.5 s once finished) |
| `dispose()` | — | Closes the window |
| `ShopPanel.paintComponent` | Swing graphics | Renders header (mode, sim time, arrived/served/in-shop, peak usage, owner alert / finished banner), three device rows (washer/dryer/kiosk boxes: blue=busy + progress bar, grey=idle, red=BROKEN "OUT OF ORDER"), a queue strip (dot per waiting customer per stage + colored per-customer state tags), and the scrolling activity log (latest events, colour-coded by `SimulationEvent.Type`) |
| Internal painters `drawHeader / drawDeviceRow / drawProgress / drawQueueStrip / drawDots / drawActivityLog / stateColor / colorFor` | snapshot slices | Pure drawing helpers reading **only** the immutable snapshot — no simulation lock is ever held while painting |

---

## 4. Assumptions made

* One thread per customer (50 threads); the front-door arrival gap is measured from the
  previous customer's submission, so arrivals trickle in every 0–3 s (avg. 1.5 s).
* Every device serves ONE load at a time and is released as soon as its phase finishes —
  waiting for the next stage happens in the queue, not on the machine (keeps throughput high).
* Failure probabilities are evaluated **per attempt** (5 % washer breakdown mid-cycle,
  5 % kiosk card rejection); failures are logged but never abort a customer — everybody
  eventually gets served.
* Dryers never fail (not required).
* With `msPerSecond = 1000` simulated time equals wall time; a full normal run lasts about
  75–90 s (≈ 50 × 1.5 s of arrivals + service tail), matching the "about 1–2 minutes" goal
  (verified: seed 7 → 91.5 sim-s / 45.8 real-s with `--fast`).
* The congested bonus run uses a ~3× accelerated clock; the owner fixes the kiosks after
  roughly 60 sim-seconds, but because every customer keeps re-attempting payment on only
  two kiosks, the whole day drains over several hundred simulated seconds — in practice the
  run is bounded by the 5-minute `maxRunMillis` watchdog and reports **partial** statistics
  (observed: 300 s real / 902 s simulated before shutdown).
* All randomness is seeded (`--seed=n`) → identical, reproducible logs.
* Queueing is realised by blocking/polling on the device pools (no central scheduler);
  the per-pool waiter counter is the logical FIFO-ish queue (fair semaphores + round-robin
  scan keep starvation low).

## 5. Sample final report

```
================== SIMULATION STATISTICS ==================
Scenario                     : Normal operation
Simulated duration           : 91.50 s (1.5 min)
Customers arrived            : 50
TOTAL CUSTOMERS SERVED       : 50
AVERAGE TOTAL TIME / CUSTOMER: 11.02 s
   - average washing time     : 4.95 s
   - average drying time      : 3.98 s
   - average payment time     : 1.62 s
   - average queue waiting    : 0.47 s
MAX CONCURRENT WASHERS IN USE: 6 of 6
MAX CONCURRENT DRYERS  IN USE: 4 of 4
...
REAL WALL-CLOCK DURATION OF THE RUN: 45.8 seconds
SIMULATED DURATION               : 91.5 seconds
```

A captured console transcript of a fast normal run is included in `logs_fast_normal.txt`.

## 6. Design notes / pitfalls handled

* **No interleaved log lines** — threads never write to `System.out` directly; a single
  daemon drain thread serialises printing (and stamps consistent timestamps).
* **No leaked locks** — every successful acquire is matched by `release()`/`fail()` on all
  paths (exceptions/interrupts included); a broken device holds its permit until repaired,
  so it silently disappears from the pool instead of deadlocking waiters.
* **GUI decoupling** — the EDT only reads immutable `Snapshot` copies; simulation threads
  never touch Swing → no freezes/deadlocks between UI and worker threads.
* **One-shot owner trigger** — `thresholdFired` under a monitor guarantees the congestion
  alert fires exactly once even if many threads cross the threshold simultaneously.
* **Watchdog** — `maxRunMillis` bounds any pathological run; the executor is force-shut-down
  and partial statistics are still reported.
* **Reproducibility** — parent `Random` guarded by `synchronized(rnd)` when deriving child
  seeds; each customer owns its own seeded `Random`, eliminating contention on shared RNG state.
