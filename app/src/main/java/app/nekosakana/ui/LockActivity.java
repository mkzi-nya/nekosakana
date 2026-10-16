package app.nekosakana.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import app.nekosakana.core.Config;
import app.nekosakana.core.ControlLog;
import app.nekosakana.engine.Engine;

/** 锁定页：全屏展示对方设定的问题，实时回传作答，超过次数通知对方。 */
public class LockActivity extends Activity {
    private static final List<LockActivity> ACTIVE = new ArrayList<>();
    private final Handler watchdog = new Handler(Looper.getMainLooper());
    private int attempts;
    private int maxWrong = 3;
    private boolean allowForceExit;
    private View overlayView;
    private WindowManager overlayManager;
    private boolean strictLock;
    private String questionRevision = "";
    private long lastScroll;
    private boolean releasing;

    public static void releaseAll() {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (LockActivity activity : new ArrayList<>(ACTIVE)) {
                try { activity.stopLockTask(); } catch (Throwable ignored) { }
                activity.finish();
            }
            ACTIVE.clear();
        });
    }

    public static void applyConfig() {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (LockActivity activity : new ArrayList<>(ACTIVE)) activity.build();
        });
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        ACTIVE.add(this);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        hideSystemBars();
        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                android.content.ComponentName admin = app.nekosakana.caps.PermsX.adminComponent(this);
                dpm.setLockTaskPackages(admin, new String[]{getPackageName()});
                dpm.setLockTaskFeatures(admin, android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
                dpm.setStatusBarDisabled(admin, true);
                strictLock = true;
                startLockTask();
            }
        } catch (Throwable ignored) { }
        build();
        watchdog.post(watchdogTask);
    }

    private final Runnable watchdogTask = new Runnable() {
        @Override
        public void run() {
            if (releasing || isFinishing() || isDestroyed()) return;
            android.view.WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            boolean imeVisible = insets != null && Build.VERSION.SDK_INT >= 30
                    && insets.isVisible(android.view.WindowInsets.Type.ime());
            if (Build.VERSION.SDK_INT < 30) {
                android.graphics.Rect visible = new android.graphics.Rect();
                getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
                imeVisible = metrics.heightPixels - visible.bottom > Ui.dp(LockActivity.this, 160);
            }
            if (overlayView == null && !hasWindowFocus() && !imeVisible) {
                try {
                    Intent intent = new Intent(LockActivity.this, LockActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(intent);
                } catch (Throwable ignored) {
                }
            }
            hideSystemBars();
            watchdog.postDelayed(this, 1200);
        }
    };

    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private JSONObject config() {
        try {
            String raw = Config.getString(Config.K_LOCK_CONFIG, "{}");
            return new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void build() {
        JSONObject config = config();
        String validationError = LockConfigValidator.error(config);
        if (validationError != null) {
            app.nekosakana.core.Logx.e("lock", "拒绝无效锁定配置：" + validationError);
            finish();
            return;
        }
        String question = config.optString("question", "请输入口令");
        String mode = config.optString("mode", "input");
        boolean multi = config.optBoolean("multi", false);
        maxWrong = config.optInt("maxWrong", 3);
        allowForceExit = config.optBoolean("allowForceExit", false);
        JSONArray options = config.optJSONArray("options");
        final JSONArray answers = config.optJSONArray("answers");
        JSONArray inputs = config.optJSONArray("inputs");
        String revision = question + "|" + mode + "|" + String.valueOf(options) + "|" + String.valueOf(inputs);
        if (!revision.equals(questionRevision)) attempts = 0;
        questionRevision = revision;

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Color.parseColor("#05080A"));
        root.setPadding(Ui.dp(this, 24), Ui.dp(this, 60), Ui.dp(this, 24), Ui.dp(this, 60));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        removeOverlay();
        // An owner uses system lock-task mode; otherwise keep the question above other apps.
        if (!strictLock && (app.nekosakana.caps.AccessService.isConnected() || android.provider.Settings.canDrawOverlays(this))) {
            try {
                app.nekosakana.caps.AccessService access = app.nekosakana.caps.AccessService.get();
                overlayManager = (WindowManager) (access != null ? access : this).getSystemService(WINDOW_SERVICE);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                        access != null ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN
                                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        android.graphics.PixelFormat.OPAQUE);
                lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
                root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                overlayManager.addView(root, lp);
                overlayView = root;
            } catch (Throwable t) { app.nekosakana.core.Logx.w("lock", "悬浮锁定失败 " + t); }
        }
        if (overlayView == null) setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        TextView brand = Ui.text(this, "nekosakana", 12, Ui.ACCENT, true);
        root.addView(brand, Ui.lp(this, -1, -2, 0, 0, 0, 4));

        ScrollView scroll = new ScrollView(this);
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastScroll < 150) return;
            lastScroll = now;
            JSONObject value = event("scroll");
            try { value.put("x", x); value.put("y", y); } catch (Exception ignored) { }
            Engine.get().sendEvent("lock.event", value);
        });
        LinearLayout card = Ui.column(this);
        card.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18));
        card.setBackground(Ui.round(0xFF111A17, 8, this));

        TextView q = Ui.text(this, question, 20, Ui.TEXT, true);
        q.setGravity(Gravity.CENTER);
        card.addView(q, Ui.lp(this, -1, -2, 0, 8, 0, 14));

        final List<CheckBox> checkboxList = new ArrayList<>();
        final List<RadioButton> radioList = new ArrayList<>();
        final RadioGroup radioGroup = new RadioGroup(this);
        final List<EditText> inputList = new ArrayList<>();

        if ("choice".equals(mode) && options != null) {
            if (multi) {
                for (int i = 0; i < options.length(); i++) {
                    CheckBox cb = new CheckBox(this);
                    cb.setText(options.optString(i));
                    cb.setTextColor(Ui.TEXT);
                    cb.setTextSize(16);
                    cb.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
                    final int optionIndex = i;
                    cb.setOnCheckedChangeListener((button, checked) -> {
                        JSONObject value = event("selection");
                        try { value.put("index", optionIndex); value.put("checked", checked); } catch (Exception ignored) { }
                        Engine.get().sendEvent("lock.event", value);
                    });
                    checkboxList.add(cb);
                    card.addView(cb);
                }
            } else {
                radioGroup.setOrientation(RadioGroup.VERTICAL);
                for (int i = 0; i < options.length(); i++) {
                    RadioButton rb = new RadioButton(this);
                    rb.setText(options.optString(i));
                    rb.setTextColor(Ui.TEXT);
                    rb.setTextSize(16);
                    rb.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
                    rb.setId(i + 1);
                    radioList.add(rb);
                    radioGroup.addView(rb);
                }
                card.addView(radioGroup);
                radioGroup.setOnCheckedChangeListener((group, id) -> {
                    JSONObject value = event("selection");
                    try { value.put("index", id - 1); value.put("checked", id > 0); } catch (Exception ignored) { }
                    Engine.get().sendEvent("lock.event", value);
                });
            }
        } else if (inputs != null && inputs.length() > 0) {
            for (int i = 0; i < inputs.length(); i++) {
                TextView label = Ui.text(this, inputs.optString(i), 13, Ui.MUTED, false);
                label.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
                card.addView(label);
                EditText edit = Ui.input(this, "在此输入");
                inputList.add(edit);
                card.addView(edit, Ui.lp(this, -1, 50, 0, 0, 0, 0));
            }
        } else {
            EditText edit = Ui.input(this, "在此输入");
            inputList.add(edit);
            card.addView(edit, Ui.lp(this, -1, 50, 0, 8, 0, 0));
        }

        TextView counter = Ui.text(this, "剩余尝试次数：" + Math.max(0, maxWrong - attempts), 12, Ui.WARN, false);
        for (int i = 0; i < inputList.size(); i++) {
            final int inputIndex = i;
            EditText edit = inputList.get(i);
            edit.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    JSONObject value = event("input");
                    try { value.put("index", inputIndex); value.put("text", s.toString()); } catch (Exception ignored) { }
                    Engine.get().sendEvent("lock.event", value);
                }
                public void afterTextChanged(android.text.Editable editable) { }
            });
            edit.setOnFocusChangeListener((v, focused) -> {
                JSONObject value = event("focus");
                try { value.put("index", inputIndex); value.put("focused", focused); } catch (Exception ignored) { }
                Engine.get().sendEvent("lock.event", value);
            });
        }
        counter.setPadding(0, Ui.dp(this, 14), 0, 0);
        card.addView(counter);

        Button submit = Ui.button(this, "提交");
        submit.setEnabled(attempts < maxWrong);
        card.addView(submit, Ui.lp(this, -1, 50, 0, 14, 0, 0));
        submit.setOnClickListener(v -> {
            JSONObject event = new JSONObject();
            try {
                event.put("action", "submit");
                JSONArray selection = new JSONArray();
                if (multi) {
                    for (int i = 0; i < checkboxList.size(); i++) {
                        if (checkboxList.get(i).isChecked()) selection.put(i);
                    }
                } else if (!radioList.isEmpty()) {
                    int checked = radioGroup.getCheckedRadioButtonId();
                    if (checked > 0) selection.put(checked - 1);
                }
                event.put("selection", selection);
                JSONArray texts = new JSONArray();
                for (EditText edit : inputList) texts.put(edit.getText().toString());
                event.put("inputs", texts);
                event.put("attempts", attempts + 1);
            } catch (Exception ignored) {
            }
            Engine.get().sendEvent("lock.event", event);
            ControlLog.add("sys", "lock.submit", event.toString());
            attempts++;
            boolean correct = checkAnswer(answers, checkboxList, radioGroup, inputList, multi);
            ControlLog.add("sys", "lock.answer", "correct=" + correct);
            counter.setText("剩余尝试次数：" + Math.max(0, maxWrong - attempts));
            if (correct) {
                Engine.get().sendEvent("lock.event", event("correct"));
                Toast.makeText(this, "回答正确", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            if (attempts >= maxWrong) {
                JSONObject over = new JSONObject();
                try {
                    over.put("action", "maxwrong");
                    over.put("attempts", attempts);
                } catch (Exception ignored) {
                }
                Engine.get().sendEvent("lock.event", over);
                counter.setText("尝试次数已用尽");
                submit.setEnabled(false);
            } else {
                Toast.makeText(this, "回答错误", Toast.LENGTH_SHORT).show();
            }
        });

        scroll.addView(card, Ui.lp(this, -1, -2, 0, 0, 0, 0));
        root.addView(scroll, Ui.lp(this, -1, 0, 0, 0, 0, 0));
        ((LinearLayout.LayoutParams) scroll.getLayoutParams()).weight = 1;

        if (allowForceExit) {
            Button force = Ui.button(this, "强行退出", Ui.SURFACE2);
            force.setTextColor(Ui.MUTED);
            force.setTextSize(10);
            LinearLayout.LayoutParams forceParams = Ui.lp(this, 72, 34, 0, 10, 0, 0);
            forceParams.gravity = Gravity.START | Gravity.BOTTOM;
            root.addView(force, forceParams);
            force.setOnClickListener(v -> {
                Engine.get().sendEvent("lock.event", event("force_exit"));
                finish();
            });
        }
    }

    private JSONObject event(String action) {
        JSONObject o = new JSONObject();
        try {
            o.put("action", action);
            o.put("time", System.currentTimeMillis());
        } catch (Exception ignored) {
        }
        return o;
    }

    private boolean checkAnswer(JSONArray answers, List<CheckBox> checks, RadioGroup group,
                                List<EditText> inputs, boolean multi) {
        if (answers == null || answers.length() == 0 || LockConfigValidator.error(config()) != null) return false;
        try {
            if ("choice".equals(config().optString("mode", "input"))) {
                List<Integer> selected = new ArrayList<>();
                if (multi) {
                    for (int i = 0; i < checks.size(); i++) if (checks.get(i).isChecked()) selected.add(i);
                } else {
                    int checked = group.getCheckedRadioButtonId();
                    if (checked > 0) selected.add(checked - 1);
                }
                if (selected.size() != answers.length()) return false;
                for (int i = 0; i < answers.length(); i++) {
                    if (!selected.contains(answers.optInt(i))) return false;
                }
                return true;
            } else {
                if (inputs.size() != answers.length()) return false;
                for (int i = 0; i < inputs.size(); i++) {
                    if (!inputs.get(i).getText().toString().trim().equals(answers.optString(i).trim())) return false;
                }
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onBackPressed() {
        Engine.get().sendEvent("lock.event", event("back"));
    }

    @Override
    public void finish() {
        releasing = true;
        watchdog.removeCallbacksAndMessages(null);
        View inputWindow = overlayView != null ? overlayView : getWindow().getDecorView();
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(inputWindow.getWindowToken(), 0);
        removeOverlay();
        ACTIVE.remove(this);
        try { stopLockTask(); } catch (Throwable ignored) { }
        if (strictLock) {
            try {
                android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                dpm.setStatusBarDisabled(app.nekosakana.caps.PermsX.adminComponent(this), false);
            } catch (Throwable ignored) { }
        }
        super.finish();
    }

    @Override
    protected void onDestroy() {
        removeOverlay();
        if (strictLock) {
            try {
                android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                dpm.setStatusBarDisabled(app.nekosakana.caps.PermsX.adminComponent(this), false);
            } catch (Throwable ignored) { }
        }
        try { stopLockTask(); } catch (Throwable ignored) { }
        ACTIVE.remove(this);
        watchdog.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void removeOverlay() {
        if (overlayView != null) {
            try { overlayManager.removeViewImmediate(overlayView); } catch (Throwable ignored) { }
            overlayView = null;
        }
    }
}
