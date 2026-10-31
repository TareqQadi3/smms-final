package com.smms.it;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connection settings of the opt-in database tests. Everything comes from the environment and there
 * is NEVER a default URL: a developer's real database can not be hit by accident.
 *
 * <pre>
 *   SMMS_IT_DB_URL       jdbc:postgresql://localhost:55601/smms_it_fresh
 *   SMMS_IT_DB_USER      throwaway superuser (needs CREATEDB for the upgrade test)
 *   SMMS_IT_DB_PASSWORD  optional (trust auth on a local throwaway cluster needs none)
 * </pre>
 *
 * Safety guards (a violation fails the test run instead of skipping): the host must be loopback and
 * the database name must start with {@code smms_it}.
 */
public final class DbItSupport {

    public static final String ENV_URL = "SMMS_IT_DB_URL";
    public static final String ENV_USER = "SMMS_IT_DB_USER";
    public static final String ENV_PASSWORD = "SMMS_IT_DB_PASSWORD";

    private static final Pattern JDBC = Pattern.compile("^jdbc:postgresql://([^/:?]+)(?::(\\d+))?/([^?]+)(\\?.*)?$");
    private static final Pattern DB_NAME = Pattern.compile("^smms_it[a-z0-9_]*$");

    public record Target(String url, String host, int port, String database, String user, String password) {
        /** Same server, other database. */
        public String urlFor(String otherDatabase) {
            return "jdbc:postgresql://" + host + ":" + port + "/" + otherDatabase;
        }
    }

    private DbItSupport() {}

    public static boolean configured() {
        String url = System.getenv(ENV_URL);
        return url != null && !url.isBlank();
    }

    public static Target target() {
        String url = System.getenv(ENV_URL);
        if (url == null || url.isBlank()) throw new IllegalStateException(ENV_URL + " is not set");
        Matcher m = JDBC.matcher(url.trim());
        if (!m.matches()) throw new IllegalStateException(ENV_URL + " must look like jdbc:postgresql://localhost:PORT/smms_it_xxx");
        String host = m.group(1);
        if (!(host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"))) {
            throw new IllegalStateException("Refusing to run database tests against non-local host: " + host);
        }
        String db = m.group(3);
        if (!DB_NAME.matcher(db).matches()) {
            throw new IllegalStateException("Refusing to run database tests against database '" + db
                + "': the name must start with smms_it (throwaway databases only)");
        }
        int port = m.group(2) == null ? 5432 : Integer.parseInt(m.group(2));
        String user = System.getenv(ENV_USER);
        if (user == null || user.isBlank()) throw new IllegalStateException(ENV_USER + " is not set");
        String pw = System.getenv(ENV_PASSWORD);
        return new Target(url.trim(), host, port, db, user, pw == null ? "" : pw);
    }

    /** Guard for databases a test creates itself. */
    public static void requireThrowawayName(String database) {
        if (!DB_NAME.matcher(database).matches()) {
            throw new IllegalStateException("Not a throwaway database name: " + database);
        }
    }

}
