package org.ftc.shooter.tuner.autotune;

public final class ShooterConfig {
    public boolean dual = true;
    public String shooter = "shooterUp";
    public boolean shooterReversed = true;
    public String secondShooter = "shooterDown";
    public boolean secondReversed = false;
    public String preshooter = "preShooter";
    public boolean preshooterReversed = false;
    public boolean preshooterVelocityMode = false;
    public double preshooterDemand = 0.6;
    public double targetVelocity = 1440;
    public double maxVelocity = 2400;
    public double maxPreshooterVelocity = 2500;
    public double maxPower = 0.85;
    public double maxCurrentAmps = 8;
    public double minBatteryVolts = 10.5;
    public double targetAcceleration = 3000;
    public double feedSeconds = 0.2;
    public double recoverySeconds = 2.5;
    public int shots = 3;
    public double maxRunSeconds = 300;

    public void validate() {
        name(shooter);
        name(preshooter);
        if (dual) name(secondShooter);
        if (shooter.equals(preshooter) || (dual && (shooter.equals(secondShooter)
                || secondShooter.equals(preshooter)))) {
            throw new IllegalArgumentException("Each motor must have a distinct hardware name");
        }
        range(maxVelocity, 100, 30000, "Maximum shooter velocity");
        range(targetVelocity, 100, maxVelocity / 1.15, "Target velocity (leave overspeed headroom)");
        range(maxPreshooterVelocity, 100, 30000, "Maximum preshooter velocity");
        range(preshooterDemand, 0.01, preshooterVelocityMode ? maxPreshooterVelocity : 1,
                "Preshooter demand");
        range(maxPower, 0.15, 1, "Maximum power");
        range(maxCurrentAmps, 0.5, 20, "Maximum current");
        range(minBatteryVolts, 9, 14, "Minimum battery voltage");
        range(targetAcceleration, 100, 20000, "Target acceleration");
        range(feedSeconds, 0.05, 0.6, "Feed pulse seconds");
        range(recoverySeconds, 1, 5, "Shot observation seconds");
        range(shots, 2, 8, "Shots per candidate");
        range(maxRunSeconds, 30, 600, "Maximum session seconds");
    }

    private static void name(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 80) {
            throw new IllegalArgumentException("Motor names must contain 1–80 characters");
        }
    }

    public static void range(double value, double minimum, double maximum, String name) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
