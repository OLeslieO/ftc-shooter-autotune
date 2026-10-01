package org.ftc.shooter.tuner.autotune;

public final class AutoTuneSimulationTest {
    private static final Gains MODEL = new Gains(0.3, 0.006, 0.002, 0, 0, 0);
    private static int checks;

    public static void main(String[] arguments) {
        identification();
        controller();
        metrics();
        validation();
        manualFeedDoesNotRetrigger();
        fullSession(false);
        fullSession(true);
        dragSession();
        missingLoad();
        safety();
        System.out.println("PASS: " + checks + " assertions; single/dual flywheel sessions, loaded verification and safety");
    }

    private static void identification() {
        Gains exact = identify(MODEL, 0, 0, 1);
        // The plant clamps at zero speed during the initial unpowered tick, so the linear fit is not bit-exact.
        near(exact.kS, MODEL.kS, 0.01, "identify kS");
        near(exact.kV, MODEL.kV, 1e-5, "identify kV");
        near(exact.kA, MODEL.kA, 1e-5, "identify kA");
        // Heavy flywheel (τ = 2 s) with 50 ticks/s gaussian encoder noise and 40 ticks/s quantisation,
        // the regime where differentiating velocity made the previous estimator fail (R² ≈ 0.4).
        Gains heavy = new Gains(0.3, 0.006, 0.012, 0, 0, 0);
        for (int seed = 1; seed <= 5; seed++) {
            FeedforwardTuner tuner = new FeedforwardTuner();
            Gains noisy = identify(heavy, 50, 40, seed, tuner);
            check(tuner.fitQuality > 0.95, "noisy heavy flywheel R²: got " + tuner.fitQuality);
            near(noisy.kV / heavy.kV, 1, 0.05, "noisy kV within 5%");
            near(noisy.kA / heavy.kA, 1, 0.15, "noisy kA within 15%");
            near(noisy.kS, heavy.kS, 0.4, "noisy kS");
        }
        FeedforwardTuner singular = new FeedforwardTuner();
        singular.begin(new double[]{1000});
        for (int index = 0; index < 400; index++) singular.add(0, 1000, 6.3, 0.02);
        rejects(singular::fit, "reject singular identification");
    }

    private static Gains identify(Gains model, double noise, double quantum, long seed) {
        return identify(model, noise, quantum, seed, new FeedforwardTuner());
    }

    /** Replays the manager's six 1.6 s power steps against an exact first-order plant with measurement noise. */
    private static Gains identify(Gains model, double noise, double quantum, long seed, FeedforwardTuner tuner) {
        java.util.Random random = new java.util.Random(seed);
        double[] levels = {0.2, 0.4, 0.65, 0.35, 0.75, 0.5};
        double battery = 12.6;
        double velocity = 0;
        double power = 0;
        double elapsed = 0;
        tuner.begin(new double[]{0});
        while ((int) (elapsed / 1.6) < levels.length) {
            double equilibrium = Math.max(0, (power * battery - model.kS) / model.kV);
            velocity = equilibrium + (velocity - equilibrium) * Math.exp(-model.kV / model.kA * 0.02);
            double measured = velocity + random.nextGaussian() * noise;
            if (quantum > 0) measured = Math.round(measured / quantum) * quantum;
            tuner.add(0, measured, power * battery, 0.02);
            elapsed += 0.02;
            power = levels[(int) Math.min(levels.length - 1, elapsed / 1.6)];
        }
        return tuner.fit();
    }

    private static void controller() {
        VelocityController controller = new VelocityController();
        Gains gains = MODEL.pid(0.05, 0.01, 0);
        for (int index = 0; index < 500; index++) {
            near(controller.update(gains, 4000, 0, 0, 0.02, 12, 0.8), 0.8, 1e-9, "bounded saturated output");
        }
        near(controller.update(gains, 1000, 0, 1000, 0.02, 12, 0.8), 6.3 / 12, 1e-9, "anti-windup after saturation");
        near(controller.update(gains, 0, 0, 1000, 0.02, 12, 0.8), 0, 1e-9, "zero target stops");
        rejects(() -> controller.update(gains, 1000, 0, 0, 0.3, 12, 0.8), "reject bad loop timing");
        near(new VelocityController().update(MODEL, 1000, 0, 1000, 0.02, 11, 0.9), 6.3 / 11, 1e-9, "voltage compensation");
    }

