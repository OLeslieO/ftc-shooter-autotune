package org.ftc.shooter.tuner.autotune;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Output-error identification of the flywheel model {@code V = kS + kV·v + kA·a}.
 *
 * <p>The applied voltage is passed through a first-order filter with time constant {@code τ = kA / kV}
 * and the measured velocity is regressed on that filtered input, so no numerical differentiation of
 * the noisy encoder velocity is needed. {@code τ} is found by a golden-section search on the residual.
 * Encoder noise therefore only appears in the regression target, which keeps the fit unbiased.
 */
public final class FeedforwardTuner {
    private static final double MIN_TAU = 0.03;
    private static final double MAX_TAU = 30;
    private final List<List<double[]>> series = new ArrayList<>();
    private double[] initialVelocity = new double[0];
    public double fitQuality;
    public double timeConstant;

    /** Records the velocities measured just before the first voltage was applied. */
    public void begin(double[] velocities) {
        initialVelocity = velocities.clone();
        series.clear();
    }

    /** Adds one control tick: the velocity measured at its end and the voltage applied during it. */
    public void add(int motor, double velocity, double appliedVoltage, double seconds) {
        if (!Double.isFinite(velocity) || !Double.isFinite(appliedVoltage) || !(seconds > 0)) return;
        while (series.size() <= motor) series.add(new ArrayList<>());
        series.get(motor).add(new double[]{seconds, velocity, appliedVoltage});
    }

    public Gains fit() {
        int samples = 0;
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (List<double[]> motor : series) {
            samples += motor.size();
            for (double[] sample : motor) {
                minimum = Math.min(minimum, sample[1]);
                maximum = Math.max(maximum, sample[1]);
            }
        }
        if (samples < 100) throw new IllegalStateException("Insufficient identification data");
        if (maximum - minimum < 200) throw new IllegalStateException("Identification lacks excitation");
        double low = Math.log(MIN_TAU);
        double high = Math.log(MAX_TAU);
        double ratio = (Math.sqrt(5) - 1) / 2;
        double first = high - ratio * (high - low);
        double second = low + ratio * (high - low);
        Solution firstSolution = solve(Math.exp(first));
        Solution secondSolution = solve(Math.exp(second));
        for (int iteration = 0; iteration < 60; iteration++) {
            if (firstSolution.residual < secondSolution.residual) {
                high = second;
                second = first;
                secondSolution = firstSolution;
                first = high - ratio * (high - low);
                firstSolution = solve(Math.exp(first));
            } else {
                low = first;
                first = second;
                firstSolution = secondSolution;
                second = low + ratio * (high - low);
                secondSolution = solve(Math.exp(second));
            }
        }
        Solution best = firstSolution.residual < secondSolution.residual ? firstSolution : secondSolution;
        fitQuality = 1 - best.residual / Math.max(best.variance, 1e-9);
        timeConstant = best.tau;
        if (best.kS < -0.4 || best.kS > 4 || best.kV <= 0 || best.tau <= MIN_TAU * 1.01 || best.tau >= MAX_TAU * 0.99) {
            throw new IllegalStateException(String.format(Locale.US,
                    "Nonphysical feedforward fit (kS=%.2f V, kV=%.5f, τ=%.2f s); check encoders, directions and power range",
                    best.kS, best.kV, best.tau));
        }
        if (fitQuality < 0.8) {
            throw new IllegalStateException(String.format(Locale.US,
                    "Poor feedforward fit; R²=%.2f < 0.8 (kS=%.2f V, kV=%.5f, τ=%.2f s); check encoder velocity readings",
                    fitQuality, best.kS, best.kV, best.tau));
        }
        return new Gains(Math.max(0, best.kS), best.kV, best.kV * best.tau, 0, 0, 0);
    }

    private static final class Solution {
        double tau;
        double kS;
        double kV;
        double residual;
        double variance;
    }

    /**
     * For a fixed τ the model is linear: {@code v(t) = v0·e^(-t/τ) + (1/kV)·[LP(V)(t) − kS·(1 − e^(-t/τ))]}.
     * Regress {@code v − v0·e^(-t/τ)} on {@code x1 = LP(V)} and {@code x2 = 1 − e^(-t/τ)}.
     */
    private Solution solve(double tau) {
        double s11 = 0, s12 = 0, s22 = 0, s1y = 0, s2y = 0, sum = 0;
        int count = 0;
        for (int motor = 0; motor < series.size(); motor++) {
            double filtered = 0;
            double time = 0;
            double start = motor < initialVelocity.length ? initialVelocity[motor] : 0;
            for (double[] sample : series.get(motor)) {
                double decay = Math.exp(-sample[0] / tau);
                filtered = filtered * decay + sample[2] * (1 - decay);
                time += sample[0];
                double x2 = 1 - Math.exp(-time / tau);
                double y = sample[1] - start * Math.exp(-time / tau);
                s11 += filtered * filtered;
                s12 += filtered * x2;
                s22 += x2 * x2;
                s1y += filtered * y;
                s2y += x2 * y;
                sum += sample[1];
                count++;
            }
        }
        double determinant = s11 * s22 - s12 * s12;
        if (Math.abs(determinant) < 1e-9) throw new IllegalStateException("Identification lacks excitation");
        double b1 = (s1y * s22 - s2y * s12) / determinant;
        double b2 = (s11 * s2y - s12 * s1y) / determinant;
        Solution solution = new Solution();
        solution.tau = tau;
        solution.kV = 1 / b1;
        solution.kS = -b2 / b1;
        double mean = sum / count;
        for (int motor = 0; motor < series.size(); motor++) {
            double filtered = 0;
            double time = 0;
            double start = motor < initialVelocity.length ? initialVelocity[motor] : 0;
            for (double[] sample : series.get(motor)) {
                double decay = Math.exp(-sample[0] / tau);
                filtered = filtered * decay + sample[2] * (1 - decay);
                time += sample[0];
                double predicted = start * Math.exp(-time / tau) + b1 * filtered + b2 * (1 - Math.exp(-time / tau));
                solution.residual += (sample[1] - predicted) * (sample[1] - predicted);
                solution.variance += (sample[1] - mean) * (sample[1] - mean);
            }
        }
        return solution;
    }
}
