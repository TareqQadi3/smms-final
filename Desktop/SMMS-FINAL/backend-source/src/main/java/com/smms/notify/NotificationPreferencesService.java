package com.smms.notify;

import com.smms.entity.User;
import com.smms.exception.WorkflowException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Per-user choice of external channels. Always the caller's own row. */
@Service
public class NotificationPreferencesService {

    public record Preferences(boolean emailEnabled, boolean smsEnabled, List<String> types,
                              boolean emailAvailable, boolean smsAvailable) {}

    public record Update(boolean emailEnabled, boolean smsEnabled, List<String> types) {}

    private static final Pattern TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{1,49}$");

    private final NamedParameterJdbcTemplate jdbc;
    private final ProviderRegistry providers;

    public NotificationPreferencesService(NamedParameterJdbcTemplate jdbc, ProviderRegistry providers) {
        this.jdbc = jdbc;
        this.providers = providers;
    }

    @Transactional(readOnly = true)
    public Preferences get(User actor) {
        var rows = jdbc.query("SELECT email_enabled, sms_enabled, types FROM notification_preferences WHERE user_id = :u",
            new MapSqlParameterSource("u", actor.getId()), (rs, i) -> {
                Array a = rs.getArray("types");
                List<String> types = null;
                if (a != null) types = List.of((String[]) a.getArray());
                return new Preferences(rs.getBoolean("email_enabled"), rs.getBoolean("sms_enabled"), types, false, false);
            });
        Preferences p = rows.isEmpty() ? new Preferences(false, false, null, false, false) : rows.get(0);
        return withAvailability(p);
    }

    @Transactional
    public Preferences update(User actor, Update req) {
        if (actor.getTenantId() == null) {
            throw new WorkflowException("Platform accounts have no tenant and cannot use external channels");
        }
        List<String> types = normalizeTypes(req.types());
        if (req.emailEnabled() && !SmtpEmailProvider.isValidAddress(actor.getEmail() == null ? null : actor.getEmail().trim())) {
            throw new WorkflowException("Your account has no valid email address");
        }
        if (req.smsEnabled() && (actor.getPhone() == null || actor.getPhone().replaceAll("[^0-9]", "").length() < 7)) {
            throw new WorkflowException("Your account has no phone number for SMS");
        }
        // enabled_at is set when a channel turns on (or was never set) and cleared when it turns off
        jdbc.update("""
            INSERT INTO notification_preferences (user_id, tenant_id, email_enabled, sms_enabled, email_enabled_at, sms_enabled_at, types)
            VALUES (:u, :t, :e, :s, CASE WHEN :e THEN now() END, CASE WHEN :s THEN now() END, :types)
            ON CONFLICT (user_id) DO UPDATE SET
              email_enabled = :e, sms_enabled = :s, types = :types,
              email_enabled_at = CASE WHEN NOT :e THEN NULL
                                      WHEN notification_preferences.email_enabled AND notification_preferences.email_enabled_at IS NOT NULL
                                           THEN notification_preferences.email_enabled_at ELSE now() END,
              sms_enabled_at = CASE WHEN NOT :s THEN NULL
                                    WHEN notification_preferences.sms_enabled AND notification_preferences.sms_enabled_at IS NOT NULL
                                         THEN notification_preferences.sms_enabled_at ELSE now() END
            """, new MapSqlParameterSource().addValue("u", actor.getId()).addValue("t", actor.getTenantId())
                .addValue("e", req.emailEnabled()).addValue("s", req.smsEnabled())
                .addValue("types", types == null ? null : types.toArray(new String[0]), java.sql.Types.ARRAY));
        return withAvailability(new Preferences(req.emailEnabled(), req.smsEnabled(), types, false, false));
    }

    /** null = all types; otherwise 1..50 distinct upper-case codes. */
    static List<String> normalizeTypes(List<String> raw) {
        if (raw == null) return null;
        if (raw.isEmpty()) throw new IllegalArgumentException("types must be omitted (all) or contain 1 to 50 entries");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String t : raw) {
            if (t == null || !TYPE.matcher(t.trim()).matches()) throw new IllegalArgumentException("Invalid notification type");
            out.add(t.trim());
        }
        if (out.size() > 50) throw new IllegalArgumentException("Too many types");
        return List.copyOf(out);
    }

    private Preferences withAvailability(Preferences p) {
        return new Preferences(p.emailEnabled(), p.smsEnabled(), p.types(),
            providers.forChannel(DeliveryChannel.email).available(), providers.forChannel(DeliveryChannel.sms).available());
    }

}
