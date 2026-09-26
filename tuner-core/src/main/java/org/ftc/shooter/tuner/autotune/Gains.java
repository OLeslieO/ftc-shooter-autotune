package org.ftc.shooter.tuner.autotune;

import java.util.Locale;

public final class Gains {
    public final double kS;
    public final double kV;
    public final double kA;
    public final double kP;
    public final double kI;
    public final double kD;

    public Gains(double staticGain, double velocityGain, double accelerationGain,
                 double proportional, double integral, double derivative) {
        double[] values = {staticGain, velocityGain, accelerationGain, proportional, integral, derivative};
        for (double value : values) {
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid gains");
        }
        kS = staticGain;
        kV = velocityGain;
        kA = accelerationGain;
        kP = proportional;
        kI = integral;
        kD = derivative;
    }

    public Gains pid(double proportional, double integral, double derivative) {
        return new Gains(kS, kV, kA, proportional, integral, derivative);
    }

    public String javaConstants() {
        String[] names = {"KS", "KV", "KA", "KP", "KI", "KD"};
        double[] values = {kS, kV, kA, kP, kI, kD};
        StringBuilder source = new StringBuilder();
        for (int index = 0; index < names.length; index++) {
            source.append(String.format(Locale.US, "public static double SHOOTER_%s = %.10g;%n",
                    names[index], values[index]));
        }
        return source.toString();
    }
}
