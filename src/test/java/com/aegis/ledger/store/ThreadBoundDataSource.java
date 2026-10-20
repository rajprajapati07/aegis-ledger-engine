package com.aegis.ledger.store;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Bare-minimum pool for load tests: each thread keeps one physical connection
 * and close() just hands it back to that thread. Without it, the concurrency
 * tests spend most of their time on TCP + auth handshakes instead of on
 * contending for row locks, which is the thing we actually want to measure.
 *
 * In the running service Hikari does this properly.
 */
final class ThreadBoundDataSource implements DataSource, AutoCloseable {

    private final DataSource target;
    private final ThreadLocal<Connection> bound = new ThreadLocal<>();
    private final List<Connection> all = new CopyOnWriteArrayList<>();

    ThreadBoundDataSource(DataSource target) {
        this.target = target;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection physical = bound.get();
        if (physical == null || physical.isClosed()) {
            physical = target.getConnection();
            bound.set(physical);
            all.add(physical);
        }
        Connection conn = physical;
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        if (!conn.getAutoCommit()) {
                            conn.rollback(); // never hand a half-open transaction to the next caller
                            conn.setAutoCommit(true);
                        }
                        return null;
                    }
                    try {
                        return method.invoke(conn, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Override
    public void close() {
        for (Connection c : all) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // test teardown
            }
        }
    }

    @Override
    public Connection getConnection(String username, String password) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return target.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        target.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        target.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return target.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return target.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return target.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return target.isWrapperFor(iface);
    }
}
