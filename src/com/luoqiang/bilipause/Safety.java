package com.luoqiang.bilipause;

/**
 * 安全红线：宁可漏点一次，也绝不点错。
 * 误点代价远大于漏点——点进广告页、点开大会员、点掉关注，用户会立刻卸载。
 */
final class Safety {

    /** id 整词黑名单（按 _ - . 切词后逐词比对），命中即拒绝 */
    private static final String[] BAD_TOKENS = {
            "danmaku", "coin", "fav", "like", "share", "back", "menu", "more",
            "send", "input", "comment", "reply", "follow", "avatar", "cover",
            "title", "setting", "settings", "volume", "rotate", "quality",
            "speed", "gift", "vip", "pay", "buy", "download", "seek", "progress",
            "slider", "track", "login", "register"
    };

    /** 文案黑名单：这些字样的按钮点下去一定出事（多为广告 CTA 或业务入口） */
    private static final String[] BAD_LABELS = {
            "开通", "大会员", "充值", "购买", "支付", "下载", "登录", "注册", "同意",
            "分享", "投币", "收藏", "举报", "关注", "评论", "发送", "打赏", "续费",
            "订购", "领取", "验证", "授权", "删除", "退出", "注销", "反馈", "客服",
            "投诉", "了解更多", "查看详情", "点击查看", "立即", "预约", "开播", "去购买",
            "弹幕", "倍速", "清晰", "画质", "投屏", "锁屏", "静音", "音量", "全屏",
            "选集", "连播", "定时", "稍后再看", "下载缓存"
    };

    /** 单个候选节点最多允许占屏幕的比例，超过必然不是关闭按钮 */
    private static final float MAX_AREA_RATIO = 0.05f;

    private Safety() {
    }

    static boolean badLabel(String s) {
        if (s == null || s.length() == 0) return false;
        for (int i = 0; i < BAD_LABELS.length; i++) {
            if (s.contains(BAD_LABELS[i])) return true;
        }
        return false;
    }

    static boolean badId(String idTail) {
        String[] tk = NodeUtil.tokens(idTail);
        for (int i = 0; i < tk.length; i++) {
            for (int j = 0; j < BAD_TOKENS.length; j++) {
                if (tk[i].equals(BAD_TOKENS[j])) return true;
            }
        }
        return false;
    }

    /** 返回拒绝原因；null 表示通过硬性校验 */
    static String reject(NodeWalker.Item it, int sw, int sh) {
        int a = NodeUtil.area(it.rect);
        if (a <= 0) return "尺寸为 0";
        if (NodeUtil.ratio(it.rect, sw, sh) > MAX_AREA_RATIO) return "面积过大（可能整层跳转）";
        if (badLabel(it.label())) return "危险文案:" + it.label();
        if (badId(it.idTail)) return "危险控件:" + it.idTail;
        return null;
    }

    /** 位置扣分：越像播放器控制条/系统栏，扣得越狠，但不硬拒（避免误伤真正在底部的关闭按钮） */
    static int positionPenalty(int left, int top, int right, int bottom, int sw, int sh, float density) {
        int penalty = 0;
        int bottomBar = NodeUtil.dp(96, density);
        if (bottom > sh - bottomBar) penalty -= 60;
        if (top < NodeUtil.dp(24, density)) penalty -= 40;
        int edge = NodeUtil.dp(4, density);
        if (left < edge || right > sw - edge) penalty -= 30;
        return penalty;
    }
}
