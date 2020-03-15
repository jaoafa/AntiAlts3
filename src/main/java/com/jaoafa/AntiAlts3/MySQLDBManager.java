package com.jaoafa.AntiAlts3;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class MySQLDBManager {
	private final String user;
	private final String database;
	private final String password;
	private final String port;
	private final String hostname;
	private long WAIT_TIMEOUT = -1;
	private long LAST_PACKET = -1;
	Connection conn = null;

	public MySQLDBManager(String hostname, String port, String database,
			String username, String password) throws ClassNotFoundException {
		Class.forName("com.mysql.jdbc.Driver");
		this.hostname = hostname;
		this.port = port;
		this.database = database;
		this.user = username;
		this.password = password;
	}

	public Connection getConnection() throws SQLException {
		if (conn != null && !conn.isClosed() && conn.isValid(5)) {
			if (WAIT_TIMEOUT != -1 && LAST_PACKET != -1) {
				long diff = System.currentTimeMillis() - LAST_PACKET;
				if (diff < WAIT_TIMEOUT) {
					return conn;
				} else {
					System.out.println("MySQL TIMEOUT! WAIT_TIMEOUT: " + WAIT_TIMEOUT + " / DIFF: " + diff);
				}
			}
			LAST_PACKET = System.currentTimeMillis();
			return conn;
		}
		String jdbcUrl = "jdbc:mysql://" + this.hostname + ":" + this.port + "/" + this.database
				+ "?autoReconnect=true&useUnicode=true&characterEncoding=utf8";
		conn = DriverManager.getConnection(jdbcUrl, this.user, this.password);
		if (WAIT_TIMEOUT == -1) {
			getWaitTimeout();
		}
		LAST_PACKET = System.currentTimeMillis();
		conn.setHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT);
		return conn;
	}

	long getWaitTimeout() {
		try {
			Connection conn = getConnection();
			PreparedStatement statement = conn.prepareStatement("show variables like 'wait_timeout'");
			ResultSet res = statement.executeQuery();
			if (res.next()) {
				WAIT_TIMEOUT = res.getInt("Value");
				System.out.println("MySQL WAIT_TIMEOUT: " + WAIT_TIMEOUT);
			} else {
				WAIT_TIMEOUT = -1;
			}
			statement.close();
		} catch (SQLException e) {
			WAIT_TIMEOUT = -1;
		}
		return WAIT_TIMEOUT;
	}
}
