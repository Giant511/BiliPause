package com.luoqiang.bilipause;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 机型设置指引：选品牌 → 看手动路径 → 点入口直接跳到对应的系统设置页。 */
public class GuideActivity extends Activity {

    private static final String[] ENTRIES = {
            "① 无障碍服务（必须开）",
            "② 自启动 / 后台运行",
            "③ 电池优化 / 后台耗电",
            "④ 应用详情页（通用兜底）"
    };

    private final List<VendorGuide.Brand> brands = VendorGuide.brands();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);

        ListView lv = (ListView) findViewById(R.id.lvBrands);
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        for (int i = 0; i < brands.size(); i++) {
            Map<String, String> m = new HashMap<String, String>();
            m.put("name", brands.get(i).name);
            m.put("rom", brands.get(i).rom);
            rows.add(m);
        }
        SimpleAdapter adapter = new SimpleAdapter(this, rows,
                android.R.layout.simple_list_item_2,
                new String[]{"name", "rom"},
                new int[]{android.R.id.text1, android.R.id.text2});
        lv.setAdapter(adapter);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                showBrand(brands.get(position));
            }
        });

        findViewById(R.id.btnBackGuide).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void showBrand(final VendorGuide.Brand brand) {
        // 注意：AlertDialog 同时 setMessage + setItems 时，列表不会渲染（只有正文）。
        // 所以这里自绘内容区：一段说明 + 四个入口按钮。
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView tv = new TextView(this);
        tv.setTextSize(13);
        tv.setTextColor(0xFF444444);
        tv.setLineSpacing(0, 1.25f);
        tv.setText("系统：" + brand.rom + "\n\n" + brand.steps);
        box.addView(tv);

        final AlertDialog[] holder = new AlertDialog[1];

        TextView hint = new TextView(this);
        hint.setText("点下面的入口可直接跳转到对应设置页：");
        hint.setTextSize(12);
        hint.setTextColor(0xFF999999);
        hint.setPadding(0, (int) (12 * d), 0, 0);
        box.addView(hint);

        for (int i = 0; i < ENTRIES.length; i++) {
            final int which = i;
            Button b = new Button(this);
            b.setText(ENTRIES[i]);
            b.setAllCaps(false);
            b.setTextSize(13);
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setPadding((int) (10 * d), 0, 0, 0);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (holder[0] != null) holder[0].dismiss();
                    dispatch(brand, which);
                }
            });
            box.addView(b);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(brand.name)
                .setView(sv)
                .setNegativeButton("关闭", null)
                .create();
        holder[0] = dialog;
        dialog.show();
    }

    private void dispatch(VendorGuide.Brand brand, int which) {
        String err;
        switch (which) {
            case 0:
                err = VendorGuide.openAccessibility(this);
                tip(err, "无障碍设置");
                break;
            case 1:
                err = VendorGuide.openComponents(this, brand.startup);
                if (err != null) {
                    // 厂商没开放这个页面，退到应用详情页
                    String err2 = VendorGuide.openAppDetail(this);
                    toast(err2 == null
                            ? "该系统没有自启动直达页，已打开应用详情页，请按上面的文字路径设置"
                            : "跳过失败：" + err);
                } else {
                    toast("已打开自启动相关页面，找到「跳过B站暂停广告」并允许");
                }
                break;
            case 2:
                err = VendorGuide.openComponents(this, brand.power);
                if (err != null) err = VendorGuide.openBatteryOptimization(this);
                tip(err, "电池优化页面");
                break;
            default:
                err = VendorGuide.openAppDetail(this);
                tip(err, "应用详情页");
                break;
        }
    }

    private void tip(String err, String page) {
        if (err == null) {
            toast("已打开" + page + "，找到「跳过B站暂停广告」设置");
        } else {
            toast("打不开" + page + "：" + err + "，请按文字路径手动找");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
