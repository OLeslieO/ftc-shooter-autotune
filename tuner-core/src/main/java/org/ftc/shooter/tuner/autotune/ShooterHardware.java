package org.ftc.shooter.tuner.autotune;

public interface ShooterHardware {
    void configure(ShooterConfig config);
    double[] velocities();
    double[] currents();
    double batteryVoltage();
    double preshooterVelocity();
    void shooterPowers(double[] powers);
    void feed(boolean enabled);
    void directionPulse(int motorIndex, double power);
    void stop();
}