    private static void metrics() {
        PerformanceMetrics.Recorder recorder = new PerformanceMetrics.Recorder(1000, 1000, 2);
        for (int index = 0; index < 100; index++) recorder.add(index < 20 ? 800 : 1000, 0.02);
        PerformanceMetrics measured = recorder.finish();
        near(measured.velocityDrop, 200, 1e-9, "velocity drop");
        near(measured.maximumError, 200, 1e-9, "max error");
        near(measured.recoveryTime, 0.4, 1e-9, "recovery time");
        near(measured.rmse, Math.sqrt(8000), 1e-8, "time-weighted RMSE");
        check(measured.recovered, "sustained recovery");
        PerformanceMetrics.Recorder unrecovered = new PerformanceMetrics.Recorder(1000, 1000, 1);
        for (int index = 0; index < 50; index++) unrecovered.add(700, 0.02);
        check(!unrecovered.finish().recovered, "unrecovered shot detected");
    }

    private static void validation() {
        check(PIDTuner.loadedCandidates(MODEL).get(1).kP > 0, "loaded optimization can add feedback to a feedforward-only winner");
        ShooterConfig config = new ShooterConfig();
        config.targetVelocity = Double.NaN;
        rejects(config::validate, "reject NaN config");
        config.targetVelocity = 1440;
        config.preshooter = config.shooter;
        rejects(config::validate, "reject duplicate motor names");
        Fixture fixture = new Fixture(false);
        rejects(() -> fixture.manager.startLoaded(0), "cannot fire before unloaded tuning");
        rejects(() -> fixture.manager.startTest(new Gains(0, 0, 0, 0, 0, 0), 0), "zero constants cannot Test");
    }

    /** Aerodynamic drag makes the static curve steeper than the spin-up transient suggests; calibration must absorb it. */
    private static void dragSession() {
        Fixture fixture = new Fixture(true);
        fixture.plant.drag = 4e-7;
        fixture.manager.startTuning(fixture.time);
        fixture.until(AutoTuneManager.Phase.AWAIT_LOAD, 600);
        double needed = fixture.plant.steadyVoltage(fixture.config.targetVelocity);
        near(fixture.manager.gains().kS + fixture.manager.gains().kV * fixture.config.targetVelocity, needed, needed * 0.03,
                "calibrated feedforward matches the real steady state at target");
        boolean calibrated = false;
        for (String line : fixture.manager.trials()) calibrated |= line.startsWith("Steady-state calibration");
        check(calibrated, "steady-state calibration recorded");
        check(fixture.plant.stopped(), "stopped after drag session");
    }

    private static void fullSession(boolean dual) {
        Fixture fixture = new Fixture(dual);
        fixture.manager.startTuning(fixture.time);
        fixture.until(AutoTuneManager.Phase.AWAIT_LOAD, 600);
        check(fixture.manager.result() == null, "no export before loaded validation");
        near(fixture.manager.gains().kV, MODEL.kV, 0.0003, "simulated kV fit");
        near(fixture.manager.gains().kA, MODEL.kA, 0.0003, "simulated kA fit");
        fixture.manager.startLoaded(fixture.time);
        fixture.until(AutoTuneManager.Phase.READY, 600);
        check(fixture.manager.result() != null, "validated constants present");
        check(fixture.plant.pulses == 4 * fixture.config.shots, "real feed pulses for every optimization and validation shot");
        check(fixture.manager.shots().size() == 4 * fixture.config.shots, "per-shot records");
        for (AutoTuneManager.ShotResult shot : fixture.manager.shots()) {
            check(shot.motors.size() == (dual ? 2 : 1), "individual motor metrics");
            check(shot.motors.get(0).velocityDrop > 100, "detected real load drop");
        }
        check(fixture.manager.result().javaConstants().contains("SHOOTER_KA"), "Java export");
        check(fixture.plant.stopped(), "motors stopped at completion");
        fixture.manager.startTest(fixture.manager.result(), fixture.time);
        fixture.tick(2, false);
        check(!fixture.plant.feeding, "Test feeder off without bumper");
        fixture.tick(0.02, true);
        check(fixture.plant.feeding, "right bumper feeds in Test");
        fixture.tick(0.02, false);
        check(!fixture.plant.feeding, "bumper release stops feeder");
        fixture.manager.stop("done");
        check(fixture.plant.stopped(), "manual Stop");
        fixture.plant.failConfiguration = true;
        rejects(() -> fixture.manager.configure(fixture.config), "hardware configuration failure reported");
        check(fixture.manager.result() == null, "configuration failure invalidates export");
        check(fixture.manager.config() == null, "configuration failure invalidates hardware configuration");
        check(fixture.manager.phase() == AutoTuneManager.Phase.FAULT, "configuration failure enters fault state");
        check(fixture.plant.stopped(), "configuration failure stops outputs");
        rejects(() -> fixture.manager.startTest(MODEL, fixture.time), "cannot restart after failed configuration");
    }

