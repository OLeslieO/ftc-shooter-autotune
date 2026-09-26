package org.ftc.shooter.tuner;

import java.util.List;

public final class ShooterTuner {
    public interface TrialRunner {
        List<VelocitySample> run(PidfCoefficients coefficients, double targetVelocity) throws Exception;
    }

    private final ShooterTunerConfig config;
    private final TrialRunner runner;

    public ShooterTuner(ShooterTunerConfig config, TrialRunner runner) {
        if (config == null || runner == null) {
            throw new IllegalArgumentException("Config and runner are required");
        }
        this.config = config;
        this.runner = runner;
    }

    public PidfTuningResult tune() throws Exception {
        PidfCoefficients best = config.seed();
        best = searchParameter(best, Parameter.F, config.fStep(), config.fMaximum());
        best = searchParameter(best, Parameter.P, config.pStep(), config.pMaximum());
        best = searchParameter(best, Parameter.D, config.dStep(), config.dMaximum());
        best = searchParameter(best, Parameter.I, config.iStep(), config.iMaximum());
        return new PidfTuningResult(best, score(best));
    }

    private PidfCoefficients searchParameter(PidfCoefficients current, Parameter parameter,
                                             double step, double maximum) throws Exception {
        PidfCoefficients best = current;
        double bestScore = score(best);
        for (int offset = -config.candidatesEachSide(); offset <= config.candidatesEachSide(); offset++) {
            double value = parameter.value(current) + offset * step;
            if (value < 0 || value > maximum) {
                continue;
            }
            PidfCoefficients candidate = parameter.withValue(current, value);
            double candidateScore = score(candidate);
            if (candidateScore < bestScore) {
                best = candidate;
                bestScore = candidateScore;
            }
        }
        return best;
    }

    private double score(PidfCoefficients coefficients) throws Exception {
        double total = 0;
        double[] targets = config.targetVelocities();
        for (double target : targets) {
            for (int repeat = 0; repeat < config.repeats(); repeat++) {
                List<VelocitySample> samples = runner.run(coefficients, target);
                StepResponseMetrics metrics = StepResponseMetrics.from(samples, config.settlingBand());
                total += metrics.score(config.settlingWeight(), config.overshootWeight(),
                        config.steadyStateWeight(), config.noiseWeight());
            }
        }
        return total / (targets.length * config.repeats());
    }

    private enum Parameter {
        P {
            @Override double value(PidfCoefficients coefficients) { return coefficients.p(); }
            @Override PidfCoefficients withValue(PidfCoefficients coefficients, double value) { return coefficients.withP(value); }
        },
        I {
            @Override double value(PidfCoefficients coefficients) { return coefficients.i(); }
            @Override PidfCoefficients withValue(PidfCoefficients coefficients, double value) { return coefficients.withI(value); }
        },
        D {
            @Override double value(PidfCoefficients coefficients) { return coefficients.d(); }
            @Override PidfCoefficients withValue(PidfCoefficients coefficients, double value) { return coefficients.withD(value); }
        },
        F {
            @Override double value(PidfCoefficients coefficients) { return coefficients.f(); }
            @Override PidfCoefficients withValue(PidfCoefficients coefficients, double value) { return coefficients.withF(value); }
        };

        abstract double value(PidfCoefficients coefficients);
        abstract PidfCoefficients withValue(PidfCoefficients coefficients, double value);
    }
}
