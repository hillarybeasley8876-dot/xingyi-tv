package xyz.erapi0neer.xingyi;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;

/** Native ten-foot layout; all dimensions are based on the 1920px canvas, never system sp. */
public final class AssistantLayout {
    public interface Listener { void onAction(String id); }

    public final View root;
    public final EditText input;
    public final TextView answer, status, taskStatus;
    public final Button send, execute, cancel, speak;
    public final ScrollView answerScroll;
    final TaskProgressView progress;

    private final Activity context;
    private final Listener listener;
    private final float scale;
    private final Map<String, Button> buttons = new LinkedHashMap<>();
    private static final int WHITE = 0xffedf0f2, MUTED = 0xffa4abb4;
    private static final int BASE = 0xff0b0d10, PANEL = 0xff11151a, LINE = 0xff303741;

    public AssistantLayout(Activity activity, Listener actions) {
        context = activity;
        listener = actions;
        scale = activity.getResources().getDisplayMetrics().widthPixels / 1920f;

        LinearLayout canvas = column();
        canvas.setPadding(px(64), px(40), px(64), px(32));
        canvas.setBackgroundColor(BASE);
        root = canvas;

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        canvas.addView(header, new LinearLayout.LayoutParams(-1, px(88)));
        LinearLayout identity = column();
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        TextView name = text("星仪", 50, WHITE);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        identity.addView(name);
        TextView eyebrow = text("ORRERY  /  MINIMAX M3.1 FLASH", 19, MUTED);
        eyebrow.setLetterSpacing(0.16f);
        identity.addView(eyebrow);
        status = text("电视助手", 25, MUTED);
        status.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        header.addView(status, new LinearLayout.LayoutParams(px(840), -1));

        space(canvas, 24);
        LinearLayout body = row();
        canvas.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout shortcuts = column();
        shortcuts.setPadding(px(24), px(24), px(24), px(24));
        shortcuts.setBackground(shape(PANEL, LINE, 1, 12));
        body.addView(shortcuts, new LinearLayout.LayoutParams(px(448), -1));
        TextView controlsTitle = text("快捷控制", 26, MUTED);
        shortcuts.addView(controlsTitle, new LinearLayout.LayoutParams(-1, px(40)));
        space(shortcuts, 14);
        addSingle(shortcuts, "screen", "看当前屏幕", 80);
        addSingle(shortcuts, "camera", "打开摄像头", 80);
        addPair(shortcuts, "apps", "应用", "hdmi", "信号源", 72);

        LinearLayout volume = row();
        shortcuts.addView(volume, rowParams(72));
        addWeighted(volume, makeButton("volume_down", "音量 −", 27), 1, 8);
        addWeighted(volume, makeButton("volume_up", "音量 +", 27), 1, 8);
        addWeighted(volume, makeButton("mute", "静音", 27), 1, 0);
        addSingle(shortcuts, "timer", "定时休眠", 72);
        addPair(shortcuts, "settings", "设置", "home", "回桌面", 72);
        addPair(shortcuts, "history", "历史", "memory", "记忆", 64);
        shortcuts.addView(new View(context), new LinearLayout.LayoutParams(1, 0, 1));
        Button details = makeButton("capabilities", "功能与状态", 25);
        shortcuts.addView(details, new LinearLayout.LayoutParams(-1, px(56)));

        View separator = new View(context);
        body.addView(separator, new LinearLayout.LayoutParams(px(28), 1));
        LinearLayout conversation = column();
        body.addView(conversation, new LinearLayout.LayoutParams(0, -1, 1));
        TextView prompt = text("和星仪对话", 25, MUTED);
        conversation.addView(prompt);
        space(conversation, 12);

        LinearLayout entry = row();
        conversation.addView(entry, new LinearLayout.LayoutParams(-1, px(76)));
        input = new EditText(context);
        input.setId(View.generateViewId());
        input.setSingleLine(true);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(31));
        input.setTextColor(WHITE);
        input.setHintTextColor(0xff89929f);
        input.setHint("输入问题，或说清想做的事");
        input.setPadding(px(22), 0, px(22), 0);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setSelectAllOnFocus(false);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setBackground(outlinedField());
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, -1, 1);
        inputParams.rightMargin = px(12);
        entry.addView(input, inputParams);
        Button phoneVoice = makeButton("phone_voice", "手机语音", 25);
        LinearLayout.LayoutParams voiceParams = new LinearLayout.LayoutParams(px(182), -1);
        voiceParams.rightMargin = px(12);
        entry.addView(phoneVoice, voiceParams);
        send = makeButton("send", "发送", 31);
        entry.addView(send, new LinearLayout.LayoutParams(px(150), -1));
        input.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEND) {
                if (send.isEnabled()) listener.onAction("send");
                return true;
            }
            return false;
        });

        space(conversation, 20);
        LinearLayout response = column();
        response.setPadding(px(26), px(22), px(26), px(22));
        response.setBackground(shape(PANEL, LINE, 1, 12));
        conversation.addView(response, new LinearLayout.LayoutParams(-1, 0, 1));
        taskStatus = text("准备就绪", 24, MUTED);
        taskStatus.setSingleLine(true);
        taskStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        response.addView(taskStatus, new LinearLayout.LayoutParams(-1, px(36)));
        space(response, 12);
        answerScroll = new ScrollView(context);
        answerScroll.setId(View.generateViewId());
        answerScroll.setFillViewport(true);
        answerScroll.setFocusable(true);
        answerScroll.setFocusableInTouchMode(false);
        answerScroll.setContentDescription("回答区域，按上下方向键滚动");
        answerScroll.setBackground(outlinedField());
        answerScroll.setPadding(px(18), px(14), px(18), px(14));
        answerScroll.setVerticalScrollBarEnabled(true);
        answerScroll.setScrollbarFadingEnabled(false);
        answerScroll.setScrollBarSize(px(4));
        LinearLayout activityRow = row();
        response.addView(activityRow, new LinearLayout.LayoutParams(-1,0,1));
        activityRow.addView(answerScroll, new LinearLayout.LayoutParams(0,-1,1.15f));
        progress = new TaskProgressView(context,scale);
        LinearLayout.LayoutParams progressSize = new LinearLayout.LayoutParams(0,-1,1f);
        progressSize.leftMargin = px(22);
        activityRow.addView(progress,progressSize);
        answer = text("可以问一个问题，\n或从左侧选择电视功能。", 35, WHITE);
        answer.setLineSpacing(px(8), 1.10f);
        answer.setFocusable(false);
        answer.setTextIsSelectable(false);
        answer.setPadding(px(8), px(6), px(8), px(6));
        answerScroll.addView(answer, new ScrollView.LayoutParams(-1, -2));

        space(conversation, 16);
        LinearLayout resultActions = row();
        conversation.addView(resultActions, new LinearLayout.LayoutParams(-1, px(72)));
        execute = makeButton("execute", "确认执行", 30);
        cancel = makeButton("cancel", "取消", 30);
        speak = makeButton("speak", "朗读回答", 30);
        addWeighted(resultActions, execute, 1.2f, 12);
        addWeighted(resultActions, cancel, 0.8f, 12);
        addWeighted(resultActions, speak, 1f, 0);
        execute.setEnabled(false);
        space(conversation, 12);
        LinearLayout extras = row();
        conversation.addView(extras, new LinearLayout.LayoutParams(-1, px(62)));
        addWeighted(extras, makeButton("remote_voice", "电视键盘", 27), 1, 12);
        addWeighted(extras, makeButton("recent", "最近指令", 27), 1, 12);
        addWeighted(extras, makeButton("xiaoai", "交给小爱", 27), 1, 0);

        space(canvas, 18);
        TextView hints = text("方向键选择  ·  确定键操作  ·  回答区域可上下滚动", 21, 0xff7d8793);
        canvas.addView(hints, new LinearLayout.LayoutParams(-1, px(28)));

        // Predictable crossings between the left controls and the conversation panel.
        input.setNextFocusLeftId(button("screen").getId());
        input.setNextFocusRightId(phoneVoice.getId());
        input.setNextFocusDownId(answerScroll.getId());
        phoneVoice.setNextFocusLeftId(input.getId());
        phoneVoice.setNextFocusRightId(send.getId());
        phoneVoice.setNextFocusDownId(answerScroll.getId());
        send.setNextFocusLeftId(phoneVoice.getId());
        send.setNextFocusDownId(answerScroll.getId());
        answerScroll.setNextFocusUpId(input.getId());
        answerScroll.setNextFocusDownId(speak.getId());
        answerScroll.setNextFocusLeftId(button("apps").getId());
        answerScroll.setNextFocusRightId(progress.scroll.getId());
        send.setNextFocusDownId(progress.scroll.getId());
        progress.scroll.setNextFocusLeftId(answerScroll.getId());
        progress.scroll.setNextFocusUpId(send.getId());
        progress.scroll.setNextFocusDownId(speak.getId());
        speak.setNextFocusUpId(progress.scroll.getId());
        for (String id : new String[]{"screen", "camera", "hdmi", "mute", "timer", "home", "memory", "capabilities"}) {
            button(id).setNextFocusRightId(id.equals("screen") ? input.getId() : answerScroll.getId());
        }
        answerScroll.setOnKeyListener((view, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int direction = key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 0;
            if (direction != 0 && answerScroll.canScrollVertically(direction)) {
                answerScroll.smoothScrollBy(0, px(150) * direction);
                return true;
            }
            return false;
        });
    }

    /** Look up a control to adjust its label or enabled state without rebuilding the view. */
    public Button button(String id) { return buttons.get(id); }

    private int px(float value) { return Math.max(1, Math.round(value * scale)); }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(context); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(context); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value); view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size)); view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }
    private void space(LinearLayout parent, int height) { parent.addView(new View(context), new LinearLayout.LayoutParams(1, px(height))); }
    private LinearLayout.LayoutParams rowParams(int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, px(height));
        params.bottomMargin = px(12);
        return params;
    }
    private void addSingle(LinearLayout parent, String id, String label, int height) {
        parent.addView(makeButton(id, label, 31), rowParams(height));
    }
    private void addPair(LinearLayout parent, String first, String firstLabel, String second, String secondLabel, int height) {
        LinearLayout line = row();
        parent.addView(line, rowParams(height));
        addWeighted(line, makeButton(first, firstLabel, 29), 1, 12);
        addWeighted(line, makeButton(second, secondLabel, 29), 1, 0);
    }
    private void addWeighted(LinearLayout row, View child, float weight, int trailingGap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, weight);
        params.rightMargin = px(trailingGap);
        row.addView(child, params);
    }
    private Button makeButton(String id, String label, int fontSize) {
        Button button = new Button(context);
        button.setId(View.generateViewId()); button.setText(label); button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(fontSize));
        button.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        button.setGravity(Gravity.CENTER); button.setIncludeFontPadding(false);
        button.setPadding(px(8), 0, px(8), 0);
        button.setMinHeight(0); button.setMinimumHeight(0); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setSingleLine(true); button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.setFocusable(true); if ("execute".equals(id) || "send".equals(id)) button.setFocusableInTouchMode(true); button.setStateListAnimator(null);
        button.setBackground(buttonBackground());
        button.setTextColor(new ColorStateList(new int[][] {
                new int[]{-android.R.attr.state_enabled}, new int[]{android.R.attr.state_pressed},
                new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{0xff626b77, BASE, BASE, WHITE}));
        button.setOnClickListener(view -> listener.onAction(id));
        buttons.put(id, button);
        return button;
    }
    private StateListDrawable buttonBackground() {
        StateListDrawable state = new StateListDrawable();
        state.addState(new int[]{-android.R.attr.state_enabled}, shape(0xff15191e, 0xff252b33, 1, 8));
        state.addState(new int[]{android.R.attr.state_pressed}, shape(0xffb9c1cb, WHITE, 3, 8));
        state.addState(new int[]{android.R.attr.state_focused}, shape(WHITE, Color.WHITE, 3, 8));
        state.addState(new int[]{}, shape(0xff1c222a, 0xff37404b, 1, 8));
        return state;
    }
    private StateListDrawable outlinedField() {
        StateListDrawable state = new StateListDrawable();
        state.addState(new int[]{android.R.attr.state_focused}, shape(0xff151a21, WHITE, 2, 8));
        state.addState(new int[]{}, shape(0xff0e1217, 0xff29323d, 1, 8));
        return state;
    }
    private GradientDrawable shape(int fill, int stroke, int thickness, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill); drawable.setStroke(px(thickness), stroke); drawable.setCornerRadius(px(radius));
        return drawable;
    }
}

