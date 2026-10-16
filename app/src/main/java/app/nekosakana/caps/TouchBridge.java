package app.nekosakana.caps;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/** Runs as shell/root through app_process; keeps one gesture's downTime intact. */
public final class TouchBridge {
    public static void main(String[] args) throws Exception {
        Class<?> managerClass;
        try { managerClass = Class.forName("android.hardware.input.InputManagerGlobal"); }
        catch (ClassNotFoundException e) { managerClass = Class.forName("android.hardware.input.InputManager"); }
        Object manager = managerClass.getDeclaredMethod("getInstance").invoke(null);
        Method inject = managerClass.getMethod("injectInputEvent", InputEvent.class, int.class);
        System.out.println("ready");
        System.out.flush();
        long downTime = 0;
        float lastX = 0, lastY = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(" ");
                int action = Integer.parseInt(fields[0]);
                float x = Float.parseFloat(fields[1]), y = Float.parseFloat(fields[2]);
                long now = SystemClock.uptimeMillis();
                if (action == MotionEvent.ACTION_DOWN) downTime = now;
                if (downTime == 0) { System.out.println("error:no-down"); System.out.flush(); continue; }
                MotionEvent event = MotionEvent.obtain(downTime, now, action, x, y, 0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                boolean ok = (boolean) inject.invoke(manager, event, 2);
                event.recycle();
                lastX = x; lastY = y;
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) downTime = 0;
                System.out.println(ok ? "ok" : "error:denied");
                System.out.flush();
            }
        } finally {
            if (downTime != 0) {
                MotionEvent cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, lastX, lastY, 0);
                cancel.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                inject.invoke(manager, cancel, 2);
                cancel.recycle();
            }
        }
    }
}
