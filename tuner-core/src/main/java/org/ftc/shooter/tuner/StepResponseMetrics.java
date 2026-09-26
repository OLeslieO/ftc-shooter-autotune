package org.ftc.shooter.tuner;

import java.util.List;

public final class StepResponseMetrics {
    private final double rmsError;
    private final double steadyStateError;
    private final double overshoot;
    private final double settlingTimeSeconds;
    private final double steadyStateNoise;

    private StepResponseMetrics(double rmsError, double steadyStateError, double overshoot,
                                double settlingTimeSeconds, double steadyStateNoise) {
        this.rmsError = rmsError;
        this.steadyStateError = steadyStateError;
        this.overshoot = overshoot;
        this.settlingTimeSeconds = settlingTimeSeconds;
        this.steadyStateNoise = steadyStateNoise;
    }

    public static StepResponseMetrics from(List<VelocitySample> samples, double settlingBand) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("At least one sample is required");
        }
        if (!(settlingBand > 0 && settlingBand < 1)) {
            throw new IllegalArgumentException("Settling band must be between zero and one");
        }

        double target = samples.get(samples.size() - 1).targetVelocity();
        double targetMagnitude = Math.max(Math.abs(target), 1.0);
        double squaredError = 0;
        double maximumOvershoot = 0;
        for (VelocitySample sample : samples) {
            double error = target - sample.measuredVelocity();
            squaredError += error * error;
            maximumOvershoot = Math.max(maximumOvershoot,
                    (sample.measuredVelocity() - target) / targetMagnitude);
        }

        int steadyStart = Math.max(0, samples.size() * 4 / 5);
        int steadyCount = samples.size() - steadyStart;
        double steadyError = 0;
        double steadyVelocity = 0;
        for (int index = steadyStart; index < samples.size(); index++) {
            steadyError += target - samples.get(index).measuredVelocity();
            steadyVelocity += samples.get(index).measuredVelocity();
        }
        steadyError /= steadyCount;
        steadyVelocity /= steadyCount;

        double noiseSquared = 0;
        for (int index = steadyStart; index < samples.size(); index++) {
            double deviation = samples.get(index).measuredVelocity() - steadyVelocity;
            noiseSquared += deviation * deviation;
        }

        double tolerance = targetMagnitude * settlingBand;
        double settlingTime = elapsedSeconds(samples);
        for (int start = 0; start < samples.size(); start++) {
            boolean settled = true;
            for (int index = start; index < samples.size(); index++) {
                if (Math.abs(target - samples.get(index).measuredVelocity()) > tolerance) {
                    settled = false;
                    break;
                }
            }
            if (settled) {
                settlingTime = secondsBetween(samples.get(0), samples.get(start));
                break;
            }
        }

        return new StepResponseMetrics(
                Math.sqrt(squaredError / samples.size()) / targetMagnitude,
                Math.abs(steadyError) / targetMagnitude,
                Math.max(0, maximumOvershoot),
                settlingTime,
                Math.sqrt(noiseSquared / steadyCount) / targetMagnitude);
    }

    private static double elapsedSeconds(List<VelocitySample> samples) {
        return secondsBetween(samples.get(0), samples.get(samples.size() - 1));
    }

    private static double secondsBetween(VelocitySample first, VelocitySample second) {
        return Math.max(0, second.timeNanos() - first.timeNanos()) / 1_000_000_000.0;
    }

    public double score(double settlingWeight, double overshootWeight,
                       double steadyStateWeight, double noiseWeight) {
        return rmsError + settlingWeight * settlingTimeSeconds + overshootWeight * overshoot
                + steadyStateWeight * steadyStateError + noiseWeight * steadyStateNoise;
    }

    public double rmsError() { return rmsError; }
    public double steadyStateError() { return steadyStateError; }
    public double overshoot() { return overshoot; }
    public double settlingTimeSeconds() { return settlingTimeSeconds; }
    public double steadyStateNoise() { return steadyStateNoise; }
}
