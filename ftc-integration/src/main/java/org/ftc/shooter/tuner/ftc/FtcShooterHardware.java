package org.ftc.shooter.tuner.ftc;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.VoltageSensor;

import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;
import org.ftc.shooter.tuner.autotune.ShooterConfig;
import org.ftc.shooter.tuner.autotune.ShooterHardware;

public final class FtcShooterHardware implements ShooterHardware {
    private final HardwareMap hardwareMap;
    private DcMotorEx[] shooters = new DcMotorEx[0];
    private DcMotorEx preshooter;
    private ShooterConfig config;
    private boolean feeding;

    public FtcShooterHardware(HardwareMap hardwareMap) {
        this.hardwareMap = hardwareMap;
    }

    @Override
    public void configure(ShooterConfig configuration) {
        configuration.validate();
        stop();
        DcMotorEx primary = hardwareMap.get(DcMotorEx.class, configuration.shooter);
        DcMotorEx secondary = configuration.dual ? hardwareMap.get(DcMotorEx.class, configuration.secondShooter) : null;
        DcMotorEx feeder = hardwareMap.get(DcMotorEx.class, configuration.preshooter);
        if (primary == secondary || primary == feeder || secondary == feeder) {
            throw new IllegalArgumentException("Motor aliases resolve to the same physical device");
        }
        shooters = configuration.dual ? new DcMotorEx[]{primary, secondary} : new DcMotorEx[]{primary};
        preshooter = feeder;
        config = configuration;
        stop();
        primary.setDirection(direction(config.shooterReversed));
        if (secondary != null) secondary.setDirection(direction(config.secondReversed));
        preshooter.setDirection(direction(config.preshooterReversed));
        for (DcMotorEx shooter : shooters) {
            shooter.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
            shooter.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);
        }
        preshooter.setMode(config.preshooterVelocityMode ? DcMotor.RunMode.RUN_USING_ENCODER : DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        preshooter.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
    }

    private DcMotorSimple.Direction direction(boolean reversed) {
        return reversed ? DcMotorSimple.Direction.REVERSE : DcMotorSimple.Direction.FORWARD;
    }

    @Override
    public double[] velocities() {
        double[] values = new double[shooters.length];
        for (int motor = 0; motor < shooters.length; motor++) values[motor] = shooters[motor].getVelocity();
        return values;
    }

    @Override
    public double[] currents() {
        double[] values = new double[shooters.length + 1];
        for (int motor = 0; motor < shooters.length; motor++) values[motor] = shooters[motor].getCurrent(CurrentUnit.AMPS);
        values[shooters.length] = preshooter.getCurrent(CurrentUnit.AMPS);
        return values;
    }

    @Override
    public double batteryVoltage() {
        double voltage = Double.POSITIVE_INFINITY;
        for (VoltageSensor sensor : hardwareMap.voltageSensor) {
            double sample = sensor.getVoltage();
            if (sample > 0) voltage = Math.min(voltage, sample);
        }
        return voltage;
    }

    @Override
    public double preshooterVelocity() {
        return preshooter.getVelocity();
    }

    @Override
    public void shooterPowers(double[] powers) {
        if (powers.length != shooters.length) throw new IllegalArgumentException("Motor count mismatch");
        for (int motor = 0; motor < shooters.length; motor++) {
            if (!Double.isFinite(powers[motor]) || powers[motor] < 0 || powers[motor] > 1) {
                throw new IllegalArgumentException("Power outside 0-1 range");
            }
            shooters[motor].setPower(powers[motor]);
        }
    }

    @Override
    public void feed(boolean enabled) {
        feeding = enabled;
        if (config.preshooterVelocityMode) preshooter.setVelocity(enabled ? config.preshooterDemand : 0);
        else preshooter.setPower(enabled ? config.preshooterDemand : 0);
    }

    @Override
    public void directionPulse(int motorIndex, double power) {
        stop();
        DcMotorEx selected = motorIndex < shooters.length ? shooters[motorIndex] : preshooter;
        selected.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        selected.setPower(Math.min(0.12, power));
    }

    @Override
    public void stop() {
        RuntimeException failure = null;
        for (DcMotorEx shooter : shooters) {
            try { shooter.setPower(0); } catch (RuntimeException exception) { failure = exception; }
        }
        if (preshooter != null) {
            try {
                preshooter.setPower(0);
                if (config != null && config.preshooterVelocityMode) {
                    preshooter.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
                    preshooter.setVelocity(0);
                }
            } catch (RuntimeException exception) { failure = exception; }
        }
        feeding = false;
        if (failure != null) throw failure;
    }

    public boolean feeding() { return feeding; }
}
