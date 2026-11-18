/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import jakarta.enterprise.inject.Instance;
import org.postgresql.util.PGobject;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The PostgreSQL storage boundary: a {@link DataSource} whose prepared
 * statements make every string parameter storable, see {@link PostgresText}.
 * <p>
 * PostgreSQL refuses U+0000 in {@code TEXT} and {@code jsonb}; the engine
 * accepts it from users, models and tools. Instead of asking each of the
 * several hundred binders in the PostgreSQL stores to remember, every store
 * wraps the data source it is given with {@link #wrap(Instance)}, and the
 * statements it prepares clean the value at the moment it is bound:
 * <ul>
 * <li>{@code setString}, {@code setNString} and {@code setObject} with a
 * {@code String} or a {@link PGobject};
 * <li>{@code createArrayOf} with {@code String} elements ({@code TEXT[]}
 * columns);
 * <li>a parameter followed by {@code ::jsonb}/{@code ::json} in the SQL, and a
 * {@link PGobject} of type {@code json}/{@code jsonb}, are treated as JSON text
 * (escaped spellings too), every other parameter as plain text.
 * </ul>
 * Statements created with {@code createStatement()} take no parameters and are
 * not touched; SQL text built from caller data must not exist.
 * <p>
 * A new PostgreSQL store adopts it with one line in its constructor:
 * {@code this.dataSourceInstance = PostgresSanitizingDataSource.wrap(dataSourceInstance);}.
 * Wrapping is idempotent.
 */
public final class PostgresSanitizingDataSource {

    private static final Pattern JSON_CAST = Pattern.compile("^\\s*::\\s*jsonb?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_CAST_FUNCTION = Pattern.compile("(?i)CAST\\s*\\(\\s*$");
    private static final Pattern JSON_CAST_AS = Pattern.compile("^\\s+AS\\s+jsonb?\\b", Pattern.CASE_INSENSITIVE);

    private PostgresSanitizingDataSource() {
    }

    /**
     * Wraps the provider of a data source. {@code get()} of the result returns the
     * sanitising data source; every other method is forwarded unchanged.
     */
    @SuppressWarnings("unchecked")
    public static Instance<DataSource> wrap(Instance<DataSource> instance) {
        if (instance == null || isOurs(instance)) {
            return instance;
        }
        return (Instance<DataSource>) Proxy.newProxyInstance(PostgresSanitizingDataSource.class.getClassLoader(), new Class<?>[]{Instance.class},
                new InstanceHandler(instance));
    }

    /**
     * Wraps a data source; {@code null} and an already wrapped one come back as
     * they are.
     */
    public static DataSource wrap(DataSource dataSource) {
        if (dataSource == null || isOurs(dataSource)) {
            return dataSource;
        }
        return (DataSource) Proxy.newProxyInstance(PostgresSanitizingDataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                new DataSourceHandler(dataSource));
    }

    /**
     * The data source behind a wrapper (the argument itself when it is not one).
     */
    public static DataSource unwrap(DataSource dataSource) {
        if (dataSource != null && Proxy.isProxyClass(dataSource.getClass())
                && Proxy.getInvocationHandler(dataSource) instanceof DataSourceHandler handler) {
            return handler.target;
        }
        return dataSource;
    }

    private static boolean isOurs(Object candidate) {
        if (!Proxy.isProxyClass(candidate.getClass())) {
            return false;
        }
        InvocationHandler handler = Proxy.getInvocationHandler(candidate);
        return handler instanceof InstanceHandler || handler instanceof DataSourceHandler || handler instanceof ConnectionHandler;
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // ── Instance<DataSource>

    private static final class InstanceHandler implements InvocationHandler {
        private final Instance<DataSource> delegate;
        private DataSource lastTarget;
        private DataSource lastWrapped;

        InstanceHandler(Instance<DataSource> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> delegate.toString();
                };
            }
            Object result = PostgresSanitizingDataSource.invoke(delegate, method, args);
            if (method.getName().equals("get") && args == null && result instanceof DataSource dataSource) {
                synchronized (this) {
                    if (dataSource != lastTarget) {
                        lastTarget = dataSource;
                        lastWrapped = wrap(dataSource);
                    }
                    return lastWrapped;
                }
            }
            return result;
        }
    }

    // ── DataSource -> Connection

    private static final class DataSourceHandler implements InvocationHandler {
        private final DataSource target;

        DataSourceHandler(DataSource target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> target.toString();
                };
            }
            Object result = PostgresSanitizingDataSource.invoke(target, method, args);
            if (method.getName().equals("getConnection") && result instanceof Connection connection) {
                return wrap(connection);
            }
            return result;
        }
    }

    /**
     * Wraps one connection, for code that borrows connections from somewhere other
     * than a {@link DataSource} it owns (the pgvector embedding store).
     */
    public static Connection wrap(Connection connection) {
        if (connection == null || isOurs(connection)) {
            return connection;
        }
        return (Connection) Proxy.newProxyInstance(PostgresSanitizingDataSource.class.getClassLoader(), new Class<?>[]{Connection.class},
                new ConnectionHandler(connection));
    }

    // ── Connection -> PreparedStatement, Array

    private static final class ConnectionHandler implements InvocationHandler {
        private final Connection target;

        ConnectionHandler(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> target.toString();
                };
            }
            String name = method.getName();
            if (name.equals("createArrayOf") && args != null && args.length == 2 && args[1] instanceof Object[] elements) {
                args = new Object[]{args[0], cleanElements(elements)};
            }
            Object result = PostgresSanitizingDataSource.invoke(target, method, args);
            if (name.equals("prepareStatement") && result instanceof PreparedStatement statement && args != null && args[0] instanceof String sql) {
                return (PreparedStatement) Proxy.newProxyInstance(PostgresSanitizingDataSource.class.getClassLoader(),
                        new Class<?>[]{PreparedStatement.class}, new PreparedStatementHandler(statement, jsonParameters(sql)));
            }
            return result;
        }

        private static Object[] cleanElements(Object[] elements) {
            Object[] cleaned = null;
            for (int i = 0; i < elements.length; i++) {
                if (elements[i] instanceof String s) {
                    String clean = PostgresText.text(s);
                    if (clean != s) {
                        if (cleaned == null) {
                            cleaned = Arrays.copyOf(elements, elements.length);
                        }
                        cleaned[i] = clean;
                    }
                }
            }
            return cleaned == null ? elements : cleaned;
        }
    }

    private static final class PreparedStatementHandler implements InvocationHandler {
        private final PreparedStatement target;
        private final BitSet jsonParameters;

        PreparedStatementHandler(PreparedStatement target, BitSet jsonParameters) {
            this.target = target;
            this.jsonParameters = jsonParameters;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> target.toString();
                };
            }
            if (args != null && args.length >= 2 && args[0] instanceof Integer index) {
                String name = method.getName();
                if ((name.equals("setString") || name.equals("setNString")) && args[1] instanceof String value) {
                    args = args.clone();
                    args[1] = clean(value, jsonParameters.get(index));
                } else if (name.equals("setObject")) {
                    if (args[1] instanceof String value) {
                        args = args.clone();
                        args[1] = clean(value, jsonParameters.get(index));
                    } else if (args[1] instanceof PGobject object) {
                        cleanObject(object);
                    }
                }
            }
            return PostgresSanitizingDataSource.invoke(target, method, args);
        }

        private static String clean(String value, boolean json) {
            return json ? PostgresText.json(value) : PostgresText.text(value);
        }

        private static void cleanObject(PGobject object) throws SQLException {
            String value = object.getValue();
            if (value == null) {
                return;
            }
            String type = object.getType();
            boolean json = type != null && (type.equalsIgnoreCase("jsonb") || type.equalsIgnoreCase("json"));
            String cleaned = clean(value, json);
            if (cleaned != value) {
                object.setValue(cleaned);
            }
        }
    }

    /**
     * 1-based indexes of the {@code ?} placeholders that the SQL casts to
     * {@code json}/{@code jsonb}. Quoted strings, quoted identifiers and comments
     * are skipped so a {@code ?} inside them is not counted.
     */
    static BitSet jsonParameters(String sql) {
        BitSet json = new BitSet();
        int parameter = 0;
        int length = sql.length();
        int i = 0;
        while (i < length) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(sql, i, c);
            } else if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? length : end + 1;
            } else if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else if (c == '?') {
                parameter++;
                String after = sql.substring(i + 1, Math.min(length, i + 24));
                Matcher cast = JSON_CAST.matcher(after);
                boolean asJson = cast.find();
                if (!asJson) {
                    String before = sql.substring(Math.max(0, i - 12), i);
                    asJson = JSON_CAST_FUNCTION.matcher(before).find() && JSON_CAST_AS.matcher(after.toLowerCase(Locale.ROOT)).find();
                }
                if (asJson) {
                    json.set(parameter);
                }
                i++;
            } else {
                i++;
            }
        }
        return json;
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        int length = sql.length();
        while (i < length) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < length && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return length;
    }
}
