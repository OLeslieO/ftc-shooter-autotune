package org.ftc.shooter.tuner.autotune;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AutoTuneManager {
    /** Each scored PID trial holds its target this long so candidates are compared over equal windows. */
    public static final double TRIAL_SECONDS = 6;
    /** FEEDFORWARD segments instead run until the velocity is steady: consecutive blocks of this length agree. */
    public static final double SETTLE_BLOCK_SECONDS = 1.5;
    public static final int SETTLE_MIN_BLOCKS = 3;
    public static final double SETTLE_MAX_SECONDS = 25;
    /** Time allowed for the flywheel to coast below 50 ticks/s between trials. */
    public static final double COAST_SECONDS = 20;
    public enum Phase { IDLE, DIRECTION, IDENTIFY, FEEDFORWARD, KP, KD, KI, AWAIT_LOAD, LOADED, VERIFY, READY, TEST, FAULT }

    public static final class ShotResult {
        public final int candidate;
        public final int shot;
        public final String phase;
        public final Gains gains;
        public final List<PerformanceMetrics> motors;

        ShotResult(int candidate, int shot, String phase, Gains gains, List<PerformanceMetrics> motors) {
            this.candidate = candidate;
            this.shot = shot;
            this.phase = phase;
            this.gains = gains;
            this.motors = motors;
        }
    }

    private final ShooterHardware hardware;
    private ShooterConfig config;
    private Phase phase = Phase.IDLE;
    private Gains gains = new Gains(0, 0, 0, 0, 0, 0);
    private Gains result;
    private String message = "Save configuration before starting";
    private VelocityController[] controllers;
    private double[] velocities = new double[0];
    private double[] powers = new double[0];
    private double[] currents = new double[0];
    private double[] stalledFor;
    private double lowBatteryFor;
    private double battery;
    private double target;
    private double elapsed;
    private double runStart;
    private double previousTime = Double.NaN;
    private double readyFor;
    private int directionMotor;
    private FeedforwardTuner identification;
    /** First FEEDFORWARD pass: measure steady states and refit kS/kV from them instead of judging the transient model. */
    private boolean calibrating;
    private final List<double[]> steadyPoints = new ArrayList<>();
    private double[] blockVelocity;
    private double[] blockVoltage;
    private double blockWeight;
    private double[] previousBlock;
    private int blocks;
    private List<Gains> candidates;
    private int candidateIndex;
    private Gains best;
    private double bestScore;
    private double bestOvershoot;
    private double bestBias;
    private double trialScore;
    private double trialOvershoot;
    private double trialBias;
    private int speedIndex;
    private boolean coast;
    private PerformanceMetrics.Recorder[] recorders;
    private LoadedShotTest shotTest;
    private int shotIndex;
    private boolean observingShot;
    private boolean manualFeeding;
    private final List<ShotResult> shots = new ArrayList<>();
    private final List<String> trials = new ArrayList<>();

    public AutoTuneManager(ShooterHardware hardware) {
        this.hardware = hardware;
    }

    public void configure(ShooterConfig configuration) {
        requireIdle();
        configuration.validate();
        config = null;
        result = null;
        shots.clear();
        trials.clear();
        try {
            hardware.stop();
            hardware.configure(configuration);
        } catch (RuntimeException failure) {
            try {
                stop("Configuration failed: " + failure.getMessage());
            } catch (RuntimeException stopFailure) {
                failure.addSuppressed(stopFailure);
            } finally {
                phase = Phase.FAULT;
            }
            throw failure;
        }
        config = configuration;
        int count = config.dual ? 2 : 1;
        controllers = new VelocityController[count];
        for (int index = 0; index < count; index++) controllers[index] = new VelocityController();
        powers = new double[count];
        stalledFor = new double[count];
        phase = Phase.IDLE;
        message = "Configured. Check directions at low power, then start unloaded tuning.";
    }

    public void directionTest(int motor, double now) {
        requireConfigured();
        requireIdle();
        if (motor < 0 || motor > (config.dual ? 2 : 1)) throw new IllegalArgumentException("Invalid motor index");
        begin(now);
        directionMotor = motor;
        phase = Phase.DIRECTION;
        message = "Low-power direction pulse (0.3 seconds)";
    }

    public void startTuning(double now) {
        requireConfigured();
        requireIdle();
        double[] initialVelocity = hardware.velocities();
        for (double velocity : initialVelocity) {
            if (Math.abs(velocity) >= 50) throw new IllegalStateException("Wait for the flywheel to stop before identification");
        }
        begin(now);
        result = null;
        shots.clear();
        trials.clear();
        identification = new FeedforwardTuner();
        identification.begin(initialVelocity);
        phase = Phase.IDENTIFY;
        message = "Identifying kS/kV/kA with bounded power steps; keep the shooter unloaded";
    }

    public void startLoaded(double now) {
        if (phase != Phase.AWAIT_LOAD) throw new IllegalStateException("Finish unloaded tuning first");
        begin(now);
        beginSearch(Phase.LOADED, PIDTuner.loadedCandidates(gains));
        message = "Loaded optimization: hold gamepad1.right_bumper to feed each shot once ready";
    }

    public void startTest(Gains constants, double now) {
        requireConfigured();
        requireIdle();
        Gains selected = result == null ? constants : result;
        if (selected == null || selected.kV <= 0) throw new IllegalArgumentException("Tune or paste valid Constants first");
        begin(now);
        gains = selected;
        phase = Phase.TEST;
        message = "Test: right bumper starts feeding at speed; release to stop feeding";
    }

    private void begin(double now) {
        hardware.stop();
        runStart = now;
        previousTime = now;
        elapsed = 0;
        readyFor = 0;
        target = 0;
        manualFeeding = false;
        lowBatteryFor = 0;
        for (int index = 0; index < powers.length; index++) {
            controllers[index].reset();
            powers[index] = 0;
            stalledFor[index] = 0;
        }
    }

    public void update(double now, boolean robotActive, boolean browserAlive, boolean rightBumper) {
        double seconds = Double.isNaN(previousTime) ? 0.02 : now - previousTime;
        previousTime = now;
        try {
            if (config == null) return;
            velocities = hardware.velocities();
            currents = hardware.currents();
            battery = hardware.batteryVoltage();
            if (!busy()) return;
            if (!robotActive || !browserAlive) throw new IllegalStateException("Stopped: Driver Station or browser disconnected");
            if (!(seconds > 0 && seconds <= 0.2)) throw new IllegalStateException("Control loop missed its 200 ms deadline");
            safety(seconds);
            elapsed += seconds;
            if (phase == Phase.DIRECTION) {
                if (elapsed >= 0.3) stop("Direction pulse complete; visually check the selected motor");
                else {
                    double pulsePower = 0.12;
                    hardware.directionPulse(directionMotor, pulsePower);
                    if (directionMotor < powers.length) powers[directionMotor] = pulsePower;
                }
            } else if (phase == Phase.IDENTIFY) {
                identify(seconds);
            } else if (phase == Phase.LOADED || phase == Phase.VERIFY) {
                loaded(seconds, rightBumper);
            } else if (phase == Phase.TEST) {
                control(config.targetVelocity, seconds);
                manualFeeding = rightBumper && (manualFeeding || atSpeed(0.1));
                hardware.feed(manualFeeding);
            } else {
                trial(seconds);
            }
        } catch (RuntimeException failure) {
            stop(failure.getMessage());
            phase = Phase.FAULT;
        }
    }

    private void safety(double seconds) {
        if (!Double.isFinite(battery)) throw new IllegalStateException("Invalid battery voltage reading");
        // Full-power flywheel spin-up pulls the pack down for a few hundred milliseconds; only a sustained sag is a fault.
        lowBatteryFor = battery < config.minBatteryVolts ? lowBatteryFor + seconds : 0;
        if (lowBatteryFor > 0.5) {
            throw new IllegalStateException(String.format(java.util.Locale.US,
                    "Low battery: %.1f V stayed below the %.1f V minimum for 0.5 s; charge or lower Minimum battery voltage",
                    battery, config.minBatteryVolts));
        }
        if (velocities.length != powers.length || currents.length != powers.length + 1) {
            throw new IllegalStateException("Hardware sample size mismatch");
        }
        for (int motor = 0; motor < velocities.length; motor++) {
            double velocity = velocities[motor];
            if (!Double.isFinite(velocity)) throw new IllegalStateException("Invalid shooter encoder reading: " + motor);
            if (phase != Phase.DIRECTION && velocity < -50) throw new IllegalStateException("Negative shooter encoder: check motor direction");
            stalledFor[motor] = powers[motor] > 0.2 && Math.abs(velocity) < 30 ? stalledFor[motor] + seconds : 0;
            if (stalledFor[motor] > 0.8) throw new IllegalStateException("Shooter stall/encoder disconnected: " + motor);
        }
        double feederVelocity = hardware.preshooterVelocity();
        if (!Double.isFinite(feederVelocity)) throw new IllegalStateException("Invalid preshooter encoder reading");
        for (int motor = 0; motor < currents.length; motor++) {
            if (!Double.isFinite(currents[motor]) || currents[motor] < 0) throw new IllegalStateException("Invalid motor current reading: " + motor);
        }
    }

    private void identify(double seconds) {
        // powers[] still holds the command applied during the interval that just ended.
        for (int motor = 0; motor < velocities.length; motor++) {
            identification.add(motor, velocities[motor], powers[motor] * battery, seconds);
        }
        double[] levels = {0.2, 0.4, 0.65, 0.35, 0.75, 0.5};
        int step = (int) (elapsed / 1.6);
        if (step >= levels.length) {
            gains = identification.fit();
            if (gains.kS + gains.kV * config.targetVelocity > battery * 0.9) {
                throw new IllegalStateException("Target lacks power headroom; lower target velocity");
            }
            trials.add(String.format(java.util.Locale.US, "Identification R²=%.3f, τ=%.2f s, kS=%.2f V, kV=%.5f, kA=%.5f",
                    identification.fitQuality, identification.timeConstant, gains.kS, gains.kV, gains.kA));
            calibrating = true;
            steadyPoints.clear();
            beginSearch(Phase.FEEDFORWARD, Collections.singletonList(gains));
            return;
        }
        for (int motor = 0; motor < powers.length; motor++) powers[motor] = levels[step];
        hardware.shooterPowers(powers);
    }

    private void beginSearch(Phase next, List<Gains> options) {
        phase = next;
        candidates = options;
        candidateIndex = 0;
        best = options.get(0);
        bestScore = Double.POSITIVE_INFINITY;
        bestOvershoot = 0;
        bestBias = 0;
        beginTrial();
    }

    private void beginTrial() {
        gains = candidates.get(candidateIndex);
        hardware.stop();
        for (int motor = 0; motor < powers.length; motor++) {
            powers[motor] = 0;
            controllers[motor].reset();
        }
        elapsed = 0;
        target = 0;
        readyFor = 0;
        speedIndex = 0;
        shotIndex = 0;
        observingShot = false;
        trialScore = 0;
        trialOvershoot = 0;
        trialBias = 0;
        coast = true;
        message = phase == Phase.FEEDFORWARD ? "FEEDFORWARD " + (calibrating ? "calibration" : "validation") + ": coasting down"
                : phase + ": candidate " + (candidateIndex + 1) + "/" + candidates.size();
    }

    private boolean coasting(double seconds) {
        if (!coast) return false;
        hardware.stop();
        boolean stopped = true;
        for (double velocity : velocities) stopped &= Math.abs(velocity) < 50;
        readyFor = stopped ? readyFor + seconds : 0;
        if (elapsed > COAST_SECONDS) throw new IllegalStateException("Flywheel did not coast down within " + (int) COAST_SECONDS + " seconds");
        if (readyFor >= 0.2) {
            coast = false;
            elapsed = 0;
            readyFor = 0;
            newRecorders();
        }
        return true;
    }

    private double trialTarget() {
        double[] fractions = phase == Phase.FEEDFORWARD ? new double[]{0.55, 0.8, 1} : new double[]{0.65, 1, 0.8};
        return fractions[speedIndex] * config.targetVelocity;
    }

    private void newRecorders() {
        blockVelocity = new double[powers.length];
        blockVoltage = new double[powers.length];
        blockWeight = 0;
        previousBlock = null;
        blocks = 0;
        if (phase == Phase.FEEDFORWARD) {
            message = String.format(java.util.Locale.US, "FEEDFORWARD %s at %.0f ticks/s: waiting for a steady velocity",
                    calibrating ? "calibration" : "validation", trialTarget());
        }
        recorders = new PerformanceMetrics.Recorder[powers.length];
        for (int motor = 0; motor < recorders.length; motor++) {
            recorders[motor] = new PerformanceMetrics.Recorder(trialTarget(), velocities[motor], TRIAL_SECONDS,
                    trialTarget() < target);
        }
    }

    private void trial(double seconds) {
        if (coasting(seconds)) return;
        control(trialTarget(), seconds);
        for (int motor = 0; motor < recorders.length; motor++) recorders[motor].add(velocities[motor], seconds);
        if (phase == Phase.FEEDFORWARD) {
            for (int motor = 0; motor < powers.length; motor++) {
                blockVelocity[motor] += velocities[motor] * seconds;
                blockVoltage[motor] += powers[motor] * battery * seconds;
            }
            blockWeight += seconds;
            if (blockWeight < SETTLE_BLOCK_SECONDS) return;
            double[] meanVelocity = new double[powers.length];
            double[] meanVoltage = new double[powers.length];
            double change = 0;
            for (int motor = 0; motor < powers.length; motor++) {
                meanVelocity[motor] = blockVelocity[motor] / blockWeight;
                meanVoltage[motor] = blockVoltage[motor] / blockWeight;
                if (previousBlock != null) change = Math.max(change, Math.abs(meanVelocity[motor] - previousBlock[motor]));
            }
            blocks++;
            double tolerance = Math.max(25, 0.015 * trialTarget());
            boolean settled = blocks >= SETTLE_MIN_BLOCKS && change <= tolerance;
            if (!settled && elapsed < SETTLE_MAX_SECONDS) {
                message = String.format(java.util.Locale.US, "FEEDFORWARD %s at %.0f ticks/s: %.0f ticks/s, still changing %.0f per %.1f s",
                        calibrating ? "calibration" : "validation", trialTarget(), meanVelocity[0], change, SETTLE_BLOCK_SECONDS);
                previousBlock = meanVelocity;
                blockVelocity = new double[powers.length];
                blockVoltage = new double[powers.length];
                blockWeight = 0;
                return;
            }
            if (!settled) {
                throw new IllegalStateException(String.format(java.util.Locale.US,
                        "Velocity did not settle within %.0f s at %.0f ticks/s (still changing %.0f ticks/s per %.1f s); check for a slipping drive or loose encoder",
                        SETTLE_MAX_SECONDS, trialTarget(), change, SETTLE_BLOCK_SECONDS));
            }
            endSegment(meanVelocity, meanVoltage);
            return;
        }
        if (elapsed < TRIAL_SECONDS) return;
        endSegment(null, null);
    }

    /** Closes one speed segment; {@code steadyVelocity}/{@code steadyVoltage} are the settled block means for FEEDFORWARD. */
    private void endSegment(double[] steadyVelocity, double[] steadyVoltage) {
        if (phase == Phase.FEEDFORWARD) {
            for (int motor = 0; motor < powers.length; motor++) {
                if (calibrating) {
                    if (steadyVelocity[motor] < 100) {
                        throw new IllegalStateException(String.format(java.util.Locale.US,
                                "Shooter %d reached only %.0f ticks/s with %.1f V of feedforward; check directions and encoders",
                                motor + 1, steadyVelocity[motor], steadyVoltage[motor]));
                    }
                    steadyPoints.add(new double[]{steadyVelocity[motor], steadyVoltage[motor]});
                    trials.add(String.format(java.util.Locale.US, "Calibration point: target %.0f, shooter %d steady at %.0f ticks/s with %.2f V (%.1f s)",
                            trialTarget(), motor + 1, steadyVelocity[motor], steadyVoltage[motor], elapsed));
                } else {
                    double steadyError = Math.abs(trialTarget() - steadyVelocity[motor]);
                    if (steadyError / trialTarget() > 0.15) {
                        throw new IllegalStateException(String.format(java.util.Locale.US,
                                "Feedforward validation failed at %.0f ticks/s: shooter %d settled at %.0f ticks/s with %.2f V",
                                trialTarget(), motor + 1, steadyVelocity[motor], steadyVoltage[motor]));
                    }
                }
            }
        }
        for (PerformanceMetrics.Recorder recorder : recorders) {
            PerformanceMetrics metrics = recorder.finish();
            trialScore += metrics.score(trialTarget()) / powers.length / 3;
            trialOvershoot = Math.max(trialOvershoot, metrics.overshoot / trialTarget());
            trialBias = Math.max(trialBias, metrics.steadyError / trialTarget());
        }
        speedIndex++;
        elapsed = 0;
        if (speedIndex < 3) newRecorders();
        else finishCandidate();
    }

    private void finishCandidate() {
        if (phase == Phase.FEEDFORWARD && calibrating) {
            calibrate();
            return;
        }
        trials.add(phase + " candidate " + (candidateIndex + 1) + " score=" + trialScore);
        if (trialScore < bestScore) {
            bestScore = trialScore;
            best = gains;
            bestOvershoot = trialOvershoot;
            bestBias = trialBias;
        }
        candidateIndex++;
        if (candidateIndex < candidates.size()) {
            beginTrial();
            return;
        }
        gains = best;
        hardware.stop();
        if (phase == Phase.FEEDFORWARD) beginSearch(Phase.KP, PIDTuner.proportionalCandidates(gains));
        else if (phase == Phase.KP && bestOvershoot > 0.08) beginSearch(Phase.KD, PIDTuner.derivativeCandidates(gains));
        else if ((phase == Phase.KP || phase == Phase.KD) && bestBias > 0.02) beginSearch(Phase.KI, PIDTuner.integralCandidates(gains));
        else if (phase == Phase.LOADED) {
            if (!Double.isFinite(bestScore)) throw new IllegalStateException("No loaded candidate recovered reliably");
            beginSearch(Phase.VERIFY, Collections.singletonList(gains));
        } else if (phase == Phase.VERIFY) {
            if (!Double.isFinite(bestScore)) throw new IllegalStateException("Final loaded verification failed");
            result = gains;
            stop("Validated under load. Export constants or start Test.");
            phase = Phase.READY;
        } else {
            stop("Unloaded tuning complete. Load game pieces and arm loaded tests in the webpage.");
            phase = Phase.AWAIT_LOAD;
        }
    }

    /**
     * Refits kS and kV from the measured steady states of the calibration pass. The transient identification
     * only sees a few seconds of spin-up, so on a heavy or drag-dominated flywheel it can misplace the static
     * curve; the steady states are what the controller must reproduce. τ from the identification sets kA.
     */
    private void calibrate() {
        hardware.stop();
        double meanVelocity = 0;
        double meanVoltage = 0;
        for (double[] point : steadyPoints) {
            meanVelocity += point[0] / steadyPoints.size();
            meanVoltage += point[1] / steadyPoints.size();
        }
        double covariance = 0;
        double variance = 0;
        for (double[] point : steadyPoints) {
            covariance += (point[0] - meanVelocity) * (point[1] - meanVoltage);
            variance += (point[0] - meanVelocity) * (point[0] - meanVelocity);
        }
        if (variance < 1e4) throw new IllegalStateException("Steady-state calibration lacks speed spread; check that the shooter follows the target");
        double velocityGain = covariance / variance;
        double staticGain = meanVoltage - velocityGain * meanVelocity;
        StringBuilder points = new StringBuilder();
        for (double[] point : steadyPoints) points.append(String.format(java.util.Locale.US, " (%.0f ticks/s, %.2f V)", point[0], point[1]));
        // A heavy belt-driven wheel can genuinely need several volts to turn, so the static limit scales with the pack.
        if (velocityGain <= 0 || staticGain < -0.4 || staticGain > battery * 0.5) {
            throw new IllegalStateException(String.format(java.util.Locale.US,
                    "Nonphysical steady-state calibration (kS=%.2f V, kV=%.5f) from points%s; check encoders and directions",
                    staticGain, velocityGain, points));
        }
        double timeConstant = identification.timeConstant > 0 ? identification.timeConstant : gains.kA / gains.kV;
        gains = new Gains(Math.max(0, staticGain), velocityGain, velocityGain * timeConstant, 0, 0, 0);
        if (gains.kS + gains.kV * config.targetVelocity > battery * 0.95) {
            throw new IllegalStateException(String.format(java.util.Locale.US,
                    "Target needs %.1f V of %.1f V available; lower target velocity", gains.kS + gains.kV * config.targetVelocity, battery));
        }
        trials.add(String.format(java.util.Locale.US, "Steady-state calibration from %d points: kS=%.2f V, kV=%.5f, kA=%.5f",
                steadyPoints.size(), gains.kS, gains.kV, gains.kA));
        calibrating = false;
        beginSearch(Phase.FEEDFORWARD, Collections.singletonList(gains));
    }

    private void loaded(double seconds, boolean rightBumper) {
        if (coasting(seconds)) return;
        control(config.targetVelocity, seconds);
        if (!observingShot) {
            readyFor = atSpeed(0.05) ? readyFor + seconds : 0;
            if (elapsed > 8) throw new IllegalStateException("Shooter could not reach shot-ready velocity");
            if (readyFor >= 0.35) {
                message = phase + ": candidate " + (candidateIndex + 1) + ", shot " + (shotIndex + 1) + "/" + config.shots
                        + " ready; hold gamepad1.right_bumper to feed";
                if (rightBumper) {
                    shotTest = new LoadedShotTest(config, velocities);
                    observingShot = true;
                    elapsed = 0;
                    hardware.feed(true);
                    message = phase + ": candidate " + (candidateIndex + 1) + ", shot " + (shotIndex + 1) + "/" + config.shots + " feeding";
                }
            }
            return;
        }
        hardware.feed(rightBumper);
        shotTest.sample(velocities, seconds);
        if (elapsed < config.recoverySeconds) return;
        hardware.feed(false);
        List<PerformanceMetrics> metrics = shotTest.finish();
        shots.add(new ShotResult(candidateIndex + 1, shotIndex + 1, phase.name(), gains, metrics));
        double worst = 0;
        for (PerformanceMetrics motor : metrics) {
            double score = motor.score(config.targetVelocity);
            if (!motor.recovered || motor.overshoot > config.targetVelocity * 0.15
                    || motor.steadyError > config.targetVelocity * 0.05) score = Double.POSITIVE_INFINITY;
            worst = Math.max(worst, score);
        }
        trialScore += worst / config.shots;
        observingShot = false;
        readyFor = 0;
        elapsed = 0;
        shotIndex++;
        if (shotIndex >= config.shots) finishCandidate();
    }

    private boolean atSpeed(double fraction) {
        for (double velocity : velocities) {
            if (Math.abs(velocity - config.targetVelocity) > config.targetVelocity * fraction) return false;
        }
        return true;
    }

    private void control(double demand, double seconds) {
        double previousTarget = target;
        target += VelocityController.clamp(demand - target, -config.targetAcceleration * seconds,
                config.targetAcceleration * seconds);
        double acceleration = (target - previousTarget) / seconds;
        for (int motor = 0; motor < powers.length; motor++) {
            powers[motor] = controllers[motor].update(gains, target, acceleration,
                    velocities[motor], seconds, battery, 1);
        }
        hardware.shooterPowers(powers);
    }

    public void stop(String reason) {
        try {
            hardware.stop();
        } finally {
            for (int motor = 0; motor < powers.length; motor++) powers[motor] = 0;
            target = 0;
            manualFeeding = false;
            phase = Phase.IDLE;
            message = reason;
        }
    }

    private void requireConfigured() {
        if (config == null) throw new IllegalStateException("Save Configure first");
    }

    private void requireIdle() {
        if (busy() || phase == Phase.AWAIT_LOAD) throw new IllegalStateException("Stop the current session first");
    }

    public boolean busy() {
        return phase != Phase.IDLE && phase != Phase.AWAIT_LOAD && phase != Phase.READY && phase != Phase.FAULT;
    }

    public Phase phase() { return phase; }
    public String message() { return message; }
    public ShooterConfig config() { return config; }
    public Gains gains() { return gains; }
    public Gains result() { return result; }
    public double[] velocities() { return velocities.clone(); }
    public double[] powers() { return powers.clone(); }
    public double[] currents() { return currents.clone(); }
    public double battery() { return battery; }
    public double target() { return target; }
    public List<ShotResult> shots() { return Collections.unmodifiableList(shots); }
    public List<String> trials() { return Collections.unmodifiableList(trials); }
}
