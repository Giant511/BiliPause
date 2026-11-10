package com.luoqiang.bilipause;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private TextView tvStatus;
    private TextView tvPhase;
    private TextView tvHits;
    private TextView tvLog;
    private Switch swEnabled;
    private Switch swAggressive;
    private Switch swShot;
    private Switch swCapture;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private String lastLog = "";
    private boolean binding;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 700L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.init(this);
        setContentView(R.layout.activity_main);

        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvPhase = (TextView) findViewById(R.id.tvPhase);
        tvHits = (TextView) findViewById(R.id.tvHits);
        tvLog = (TextView) findViewById(R.id.tvLog);
        swEnabled = (Switch) findViewById(R.id.swEnabled);
        swAggressive = (Switch) findViewById(R.id.swAggressive);
        swShot = (Switch) findViewById(R.id.swShot);
        swCapture = (Switch) findViewById(R.id.swCapture);

        binding = true;
        swEnabled.setChecked(Prefs.enabled());
        swAggressive.setChecked(Prefs.aggressive());
        swShot.setChecked(Prefs.screenshot());
        swCapture.setChecked(Prefs.capture());
        binding = false;

        swEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (binding) return;
                Prefs.setEnabled(checked);
                LogRing.add(checked ? "总开关已打开" : "总开关已关闭");
            }
        });
        swAggressive.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (binding) return;
                Prefs.setAggressive(checked);
                LogRing.add(checked ? "激进模式已打开" : "激进模式已关闭");
            }
        });
        swShot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (binding) return;
                Prefs.setScreenshot(checked);
                LogRing.add(checked ? "命中时自动存截图：开" : "命中时自动存截图：关");
            }
        });

        swCapture.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (binding) return;
                Prefs.setCapture(checked);
                LogRing.add(checked ? "抓取模式已打开（每 2s 存一份现场）" : "抓取模式已关闭");
            }
        });

        findViewById(R.id.btnEnable).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });

        findViewById(R.id.btnGuide).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, GuideActivity.class));
            }
        });

        findViewById(R.id.btnCopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String d = ServiceState.lastDump;
                if (d == null || d.length() == 0) {
                    toast("还没有抓取记录：先去 B 站暂停出广告，再回来复制");
                    return;
                }
                copy(d);
                toast("已复制 " + d.length() + " 字符");
            }
        });

        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String d = ServiceState.lastDump;
                if (d == null || d.length() == 0) {
                    toast("还没有抓取记录");
                    return;
                }
                toast(saveText("nodes", d));
            }
        });

        findViewById(R.id.btnShot).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AdGuardService svc = AdGuardService.get();
                if (svc == null) {
                    toast("无障碍服务未连接");
                    return;
                }
                toast("正在截图…");
                ScreenProbe.capture(svc, "manual", new ScreenProbe.Cb() {
                    @Override
                    public void onDone(String msg) {
                        toast(msg);
                    }
                });
            }
        });

        findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogRing.clear();
                lastLog = "";
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(refresher);
        ui.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresher);
    }

    private void refresh() {
        boolean enabled = accEnabledInSettings();
        if (enabled) {
            tvStatus.setText(R.string.status_on);
            tvStatus.setTextColor(0xFF2E7D32);
        } else {
            tvStatus.setText(R.string.status_off);
            tvStatus.setTextColor(0xFFD32F2F);
        }

        StringBuilder sb = new StringBuilder(ServiceState.phase);
        if (ServiceState.activity != null && ServiceState.activity.length() > 0) {
            sb.append("  ·  当前页面 ").append(AdGuardService.shortCls(ServiceState.activity));
        }
        if (enabled && !ServiceState.connected) {
            sb.append("  ·  服务未连接：解锁屏幕，或到系统无障碍里关闭再开启一次");
        }
        tvPhase.setText(sb.toString());
        tvHits.setText(getString(R.string.hit_count, Prefs.hits()));

        String log = LogRing.dump(120);
        if (!log.equals(lastLog)) {
            lastLog = log;
            tvLog.setText(log);
        }
    }

    private boolean accEnabledInSettings() {
        if (ServiceState.connected) return true;
        try {
            String list = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null) return false;
            String me = getPackageName() + "/" + AdGuardService.class.getName();
            return list.contains(me);
        } catch (Exception e) {
            return false;
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast("在列表里找到「跳过B站暂停广告」并打开开关");
        } catch (Exception e) {
            toast("打不开系统设置：" + e);
        }
    }

    private void copy(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("bilipause", s));
        } catch (Exception e) {
            toast("复制失败：" + e);
        }
    }

    private String saveText(String prefix, String content) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            String name = prefix + "-"
                    + new SimpleDateFormat("MMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
            File f = new File(dir, name);
            FileOutputStream out = new FileOutputStream(f);
            out.write(content.getBytes("UTF-8"));
            out.close();
            return "已保存：" + f.getAbsolutePath();
        } catch (Exception e) {
            return "保存失败：" + e;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
