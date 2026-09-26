package org.ftc.shooter.tuner;

import java.util.Locale;

public final class PidfCoefficients {
    private final double p;
    private final double i;
    private final double d;
    private final double f;

    public PidfCoefficients(double p, double i, double d, double f) {
        if (!Double.isFinite(p) || !Double.isFinite(i) || !Double.isFinite(d) || !Double.isFinite(f)) {
            throw new IllegalArgumentException("PIDF values must be finite");
        }
        if (p < 0 || i < 0 || d < 0 || f < 0) {
            throw new IllegalArgumentException("PIDF values must be non-negative");
        }
        this.p = p;
        this.i = i;
        this.d = d;
        this.f = f;
    }

    public double p() { return p; }
    public double i() { return i; }
    public double d() { return d; }
    public double f() { return f; }

    public PidfCoefficients withP(double value) { return new PidfCoefficients(value, i, d, f); }
    public PidfCoefficients withI(double value) { return new PidfCoefficients(p, value, d, f); }
    public PidfCoefficients withD(double value) { return new PidfCoefficients(p, i, value, f); }
    public PidfCoefficients withF(double value) { return new PidfCoefficients(p, i, d, value); }

    public String toJavaLiteral() {
        return String.format(Locale.US, "%.10g, %.10g, %.10g, %.10g", p, i, d, f);
    }

    @Override
    public String toString() {
        return "PidfCoefficients{" + "p=" + p + ", i=" + i + ", d=" + d + ", f=" + f + '}';
    }
}
