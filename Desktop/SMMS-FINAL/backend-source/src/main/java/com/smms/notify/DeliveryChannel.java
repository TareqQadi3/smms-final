package com.smms.notify;

import java.util.Locale;

/** External notification channels. In-app notifications are not a channel here: they always work. */
public enum DeliveryChannel {
    email, sms;

    public static DeliveryChannel fromDb(String v) {
        return valueOf(v.toLowerCase(Locale.ROOT));
    }
}