    private static void manualFeedDoesNotRetrigger() {
        Fixture fixture = new Fixture(false);
        fixture.manager.startTest(MODEL, 0);
        fixture.tick(2, false);
        fixture.tick(0.6, true);
        check(fixture.plant.pulses == 1, "holding bumper must not retrigger feeding after a velocity dip");
        check(fixture.plant.feeding, "manual feed remains on while bumper is held");
        fixture.tick(0.02, false);
        check(!fixture.plant.feeding, "releasing bumper ends latched feed");
        fixture.tick(1, false);
        fixture.tick(0.02, true);
        check(fixture.plant.pulses == 2, "new bumper press can feed again");
        fixture.manager.stop("test stop");
        check(fixture.plant.stopped(), "Stop overrides latched feed");
        fixture.manager.startTest(MODEL, fixture.time);
        fixture.tick(0.02, false);
        check(!fixture.plant.feeding, "new session resets manual feed latch");
    }

    private static void missingLoad() {
        Fixture fixture = new Fixture(false);
        fixture.plant.deliverShots = false;
        fixture.manager.startTuning(0);
        fixture.until(AutoTuneManager.Phase.AWAIT_LOAD, 600);
        fixture.manager.startLoaded(fixture.time);
        fixture.until(AutoTuneManager.Phase.FAULT, 30);
        check(fixture.manager.result() == null, "empty feeder cannot validate");
        check(fixture.manager.message().contains("No shot load"), "missing load is explicit");
        check(fixture.plant.stopped(), "empty feeder failure stops");
    }

    private static void safety() {
        Fixture fixture = new Fixture(true);
        fixture.manager.startTest(MODEL, 0);
        fixture.tick(1, false);
        fixture.time += 0.02;
        fixture.manager.update(fixture.time, true, false, false);
        check(fixture.manager.phase() == AutoTuneManager.Phase.FAULT && fixture.plant.stopped(), "heartbeat stop");
        fixture = new Fixture(false);
        fixture.manager.startTest(MODEL, 0);
        fixture.manager.update(0.02, false, true, false);
        check(fixture.plant.stopped(), "DS stop");
        fixture = new Fixture(false);
        fixture.manager.startTest(MODEL, 0);
        fixture.plant.velocity[0] = 3000;
        fixture.manager.update(0.02, true, true, false);
        check(fixture.manager.phase() != AutoTuneManager.Phase.FAULT, "overspeed no longer stops (protection intentionally removed)");
        fixture = new Fixture(false);
        fixture.manager.startTest(MODEL, 0);
        fixture.plant.current = 30;
        fixture.tick(0.3, false);
        check(fixture.manager.phase() != AutoTuneManager.Phase.FAULT, "overcurrent no longer stops (protection intentionally removed)");
        fixture = new Fixture(false);
        fixture.manager.startTest(MODEL, 0);
        fixture.plant.battery = 9;
        fixture.tick(0.3, false);
        check(fixture.manager.phase() == AutoTuneManager.Phase.TEST, "brief voltage sag tolerated");
        fixture.plant.battery = 12.6;
        fixture.tick(0.1, false);
        fixture.plant.battery = 9;
        fixture.tick(0.4, false);
        check(fixture.manager.phase() == AutoTuneManager.Phase.TEST, "sag timer resets on recovery");
        fixture.tick(0.2, false);
        check(fixture.plant.stopped() && fixture.manager.phase() == AutoTuneManager.Phase.FAULT, "sustained low battery stops");
        check(fixture.manager.message().startsWith("Low battery: 9.0 V"), "battery message reports voltage");
        fixture = new Fixture(false);
        fixture.manager.directionTest(0, 0);
        fixture.tick(0.4, false);
        check(fixture.plant.stopped(), "direction test automatically stops");
        fixture = new Fixture(false);
        fixture.manager.startTest(MODEL, 0);
        fixture.manager.update(0.3, true, true, false);
        check(fixture.plant.stopped(), "loop deadline stops");
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }

