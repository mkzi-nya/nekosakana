package app.nekosakana.ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

public final class LockConfigValidator {
    private LockConfigValidator() {
    }

    public static String error(JSONObject config) {
        if (config == null) return "缺少锁定配置";
        if (config.optString("question", "").trim().isEmpty()) return "请填写问题";

        String mode = config.optString("mode", "input");
        if (!"input".equals(mode) && !"choice".equals(mode)) return "题目类型无效";
        if (config.optInt("maxWrong", 0) < 1) return "允许错误次数至少为 1";

        JSONArray answers = config.optJSONArray("answers");
        if (answers == null || answers.length() == 0) return "请至少设置一个答案";

        if ("choice".equals(mode)) {
            JSONArray options = config.optJSONArray("options");
            if (options == null || options.length() == 0) return "请至少设置一个选项";
            for (int i = 0; i < options.length(); i++) {
                if (stringAt(options, i).trim().isEmpty()) return "选项不能留空";
            }
            boolean multi = config.optBoolean("multi", false);
            if (!multi && answers.length() != 1) return "单选题只能设置一个正确选项";
            Set<Integer> selected = new HashSet<>();
            for (int i = 0; i < answers.length(); i++) {
                Object value = valueAt(answers, i);
                if (!(value instanceof Number)) return "答案序号无效";
                double number = ((Number) value).doubleValue();
                int index = (int) number;
                if (number != index || index < 0 || index >= options.length()) return "答案序号超出选项范围";
                if (!selected.add(index)) return "答案序号不能重复";
            }
            return null;
        }

        JSONArray inputs = config.optJSONArray("inputs");
        int inputCount = 1;
        if (config.has("inputs")) {
            if (inputs == null) return "输入框配置无效";
            inputCount = 0;
            for (int i = 0; i < inputs.length(); i++) {
                if (stringAt(inputs, i).trim().isEmpty()) return "输入框名称不能留空";
                inputCount++;
            }
            if (inputCount == 0) inputCount = 1;
        }
        if (answers.length() != inputCount) return "答案数量必须与输入框数量一致";
        for (int i = 0; i < answers.length(); i++) {
            if (stringAt(answers, i).trim().isEmpty()) return "答案不能留空";
        }
        return null;
    }

    private static String stringAt(JSONArray array, int index) {
        Object value = valueAt(array, index);
        return value instanceof String ? (String) value : "";
    }

    private static Object valueAt(JSONArray array, int index) {
        try {
            return array.get(index);
        } catch (Exception ignored) {
            return null;
        }
    }
}
