package db.migration;

import com.cqwlw.maintenance.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class V69__redact_idempotency_response_secrets extends BaseJavaMigration {

    private static final Set<String> SECRET_FIELDS = Set.of("initialPassword", "password");
    private static final Pattern INVALID_SECRET_FIELD = Pattern.compile(
            "(?<!\\\\)\"(?:initialPassword|password)\"\\s*:");

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT idem_key, response_json FROM idempotency_key");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(new String[]{result.getString(1), result.getString(2)});
            }
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE idempotency_key SET response_json = ? WHERE idem_key = ?")) {
            for (String[] row : rows) {
                String sanitized = sanitize(row[1]);
                if (sanitized == null || sanitized.equals(row[1])) {
                    continue;
                }
                update.setString(1, sanitized);
                update.setString(2, row[0]);
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("幂等口令清理未唯一定位键");
                }
            }
        }
    }

    private static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        JsonNode tree;
        try {
            tree = JsonUtil.MAPPER.readTree(raw);
        } catch (Exception e) {
            return containsSecretField(raw) ? "{\"redacted\":true}" : null;
        }
        boolean[] changed = new boolean[1];
        redact(tree, changed);
        return changed[0] ? JsonUtil.write(tree) : null;
    }

    private static void redact(JsonNode node, boolean[] changed) {
        if (node instanceof ObjectNode object) {
            for (String field : SECRET_FIELDS) {
                changed[0] |= object.remove(field) != null;
            }
            object.forEach(child -> redact(child, changed));
        } else if (node != null && node.isArray()) {
            node.forEach(child -> redact(child, changed));
        }
    }

    private static boolean containsSecretField(String raw) {
        return INVALID_SECRET_FIELD.matcher(decodeUnicodeEscapes(raw)).find();
    }

    private static String decodeUnicodeEscapes(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '\\' && i + 5 < raw.length() && raw.charAt(i + 1) == 'u') {
                String hex = raw.substring(i + 2, i + 6);
                if (hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                    out.append((char) Integer.parseInt(hex, 16));
                    i += 5;
                    continue;
                }
            }
            out.append(raw.charAt(i));
        }
        return out.toString();
    }
}
