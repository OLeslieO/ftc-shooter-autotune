package org.ftc.shooter.tuner.autotune;

import java.util.Arrays;
import java.util.List;

public final class PIDTuner {
    private PIDTuner() { }

    public static List<Gains> proportionalCandidates(Gains model) {
        double proportional = proportionalScale(model);
        return Arrays.asList(model, model.pid(proportional * 0.5, 0, 0),
                model.pid(proportional, 0, 0), model.pid(Math.min(0.05, proportional * 1.5), 0, 0));
    }

    public static List<Gains> derivativeCandidates(Gains best) {
        return Arrays.asList(best, best.pid(best.kP, 0, best.kA * 0.05),
                best.pid(best.kP, 0, best.kA * 0.1));
    }

    public static List<Gains> integralCandidates(Gains best) {
        double integral = Math.min(0.01, Math.max(best.kP, proportionalScale(best) * 0.25)
                / Math.max(1, 4 * best.kA / best.kV));
        return Arrays.asList(best, best.pid(best.kP, integral * 0.5, best.kD),
                best.pid(best.kP, integral, best.kD));
    }

    public static List<Gains> loadedCandidates(Gains best) {
        if (best.kP == 0) {
            double proportional = proportionalScale(best);
            return Arrays.asList(best, best.pid(proportional * 0.5, best.kI, best.kD),
                    best.pid(proportional, best.kI, best.kD));
        }
        return Arrays.asList(best, best.pid(Math.min(0.05, best.kP * 1.2), best.kI, best.kD),
                best.pid(best.kP * 0.8, best.kI, best.kD));
    }

    private static double proportionalScale(Gains model) {
        return Math.min(0.05, model.kA / Math.max(0.15, model.kA / model.kV * 0.7));
    }
}
