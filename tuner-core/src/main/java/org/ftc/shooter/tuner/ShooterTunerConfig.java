package org.ftc.shooter.tuner;

import java.util.Arrays;

public final class ShooterTunerConfig {
    private final double[] targetVelocities;
    private final PidfCoefficients seed;
    private final double pStep;
    private final double iStep;
    private final double dStep;
    private final double fStep;
    private final int candidatesEachSide;
    private final double pMaximum;
    private final double iMaximum;
    private final double dMaximum;
    private final double fMaximum;
    private final double settlingBand;
    private final double settlingWeight;
    private final double overshootWeight;
    private final double steadyStateWeight;
    private final double noiseWeight;
    private final int repeats;

    private ShooterTunerConfig(Builder builder) {
        targetVelocities = builder.targetVelocities.clone();
        seed = builder.seed;
        pStep = builder.pStep;
        iStep = builder.iStep;
        dStep = builder.dStep;
        fStep = builder.fStep;
        candidatesEachSide = builder.candidatesEachSide;
        pMaximum = builder.pMaximum;
        iMaximum = builder.iMaximum;
        dMaximum = builder.dMaximum;
        fMaximum = builder.fMaximum;
        settlingBand = builder.settlingBand;
        settlingWeight = builder.settlingWeight;
        overshootWeight = builder.overshootWeight;
        steadyStateWeight = builder.steadyStateWeight;
        noiseWeight = builder.noiseWeight;
        repeats = builder.repeats;
    }

    public static Builder builder() { return new Builder(); }
    public double[] targetVelocities() { return targetVelocities.clone(); }
    public PidfCoefficients seed() { return seed; }
    public double pStep() { return pStep; }
    public double iStep() { return iStep; }
    public double dStep() { return dStep; }
    public double fStep() { return fStep; }
    public int candidatesEachSide() { return candidatesEachSide; }
    public double pMaximum() { return pMaximum; }
    public double iMaximum() { return iMaximum; }
    public double dMaximum() { return dMaximum; }
    public double fMaximum() { return fMaximum; }
    public double settlingBand() { return settlingBand; }
    public double settlingWeight() { return settlingWeight; }
    public double overshootWeight() { return overshootWeight; }
    public double steadyStateWeight() { return steadyStateWeight; }
    public double noiseWeight() { return noiseWeight; }
    public int repeats() { return repeats; }

    public static final class Builder {
        private double[] targetVelocities = {500, 1000, 1500};
        private PidfCoefficients seed = new PidfCoefficients(0, 0, 0, 12);
        private double pStep = 0.5;
        private double iStep = 0.1;
        private double dStep = 0.05;
        private double fStep = 2;
        private int candidatesEachSide = 5;
        private double pMaximum = 100;
        private double iMaximum = 20;
        private double dMaximum = 10;
        private double fMaximum = 100;
        private double settlingBand = 0.05;
        private double settlingWeight = 0.05;
        private double overshootWeight = 2;
        private double steadyStateWeight = 2;
        private double noiseWeight = 1;
        private int repeats = 2;

        public Builder targetVelocities(double... values) {
            if (values == null || values.length == 0) {
                throw new IllegalArgumentException("At least one target velocity is required");
            }
            targetVelocities = values.clone();
            return this;
        }

        public Builder seed(PidfCoefficients value) { seed = value; return this; }
        public Builder pStep(double value) { pStep = value; return this; }
        public Builder iStep(double value) { iStep = value; return this; }
        public Builder dStep(double value) { dStep = value; return this; }
        public Builder fStep(double value) { fStep = value; return this; }
        public Builder candidatesEachSide(int value) { candidatesEachSide = value; return this; }
        public Builder pMaximum(double value) { pMaximum = value; return this; }
        public Builder iMaximum(double value) { iMaximum = value; return this; }
        public Builder dMaximum(double value) { dMaximum = value; return this; }
        public Builder fMaximum(double value) { fMaximum = value; return this; }
        public Builder settlingBand(double value) { settlingBand = value; return this; }
        public Builder settlingWeight(double value) { settlingWeight = value; return this; }
        public Builder overshootWeight(double value) { overshootWeight = value; return this; }
        public Builder steadyStateWeight(double value) { steadyStateWeight = value; return this; }
        public Builder noiseWeight(double value) { noiseWeight = value; return this; }
        public Builder repeats(int value) { repeats = value; return this; }

        public ShooterTunerConfig build() {
            if (seed == null || Arrays.stream(targetVelocities).anyMatch(value -> !Double.isFinite(value) || value <= 0)) {
                throw new IllegalArgumentException("Target velocities and seed must be valid");
            }
            if (candidatesEachSide < 1 || repeats < 1) {
                throw new IllegalArgumentException("Candidate count and repeats must be positive");
            }
            if (pStep <= 0 || iStep <= 0 || dStep <= 0 || fStep <= 0) {
                throw new IllegalArgumentException("Search steps must be positive");
            }
            if (pMaximum <= 0 || iMaximum <= 0 || dMaximum <= 0 || fMaximum <= 0) {
                throw new IllegalArgumentException("Search limits must be positive");
            }
            if (!(settlingBand > 0 && settlingBand < 1)) {
                throw new IllegalArgumentException("Settling band must be between zero and one");
            }
            if (settlingWeight < 0 || overshootWeight < 0 || steadyStateWeight < 0 || noiseWeight < 0) {
                throw new IllegalArgumentException("Metric weights must be non-negative");
            }
            return new ShooterTunerConfig(this);
        }
    }
}