    private static void near(double actual, double expected, double tolerance, String name) {
        check(Math.abs(actual - expected) <= tolerance, name + ": got " + actual + ", expected " + expected);
    }

    private static void rejects(Runnable operation, String name) {
        try { operation.run(); } catch (IllegalArgumentException | IllegalStateException expected) { checks++; return; }
        throw new AssertionError(name);
    }

    private static final class Fixture {
        final Plant plant = new Plant();
        final AutoTuneManager manager = new AutoTuneManager(plant);
        final ShooterConfig config = new ShooterConfig();
        double time;

        Fixture(boolean dual) {
            config.dual = dual;
            manager.configure(config);
        }

        void tick(double duration, boolean bumper) {
            int count = (int) Math.ceil(duration / 0.02);
            for (int index = 0; index < count; index++) {
                plant.advance(0.02);
                time += 0.02;
                manager.update(time, true, true, bumper);
            }
        }

        void until(AutoTuneManager.Phase desired, double limit) {
            // Loaded/verify shots require a held bumper to feed; harmless elsewhere since only those phases read it.
            double deadline = time + limit;
            while (time < deadline && manager.phase() != desired) {
                if (manager.phase() == AutoTuneManager.Phase.FAULT) throw new AssertionError(manager.message());
                tick(0.02, true);
            }
            check(manager.phase() == desired, "phase " + desired + "; got " + manager.phase() + " / " + manager.message());
        }
    }

    private static final class Plant implements ShooterHardware {
        double[] velocity;
        double[] power;
        double battery = 12.6;
        double current = 2;
        double drag;
        boolean feeding;
        boolean deliverShots = true;
        boolean failConfiguration;
        int pulses;

        public void configure(ShooterConfig config) {
            if (failConfiguration) throw new IllegalStateException("Simulated configuration failure");
            velocity = new double[config.dual ? 2 : 1];
            power = new double[velocity.length];
        }

        void advance(double seconds) {
            for (int motor = 0; motor < velocity.length; motor++) {
                double available = power[motor] * battery - MODEL.kS;
                double equilibrium = available <= 0 ? 0 : drag > 0
                        ? (-MODEL.kV + Math.sqrt(MODEL.kV * MODEL.kV + 4 * drag * available)) / (2 * drag)
                        : available / MODEL.kV;
                velocity[motor] = equilibrium + (velocity[motor] - equilibrium) * Math.exp(-MODEL.kV / MODEL.kA * seconds);
            }
        }

        double steadyVoltage(double speed) {
            return MODEL.kS + MODEL.kV * speed + drag * speed * speed;
        }

        public double[] velocities() { return velocity.clone(); }
        public double[] currents() {
            double[] samples = new double[velocity.length + 1];
            java.util.Arrays.fill(samples, current);
            return samples;
        }
        public double batteryVoltage() { return battery; }
        public double preshooterVelocity() { return feeding ? 500 : 0; }
        public void shooterPowers(double[] values) { power = values.clone(); }
        public void feed(boolean enabled) {
            if (enabled && !feeding) {
                pulses++;
                if (deliverShots) for (int motor = 0; motor < velocity.length; motor++) velocity[motor] *= 0.75;
            }
            feeding = enabled;
        }
        public void directionPulse(int motor, double value) {
            if (motor < power.length) power[motor] = value;
        }
        public void stop() {
            if (power != null) java.util.Arrays.fill(power, 0);
            feeding = false;
        }
        boolean stopped() {
            for (double value : power) if (value != 0) return false;
            return !feeding;
        }
    }
}
