package org.ftc.shooter.tuner.ftc;

import org.ftc.shooter.tuner.autotune.AutoTuneManager;
import org.ftc.shooter.tuner.autotune.Gains;
import org.ftc.shooter.tuner.autotune.ShooterConfig;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.Iterator;

public final class AutoTuneJson {
    private AutoTuneJson() { }

    public static ShooterConfig config(JSONObject data) throws JSONException {
        ShooterConfig config = new ShooterConfig();
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            try {
                Field field = ShooterConfig.class.getField(name);
                if (field.getType() == boolean.class) field.setBoolean(config, data.getBoolean(name));
                else if (field.getType() == String.class) field.set(config, data.getString(name));
                else if (field.getType() == int.class) {
                    double value = data.getDouble(name);
                    if (value != Math.rint(value)) throw new IllegalArgumentException("Expected an integer: " + name);
                    field.setInt(config, data.getInt(name));
                } else field.setDouble(config, data.getDouble(name));
            } catch (ReflectiveOperationException exception) {
                throw new IllegalArgumentException("Unknown configuration field: " + name, exception);
            }
        }
        config.validate();
        return config;
    }

    public static JSONObject object(Object value) throws JSONException {
        JSONObject result = new JSONObject();
        if (value == null) return result;
        for (Field field : value.getClass().getFields()) {
            try {
                Object member = field.get(value);
                if (member instanceof Double && !Double.isFinite((Double) member)) member = JSONObject.NULL;
                result.put(field.getName(), member);
            } catch (IllegalAccessException exception) {
                throw new IllegalArgumentException(exception);
            }
        }
        return result;
    }

    public static JSONObject snapshot(AutoTuneManager manager, boolean started, boolean feeding,
                                      double now, String commandMessage) throws JSONException {
        JSONObject state = new JSONObject();
        state.put("time", now);
        state.put("active", started);
        state.put("busy", manager.busy());
        state.put("phase", manager.phase().name());
        state.put("message", manager.message());
        state.put("commandMessage", commandMessage);
        state.put("config", object(manager.config()));
        state.put("gains", object(manager.gains()));
        state.put("validated", manager.result() != null);
        state.put("target", manager.target());
        state.put("velocities", numbers(manager.velocities()));
        state.put("powers", numbers(manager.powers()));
        state.put("currents", numbers(manager.currents()));
        state.put("battery", Double.isFinite(manager.battery()) ? manager.battery() : JSONObject.NULL);
        state.put("feeding", feeding);
        state.put("trials", new JSONArray(manager.trials()));
        JSONArray shots = new JSONArray();
        for (AutoTuneManager.ShotResult shot : manager.shots()) {
            JSONObject record = new JSONObject();
            record.put("candidate", shot.candidate);
            record.put("shot", shot.shot);
            record.put("phase", shot.phase);
            record.put("gains", object(shot.gains));
            JSONArray motors = new JSONArray();
            for (Object metrics : shot.motors) motors.put(object(metrics));
            record.put("motors", motors);
            shots.put(record);
        }
        state.put("shots", shots);
        return state;
    }

    private static JSONArray numbers(double[] values) {
        JSONArray result = new JSONArray();
        for (double value : values) result.put(Double.isFinite(value) ? Double.valueOf(value) : JSONObject.NULL);
        return result;
    }
}
