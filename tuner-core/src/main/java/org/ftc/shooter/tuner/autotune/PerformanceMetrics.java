package org.ftc.shooter.tuner.autotune;

public final class PerformanceMetrics {
    public final double velocityDrop;
    public final double maximumError;
    public final double recoveryTime;
    public final double overshoot;
    public final double rmse;
    public final double steadyError;
    public final boolean recovered;

    public PerformanceMetrics(double drop, double maximum, double recovery, double excess,
                              double rms, double bias, boolean settled) {
        velocityDrop = drop;
        maximumError = maximum;
        recoveryTime = recovery;
        overshoot = excess;
        rmse = rms;
        steadyError = bias;
        recovered = settled;
    }

    public double score(double target) {
        return rmse / target + 2 * overshoot / target + 2 * steadyError / target
                + 0.15 * recoveryTime + (recovered ? 0 : 2);
    }

    public static final class Recorder {
        private final double target;
        private final double baseline;
        private final double observationSeconds;
        private final double stepDirection;
        private double minimum = Double.POSITIVE_INFINITY;
        private double maximumError;
        private double overshoot;
        private double squaredError;
        private double weight;
        private double tailError;
        private double tailWeight;
        private double lastOutside;
        private double elapsed;

        public Recorder(double target, double baseline, double observationSeconds) {
            this(target, baseline, observationSeconds, false);
        }

        public Recorder(double target, double baseline, double observationSeconds, boolean decreasingTarget) {
            this.target = target;
            this.baseline = baseline;
            this.observationSeconds = observationSeconds;
            stepDirection = decreasingTarget ? -1 : 1;
        }

        public void add(double velocity, double seconds) {
            if (!Double.isFinite(velocity) || !(seconds > 0)) throw new IllegalArgumentException("Invalid metric sample");
            elapsed += seconds;
            double error = Math.abs(target - velocity);
            minimum = Math.min(minimum, velocity);
            maximumError = Math.max(maximumError, error);
            overshoot = Math.max(overshoot, (velocity - target) * stepDirection);
            squaredError += error * error * seconds;
            weight += seconds;
            if (elapsed >= observationSeconds * 0.8) {
                tailError += error * seconds;
                tailWeight += seconds;
            }
            if (error > target * 0.05) lastOutside = elapsed;
        }

        public PerformanceMetrics finish() {
            if (weight == 0) throw new IllegalStateException("No metric samples");
            boolean recovered = elapsed - lastOutside >= 0.3;
            return new PerformanceMetrics(Math.max(0, baseline - minimum), maximumError,
                    recovered ? lastOutside : elapsed, overshoot, Math.sqrt(squaredError / weight),
                    tailWeight > 0 ? tailError / tailWeight : maximumError, recovered);
        }
    }
}
