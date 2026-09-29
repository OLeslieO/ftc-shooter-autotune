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
    public double targetVelocity = 1600;
    public double minBatteryVolts = 10.5;
    public double targetAcceleration = 3000;
    public double recoverySeconds = 2.5;
    public int shots = 3;

    public void validate() {
        name(shooter);
        name(preshooter);
        if (dual) name(secondShooter);
        if (shooter.equals(preshooter) || (dual && (shooter.equals(secondShooter)
                || secondShooter.equals(preshooter)))) {
            throw new IllegalArgumentException("Each motor must have a distinct hardware name");
        }
        range(targetVelocity, 100, 30000, "Target velocity");
        range(preshooterDemand, 0.01, preshooterVelocityMode ? 30000 : 1, "Preshooter demand");
        range(minBatteryVolts, 9, 14, "Minimum battery voltage");
        range(targetAcceleration, 100, 20000, "Target acceleration");
        range(recoverySeconds, 1, 5, "Shot observation seconds");
        range(shots, 2, 8, "Shots per candidate");
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
