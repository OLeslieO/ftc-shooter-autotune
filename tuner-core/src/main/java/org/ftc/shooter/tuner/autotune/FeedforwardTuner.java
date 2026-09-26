package org.ftc.shooter.tuner.autotune;

import java.util.ArrayList;
import java.util.List;

public final class FeedforwardTuner {
    private final List<double[]> rows = new ArrayList<>();
    public double fitQuality;

    public void add(double velocity, double acceleration, double appliedVoltage) {
        if (velocity > 50 && Double.isFinite(acceleration) && Double.isFinite(appliedVoltage)) {
            rows.add(new double[]{1, velocity / 1000, acceleration / 1000, appliedVoltage});
        }
    }

    public Gains fit() {
        if (rows.size() < 30) throw new IllegalStateException("Insufficient identification data");
        double[][] normal = new double[3][4];
        for (double[] row : rows) {
            for (int column = 0; column < 3; column++) {
                for (int other = 0; other < 4; other++) normal[column][other] += row[column] * row[other];
            }
        }
        for (int pivot = 0; pivot < 3; pivot++) {
            int best = pivot;
            for (int candidate = pivot + 1; candidate < 3; candidate++) {
                if (Math.abs(normal[candidate][pivot]) > Math.abs(normal[best][pivot])) best = candidate;
            }
            double[] swap = normal[best];
            normal[best] = normal[pivot];
            normal[pivot] = swap;
            double divisor = normal[pivot][pivot];
            if (Math.abs(divisor) < 1e-7) throw new IllegalStateException("Identification lacks excitation");
            for (int column = pivot; column < 4; column++) normal[pivot][column] /= divisor;
            for (int row = 0; row < 3; row++) {
                if (row == pivot) continue;
                double factor = normal[row][pivot];
                for (int column = pivot; column < 4; column++) normal[row][column] -= factor * normal[pivot][column];
            }
        }
        double staticGain = normal[0][3];
        double velocityGain = normal[1][3] / 1000;
        double accelerationGain = normal[2][3] / 1000;
        if (staticGain < -0.4 || staticGain > 4 || velocityGain <= 0 || accelerationGain <= 0) {
            throw new IllegalStateException("Nonphysical feedforward fit; check encoders, directions and power range");
        }
        staticGain = Math.max(0, staticGain);
        double average = 0;
        for (double[] row : rows) average += row[3] / rows.size();
        double residual = 0;
        double variation = 0;
        for (double[] row : rows) {
            double error = row[3] - staticGain - velocityGain * row[1] * 1000
                    - accelerationGain * row[2] * 1000;
            residual += error * error;
            variation += (row[3] - average) * (row[3] - average);
        }
        fitQuality = 1 - residual / Math.max(variation, 1e-9);
        if (fitQuality < 0.8) throw new IllegalStateException("Poor feedforward fit; R² < 0.8");
        return new Gains(staticGain, velocityGain, accelerationGain, 0, 0, 0);
    }
}
