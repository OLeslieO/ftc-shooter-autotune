package org.ftc.shooter.tuner;

public final class PidfTuningResult {
    private final PidfCoefficients coefficients;
    private final double score;

    public PidfTuningResult(PidfCoefficients coefficients, double score) {
        this.coefficients = coefficients;
        this.score = score;
    }

    public PidfCoefficients coefficients() { return coefficients; }
    public double score() { return score; }

    public String toJava(String motorExpression) {
        return motorExpression + ".setVelocityPIDFCoefficients(" + coefficients.toJavaLiteral() + ");";
    }
}
