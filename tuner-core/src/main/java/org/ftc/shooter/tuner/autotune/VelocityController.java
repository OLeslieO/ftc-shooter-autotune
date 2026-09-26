package org.ftc.shooter.tuner.autotune;

public final class VelocityController {
    private double integral;
    private double previousVelocity;
    private double filteredAcceleration;
    private boolean initialized;

    public double update(Gains gains, double target, double targetAcceleration,
                         double velocity, double seconds, double battery, double maxPower) {
        if (!(seconds > 0 && seconds <= 0.2) || !Double.isFinite(velocity)
                || !Double.isFinite(target) || !Double.isFinite(targetAcceleration)
                || !Double.isFinite(battery) || battery <= 0) {
            reset();
            throw new IllegalArgumentException("Invalid controller timing or sensor reading");
        }
        if (target <= 0) {
            reset();
            return 0;
        }
        double acceleration = initialized ? (velocity - previousVelocity) / seconds : 0;
        filteredAcceleration += seconds / (0.06 + seconds) * (acceleration - filteredAcceleration);
        previousVelocity = velocity;
        initialized = true;
        double error = target - velocity;
        double voltageLimit = battery * maxPower;
        double integralCandidate = gains.kI > 0
                ? clamp(integral + error * seconds, -voltageLimit * 0.25 / gains.kI,
                        voltageLimit * 0.25 / gains.kI) : 0;
        double base = gains.kS * Math.signum(target) + gains.kV * target
                + gains.kA * targetAcceleration + gains.kP * error - gains.kD * filteredAcceleration;
        double candidate = base + gains.kI * integralCandidate;
        if ((candidate >= 0 && candidate <= voltageLimit)
                || (candidate > voltageLimit && error < 0) || (candidate < 0 && error > 0)) {
            integral = integralCandidate;
        }
        return clamp((base + gains.kI * integral) / battery, 0, maxPower);
    }

    public void reset() {
        integral = 0;
        filteredAcceleration = 0;
        initialized = false;
    }

    public static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
