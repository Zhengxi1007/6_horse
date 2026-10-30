package com.qss.license;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 【环节 ②】激活界面：显示机器码 + 一键复制 + 输入卡密 + 提交绑定。
 *
 * <p>刻意用<b>纯代码布局</b>（不写 layout XML、不引用 R.*），这样整包丢进任意工程即可编译，
 * 不需要合并资源 ID。若你要套自己的视觉规范，只需替换 {@link #buildRoot} 里的视图构造，
 * 保留 {@link #onActivateClicked} 这个唯一调用点即可。
 *
 * <p>不可取消：{@code setCancelable(false)} + 返回键直接退出应用，避免用户绕过授权进入功能。
 */
public class ActivationActivity extends Activity {

    public static final String EXTRA_REASON = "reason";
    /** 激活成功后由子类/调用方接管跳转；默认本类不自跳，交给外部 {@link #onLicenseGranted}。 */
    public static final String EXTRA_GRANT_ACTION = "grant_action";

    private EditText cardInput;
    private Button activateBtn;
    private TextView statusText;
    private boolean submitting;

    /** 推荐入口：带上被拒原因，界面会显示为什么需要重新激活。 */
    public static void start(Context ctx, KamiResult reason) {
        Intent i = new Intent(ctx, ActivationActivity.class);
        if (reason != null) i.putExtra(EXTRA_REASON, reason.status.name());
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(buildRoot());
        setCancelableFalse();
        showReason();
    }

    // ------------------------------------------------------------ UI 构造

    private View buildRoot() {
        final float density = getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(density, 20), dp(density, 20), dp(density, 20), dp(density, 20));
        root.setBackgroundColor(Color.parseColor("#FFFFFF"));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 界面标题（本 Activity 无 ActionBar，标题直接画在内容区）
        root.addView(text("软件激活", 18f, Color.parseColor("#111827"),
                Typeface.DEFAULT_BOLD, 0, dp(density, 10)));

        // 机器码标签
        root.addView(text("本机机器码（请发给管理员）", 12f, Color.parseColor("#6B7280"),
                Typeface.DEFAULT, dp(density, 4), dp(density, 14)));

        // 机器码展示框 + 复制
        LinearLayout mcBox = new LinearLayout(this);
        mcBox.setOrientation(LinearLayout.VERTICAL);
        mcBox.setPadding(dp(density, 12), dp(density, 10), dp(density, 12), dp(density, 10));
        GradientDrawable mcBg = new GradientDrawable();
        mcBg.setColor(Color.parseColor("#F3F4F6"));
        mcBg.setStroke(dp(density, 1), Color.parseColor("#D1D5DB"));
        mcBg.setCornerRadius(dp(density, 8));
        mcBox.setBackground(mcBg);

        final String machineCode = MachineCode.get(this);
        final TextView mcText = new TextView(this);
        mcText.setText(machineCode);
        mcText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        mcText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mcText.setTextColor(Color.parseColor("#111827"));
        mcText.setGravity(Gravity.CENTER);
        mcText.setTextIsSelectable(true);
        mcBox.addView(mcText, matchWrap());

        Button copy = styledButton("复制机器码", Color.parseColor("#111827"), false, density);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("machine_code", machineCode));
                    Toast.makeText(ActivationActivity.this, "已复制", Toast.LENGTH_SHORT).show();
                }
            }
        });
        LinearLayout.LayoutParams copyLp = matchWrap();
        copyLp.topMargin = dp(density, 8);
        mcBox.addView(copy, copyLp);

        LinearLayout.LayoutParams mcLp = matchWrap();
        mcLp.topMargin = dp(density, 6);
        root.addView(mcBox, mcLp);

        // 卡密输入
        root.addView(text("输入卡密", 12f, Color.parseColor("#6B7280"),
                Typeface.DEFAULT, dp(density, 18), dp(density, 6)));

        cardInput = new EditText(this);
        cardInput.setHint("在此输入卡密…");
        cardInput.setSingleLine(true);
        cardInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        cardInput.setTextColor(Color.parseColor("#111827"));
        cardInput.setHintTextColor(Color.parseColor("#9CA3AF"));
        // 简单卡密多为大写字母数字；TYPE_CLASS_TEXT 保证中文输入法可正常唤起
        cardInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        cardInput.setBackground(null);
        cardInput.setPadding(dp(density, 12), dp(density, 12), dp(density, 12), dp(density, 12));
        LinearLayout editWrap = new LinearLayout(this);
        editWrap.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable editBg = new GradientDrawable();
        editBg.setColor(Color.WHITE);
        editBg.setStroke(dp(density, 1), Color.parseColor("#D1D5DB"));
        editBg.setCornerRadius(dp(density, 8));
        editWrap.setBackground(editBg);
        editWrap.addView(cardInput, matchWrap());
        LinearLayout.LayoutParams editLp = matchWrap();
        editLp.topMargin = dp(density, 6);
        root.addView(editWrap, editLp);

        // 状态文案
        statusText = new TextView(this);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        statusText.setTextColor(Color.parseColor("#DC2626"));
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stLp = matchWrap();
        stLp.topMargin = dp(density, 12);
        root.addView(statusText, stLp);

        // 激活按钮
        activateBtn = styledButton("立即激活", Color.parseColor("#2563EB"), true, density);
        activateBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onActivateClicked();
            }
        });
        LinearLayout.LayoutParams btnLp = matchWrap();
        btnLp.topMargin = dp(density, 16);
        btnLp.height = dp(density, 46);
        root.addView(activateBtn, btnLp);

        // 退出
        Button exit = styledButton("退出应用", Color.parseColor("#6B7280"), false, density);
        exit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishAffinity();
            }
        });
        LinearLayout.LayoutParams exLp = matchWrap();
        exLp.topMargin = dp(density, 8);
        root.addView(exit, exLp);

        return scroll;
    }

    private void setCancelableFalse() {
        setFinishOnTouchOutside(false);
        // 物理返回键：不允许绕过授权
    }

    @Override
    public void onBackPressed() {
        finishAffinity();
    }

    private void showReason() {
        String reason = getIntent().getStringExtra(EXTRA_REASON);
        if (reason == null) return;
        KamiResult.Status s;
        try {
            s = KamiResult.Status.valueOf(reason);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (s == KamiResult.Status.NOT_ACTIVATED) return; // 首次使用，无需吓用户
        statusText.setText(new KamiResult(s).userMessage());
    }

    // ------------------------------------------------------------ 提交

    private void onActivateClicked() {
        if (submitting) return;
        String cardKey = cardInput.getText().toString().trim();
        if (cardKey.length() == 0) {
            statusText.setText("请输入卡密");
            return;
        }
        submitting = true;
        activateBtn.setEnabled(false);
        activateBtn.setText("验证中…");
        statusText.setText("");

        LicenseGate.activateAsync(this, cardKey, new LicenseGate.ActivateCallback() {
            @Override
            public void onResult(KamiResult r) {
                submitting = false;
                if (isFinishing() || isDestroyed()) return;
                activateBtn.setEnabled(true);
                activateBtn.setText("立即激活");
                if (r.isGranted()) {
                    statusText.setTextColor(Color.parseColor("#059669"));
                    statusText.setText("激活成功");
                    onLicenseGranted(r);
                } else {
                    statusText.setTextColor(Color.parseColor("#DC2626"));
                    // 优先用我们的语义化文案；UNKNOWN_BLOCK 时附上服务端原文便于排障
                    String msg = r.userMessage();
                    if (r.status == KamiResult.Status.UNKNOWN_BLOCK && r.rawMessage != null) {
                        msg = "无法激活：" + r.rawMessage;
                    }
                    statusText.setText(msg);
                }
            }
        });
    }

    /** 激活成功后做什么：默认按调用方传入的 action 跳转；否则结束自身由父栈继续。 */
    protected void onLicenseGranted(KamiResult r) {
        String action = getIntent().getStringExtra(EXTRA_GRANT_ACTION);
        if (action != null && action.length() > 0) {
            Intent i = new Intent(action);
            i.setPackage(getPackageName());
            startActivity(i);
        }
        finish();
    }

    // ------------------------------------------------------------ 小工具

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView text(String s, float sp, int color, Typeface tf, int top, int bottom) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = top;
        lp.bottomMargin = bottom;
        t.setLayoutParams(lp);
        return t;
    }

    private Button styledButton(String label, int color, boolean solid, float density) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null); // 去掉 Material 默认阴影，保证各 ROM 观感一致
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(density, 8));
        if (solid) {
            bg.setColor(color);
            b.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.WHITE);
            bg.setStroke(dp(density, 1), color);
            b.setTextColor(color);
        }
        b.setBackground(bg);
        return b;
    }

    private static int dp(float density, int value) {
        return (int) (value * density + 0.5f);
    }
}
