package org.ftc.shooter.tuner.autotune;

import java.util.ArrayList;
import java.util.List;

public final class LoadedShotTest {
    private final PerformanceMetrics.Recorder[] recorders;
    private final double minimumDrop;

    public LoadedShotTest(ShooterConfig config, double[] baseline) {
        minimumDrop = Math.max(10, config.targetVelocity * 0.01);
        recorders = new PerformanceMetrics.Recorder[baseline.length];
        for (int motor = 0; motor < baseline.length; motor++) {
            recorders[motor] = new PerformanceMetrics.Recorder(config.targetVelocity,
                    baseline[motor], config.recoverySeconds);
        }
    }

    public void sample(double[] velocities, double seconds) {
        for (int motor = 0; motor < recorders.length; motor++) recorders[motor].add(velocities[motor], seconds);
    }

    public List<PerformanceMetrics> finish() {
        List<PerformanceMetrics> metrics = new ArrayList<>();
        boolean detected = false;
        for (PerformanceMetrics.Recorder recorder : recorders) {
            PerformanceMetrics result = recorder.finish();
            detected |= result.velocityDrop >= minimumDrop;
            metrics.add(result);
        }
        if (!detected) throw new IllegalStateException("No shot load detected: refill and check preshooter feed pulse");
        return metrics;
    }
}
