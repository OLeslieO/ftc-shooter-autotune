package org.ftc.shooter.tuner;

public final class VelocitySample {
    private final long timeNanos;
    private final double targetVelocity;
    private final double measuredVelocity;

    public VelocitySample(long timeNanos, double targetVelocity, double measuredVelocity) {
        if (timeNanos < 0 || !Double.isFinite(targetVelocity) || !Double.isFinite(measuredVelocity)) {
            throw new IllegalArgumentException("Invalid velocity sample");
        }
        this.timeNanos = timeNanos;
        this.targetVelocity = targetVelocity;
        this.measuredVelocity = measuredVelocity;
    }

    public long timeNanos() { return timeNanos; }
    public double targetVelocity() { return targetVelocity; }
    public double measuredVelocity() { return measuredVelocity; }
}
