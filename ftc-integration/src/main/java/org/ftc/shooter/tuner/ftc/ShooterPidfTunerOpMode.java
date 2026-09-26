package org.ftc.shooter.tuner.ftc;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.Disabled;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotorEx;

import org.ftc.shooter.tuner.PidfCoefficients;
import org.ftc.shooter.tuner.PidfTuningResult;
import org.ftc.shooter.tuner.ShooterTuner;
import org.ftc.shooter.tuner.ShooterTunerConfig;

@TeleOp(name = "Shooter PIDF Auto Tuner", group = "Tuning")
@Disabled
public final class ShooterPidfTunerOpMode extends LinearOpMode {
    public static String MOTOR_NAME = "preShooter";
    public static double TARGET_LOW = 500;
    public static double TARGET_MIDDLE = 1000;
    public static double TARGET_HIGH = 1500;
    public static double SEED_F = 12;
    public static double MAXIMUM_VELOCITY = 2500;
    public static long SETTLE_MILLISECONDS = 800;
    public static long RECORD_MILLISECONDS = 1200;
    public static long SAMPLE_MILLISECONDS = 20;

    @Override
    public void runOpMode() throws InterruptedException {
        DcMotorEx motor = hardwareMap.get(DcMotorEx.class, MOTOR_NAME);
        ShooterTunerConfig config = ShooterTunerConfig.builder()
                .targetVelocities(TARGET_LOW, TARGET_MIDDLE, TARGET_HIGH)
                .seed(new PidfCoefficients(0, 0, 0, SEED_F))
                .build();

        telemetry.addData("Motor", MOTOR_NAME);
        telemetry.addData("Targets", "%.0f, %.0f, %.0f", TARGET_LOW, TARGET_MIDDLE, TARGET_HIGH);
        telemetry.addLine("Remove game pieces and keep clear of the shooter.");
        telemetry.addLine("Press start to begin the automatic search.");
        telemetry.update();
        waitForStart();

        DcMotorExTrialRunner runner = new DcMotorExTrialRunner(
                this, motor, SETTLE_MILLISECONDS, RECORD_MILLISECONDS,
                SAMPLE_MILLISECONDS, MAXIMUM_VELOCITY);
        PidfTuningResult result;
        try {
            result = new ShooterTuner(config, runner).tune();
        } catch (InterruptedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Shooter PIDF tuning failed", exception);
        }

        telemetry.addData("PIDF", result.coefficients());
        telemetry.addData("Score", result.score());
        telemetry.addLine(result.toJava("motor"));
        telemetry.update();
        sleep(10000);
    }
}
