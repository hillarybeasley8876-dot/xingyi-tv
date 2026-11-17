package xyz.erapi0neer.xingyi.agent;

import java.util.regex.Pattern;

/** Which on-screen choices the agent must not make on its own. */
public final class Sensitive {
    private static final Pattern NEGATIVE = Pattern.compile("^(不|拒绝|取消|暂不|以后|稍后|跳过|关闭|返回|退出$)");
    private static final Pattern CONSENT = Pattern.compile("同意|允许|授权|接受|我已阅读|已阅读并");
    private static final Pattern MONEY = Pattern.compile("支付|付款|购买|开通|续费|订阅|充值|下单|立即抢|确认订单|兑换|领取会员|扫码付");
    private static final Pattern DESTROY = Pattern.compile("删除|卸载|清除|清空|格式化|恢复出厂|重置|退出登录|注销|解绑|解除绑定");
    private static final Pattern DEVICE = Pattern.compile("门锁|开锁|解锁|开门|报警|燃气|保险箱");

    private Sensitive() { }

    /** Why choosing this label needs the user's consent, or null when it does not. */
    public static String why(String label) {
        if (label == null) return null;
        String l = label.trim();
        if (l.isEmpty() || NEGATIVE.matcher(l).find()) return null;
        if (MONEY.matcher(l).find()) return "涉及付费";
        if (DESTROY.matcher(l).find()) return "会删除或重置内容";
        if (CONSENT.matcher(l).find()) return "代表你同意协议或授权";
        return null;
    }

    /** Smart-home requests that unlock or disarm something. */
    public static boolean securityDevice(String text) { return text != null && DEVICE.matcher(text).find(); }
}
