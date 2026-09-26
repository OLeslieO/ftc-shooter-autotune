package org.ftc.shooter.tuner.ftc;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;

import org.ftc.shooter.tuner.PidfCoefficients;
import org.ftc.shooter.tuner.ShooterTuner;
import org.ftc.shooter.tuner.VelocitySample;

import java.util.ArrayList;
import java.util.List;

public final class DcMotorExTrialRunner implements ShooterTuner.TrialRunner {
    private final LinearOpMode opMode;
    private final DcMotorEx motor;
    private final long settleMillis;
    private final long recordMillis;
    private final long sampleMillis;
    private final double maximumVelocity;

    public DcMotorExTrialRunner(LinearOpMode opMode, DcMotorEx motor,
                                long settleMillis, long recordMillis,
                                long sampleMillis, double maximumVelocity) {
        if (opMode == null || motor == null || settleMillis < 0 || recordMillis <= 0
                || sampleMillis <= 0 || maximumVelocity <= 0) {
            throw new IllegalArgumentException("Invalid trial runner configuration");
        }
        this.opMode = opMode;
        this.motor = motor;
        this.settleMillis = settleMillis;
        this.recordMillis = recordMillis;
        this.sampleMillis = sampleMillis;
        this.maximumVelocity = maximumVelocity;
    }

    @Override
    public List<VelocitySample> run(PidfCoefficients coefficients, double targetVelocity)
            throws InterruptedException {
        motor.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
        motor.setVelocityPIDFCoefficients(coefficients.p(), coefficients.i(), coefficients.d(), coefficients.f());
        motor.setVelocity(targetVelocity);

        try {
            opMode.sleep(settleMillis);
            long startNanos = System.nanoTime();
            long endNanos = startNanos + recordMillis * 1_000_000L;
            List<VelocitySample> samples = new ArrayList<>();
            while (opMode.opModeIsActive() && System.nanoTime() < endNanos) {
                long now = System.nanoTime();
                double measuredVelocity = motor.getVelocity();
                if (Math.abs(measuredVelocity) > maximumVelocity) {
                    throw new IllegalStateException("Shooter exceeded configured velocity limit");
                }
                samples.add(new VelocitySample(now - startNanos, targetVelocity, measuredVelocity));
                opMode.sleep(sampleMillis);
            }
            if (samples.isEmpty()) {
                throw new IllegalStateException("No velocity samples were collected");
            }
            return samples;
        } finally {
            motor.setVelocity(0);
            motor.setPower(0);
        }
    }
}
