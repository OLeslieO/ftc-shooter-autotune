package org.ftc.shooter.tuner.ftc;

import android.content.Context;
import android.content.SharedPreferences;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;

import org.ftc.shooter.tuner.autotune.AutoTuneManager;
import org.ftc.shooter.tuner.autotune.Gains;
import org.ftc.shooter.tuner.autotune.ShooterConfig;
import org.json.JSONObject;

public abstract class ShooterAutoTuneOpMode extends LinearOpMode {
    private AutoTuneManager manager;
    private SharedPreferences preferences;

    protected abstract Gains testGains();

    public void Configure(ShooterConfig config) {
        manager.configure(config);
    }

    public void PIDFTuner() {
        manager.startTuning(seconds());
    }

    public void Test() {
        manager.startTest(testGains(), seconds());
    }

    @Override
    public void runOpMode() throws InterruptedException {
        FtcShooterHardware hardware = new FtcShooterHardware(hardwareMap);
        manager = new AutoTuneManager(hardware);
        preferences = hardwareMap.appContext.getSharedPreferences("shooter-autotune-v1", Context.MODE_PRIVATE);
        String commandMessage = "";
        double lastPublish = 0;
        Gains saved = null;
        AutoTuneWebServer server;
        try {
            server = AutoTuneWebServer.open(hardwareMap.appContext.getAssets());
        } catch (Exception exception) {
            throw new IllegalStateException("Shooter AutoTune: " + exception.getMessage(), exception);
        }
        try {
            while (!isStopRequested()) {
                double now = seconds();
                if (server.takeStop()) {
                    manager.stop("Stopped from webpage");
                    commandMessage = "Stopped";
                } else {
                    AutoTuneWebServer.Command command = server.poll();
                    if (command != null) {
                        try {
                            if (System.nanoTime() - command.submittedNanos > 1_000_000_000L) throw new IllegalStateException("Expired command; retry");
                            if (command.action.equals("configure")) {
                                ShooterConfig config = AutoTuneJson.config(command.body);
                                Configure(config);
                                preferences.edit().putString("config", AutoTuneJson.object(config).toString()).apply();
                                saved = null;
                            } else {
                                if (!isStarted() || !server.browserAlive()) throw new IllegalStateException("Press DS Start and keep the webpage connected");
                                if (command.action.equals("direction")) manager.directionTest(command.body.getInt("motor"), now);
                                else if (command.action.equals("tune")) PIDFTuner();
                                else if (command.action.equals("loaded")) {
                                    if (!command.body.optBoolean("armed")) throw new IllegalStateException("Arm loaded firing first");
                                    manager.startLoaded(now);
                                } else if (command.action.equals("test")) Test();
                            }
                            commandMessage = "Accepted: " + command.action;
                        } catch (Exception exception) {
                            commandMessage = "Rejected: " + exception.getMessage();
                        }
                    }
                }
                manager.update(seconds(), isStarted() && !isStopRequested(), server.browserAlive(), gamepad1.right_bumper);
                if (manager.phase() == AutoTuneManager.Phase.FAULT) server.clearCommands();
                if (manager.result() != null && manager.result() != saved) {
                    saved = manager.result();
                    preferences.edit().putString("lastConstants", saved.javaConstants()).apply();
                }
                if (now - lastPublish >= 0.1) {
                    JSONObject snapshot = AutoTuneJson.snapshot(manager, isStarted(), hardware.feeding(), now, commandMessage);
                    snapshot.put("savedConfig", new JSONObject(preferences.getString("config", AutoTuneJson.object(new ShooterConfig()).toString())));
                    snapshot.put("constants", manager.result() == null ? "" : manager.result().javaConstants());
                    snapshot.put("previousConstants", preferences.getString("lastConstants", ""));
                    server.publish(snapshot.toString(), manager.result() == null ? "" : manager.result().javaConstants());
                    telemetry.addData("Web UI", "http://192.168.43.1:" + server.port + " (Control Hub)");
                    telemetry.addData("Phone RC", "http://192.168.49.1:" + server.port);
                    telemetry.addData("Phase", manager.phase());
                    telemetry.addData("Status", manager.message());
                    telemetry.addData("Command", commandMessage);
                    telemetry.addData("Battery", manager.battery());
                    telemetry.addData("Target ticks/s", manager.target());
                    telemetry.addData("Actual ticks/s", java.util.Arrays.toString(manager.velocities()));
                    telemetry.addData("Motor power", java.util.Arrays.toString(manager.powers()));
                    telemetry.update();
                    lastPublish = now;
                }
                sleep(20);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Shooter AutoTune: " + exception.getMessage(), exception);
        } finally {
            try { manager.stop("OpMode ended"); } finally { server.stop(); }
        }
    }

    private static double seconds() {
        return System.nanoTime() / 1_000_000_000.0;
    }
}
