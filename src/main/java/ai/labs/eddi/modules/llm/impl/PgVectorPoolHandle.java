/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.impl.PgVectorConnectionPools.Login;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * What a pgvector store holds instead of a pool: the login, resolved to its
 * current pool on every borrow — see {@link PgVectorConnectionPools}.
 * langchain4j only calls {@link #getConnection()}.
 */
final class PgVectorPoolHandle implements DataSource {

    private final PgVectorConnectionPools pools;
    private final String key;
    private final Login login;

    PgVectorPoolHandle(PgVectorConnectionPools pools, String key, Login login) {
        this.pools = pools;
        this.key = key;
        this.login = login;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return pools.connection(key, login);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException("a pgvector pool connects with the knowledge base's own login");
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        // no log writer: the pools log through JBoss logging
    }

    @Override
    public void setLoginTimeout(int seconds) {
        // the pool's acquisition timeout applies
    }

    @Override
    public int getLoginTimeout() {
        return (int) PgVectorConnectionPools.ACQUISITION_TIMEOUT.toSeconds();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
