package com.qss.host;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.qss.license.KamiResult;
import com.qss.license.LicenseGate;

/**
 * 宿主主界面 —— 仅作示范：验证通过后才可进入。
 *
 * <p>这是一个干净的占位页：不含任何第三方代码。把你自己真正的业务界面
 * 替换掉 {@link #buildPlaceholder()} 即可，门禁逻辑（{@link #onCreate} 前半段）
 * 不要动。
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── 授权门禁：全应用唯一的判定入口 ──────────────────────────
        // 回调保证在主线程；GRANTED / GRANTED_OFFLINE 才放行。
        LicenseGate.checkAsync(this, new LicenseGate.Callback() {
            @Override
            public void onResult(LicenseGate.Verdict verdict, KamiResult r) {
                switch (verdict) {
                    case GRANTED:
                    case GRANTED_OFFLINE:
                        setContentView(buildPlaceholder(verdict == LicenseGate.Verdict.GRANTED_OFFLINE));
                        break;
                    case NEEDS_ACTIVATION:
                    case DENIED:
                    default:
                        // 跳激活页；本页不留在返回栈
                        startActivity(new Intent(MainActivity.this, com.qss.license.ActivationActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                        finish();
                        break;
                }
            }
        });
    }

    private View buildPlaceholder(boolean offline) {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(32));
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("授权已通过");
        title.setTextSize(24f);
        title.setTextColor(Color.parseColor("#1B5E20"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("这是你的主界面占位页。把你的业务功能替换到这里即可。");
        sub.setTextSize(14f);
        sub.setTextColor(Color.parseColor("#555555"));
        sub.setPadding(0, dp(8), 0, dp(20));
        root.addView(sub);

        TextView state = new TextView(this);
        String days = "未知";
        int d = LicenseGate.remainingDays(this);
        if (d >= 0) days = d + " 天";
        state.setText("授权状态：" + (offline ? "离线宽限期内（未联网校验）" : "在线校验通过")
                + "\n剩余时长：" + days);
        state.setTextSize(14f);
        state.setTextColor(Color.parseColor("#333333"));
        state.setPadding(0, 0, 0, dp(20));
        root.addView(state);

        Button hb = new Button(this);
        hb.setText("立即联网复检");
        hb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Toast.makeText(MainActivity.this, "正在复检…", Toast.LENGTH_SHORT).show();
                LicenseGate.refreshFromServer(MainActivity.this, new LicenseGate.Callback() {
                    @Override public void onResult(LicenseGate.Verdict vd, KamiResult r) {
                        Toast.makeText(MainActivity.this,
                                vd == LicenseGate.Verdict.GRANTED ? "复检通过"
                                        : (vd == LicenseGate.Verdict.GRANTED_OFFLINE ? "网络异常，仍在宽限期"
                                        : "授权失效，请重新激活"),
                                Toast.LENGTH_LONG).show();
                        if (vd == LicenseGate.Verdict.NEEDS_ACTIVATION || vd == LicenseGate.Verdict.DENIED) {
                            startActivity(new Intent(MainActivity.this, com.qss.license.ActivationActivity.class));
                            finish();
                        }
                    }
                });
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(hb, bp);

        Button logout = new Button(this);
        logout.setText("清除本地授权（测试用）");
        logout.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                LicenseGate.clearLicense(MainActivity.this);
                Toast.makeText(MainActivity.this, "已清除，请重启应用", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        root.addView(logout, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tip = new TextView(this);
        tip.setText("\n提示：清除本地授权仅删本机状态，服务端绑定关系仍存在。"
                + "同一卡密换机需后台解绑，或发卡时开启“允许自助解绑”。");
        tip.setTextSize(12f);
        tip.setTextColor(Color.parseColor("#888888"));
        tip.setGravity(Gravity.START);
        root.addView(tip);

        return sv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
