package com.xiguan.smsfw;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 一条待转发的短信。
 */
public final class SmsInfo {

    public final String sender;
    public final String content;
    public final long timeMillis;
    /** 0 = 卡1，1 = 卡2，-1 = 未知 */
    public final int slotIndex;

    public SmsInfo(String sender, String content, long timeMillis, int slotIndex) {
        this.sender = sender == null ? "" : sender;
        this.content = content == null ? "" : content;
        this.timeMillis = timeMillis > 0 ? timeMillis : System.currentTimeMillis();
        this.slotIndex = slotIndex;
    }

    public String slotLabel() {
        if (slotIndex < 0) {
            return "未知";
        }
        return "卡" + (slotIndex + 1);
    }

    public String timeLabel() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(timeMillis));
    }

    public String render(String tpl, String device) {
        String t = tpl == null ? "" : tpl;
        t = t.replace("{sender}", sender)
                .replace("{content}", content)
                .replace("{time}", timeLabel())
                .replace("{slot}", slotLabel())
                .replace("{device}", device == null ? "" : device);
        return t;
    }
}
