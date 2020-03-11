package com.jaoafa.AntiAlts3;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import com.mysql.jdbc.exceptions.jdbc4.CommunicationsException;

/**
 * Connects to and uses a MySQL database
 *
 * @author -_Husky_-
 * @author tips48
 */
public class MySQL extends Database {
	private final String user;
	private final String database;
	private final String password;
	private final String port;
	private final String hostname;

	/**
	 * Creates a new MySQL instance
	 *
	 * @param hostname
	 *            Name of the host
	 * @param port
	 *            Port number
	 * @param username
	 *            Username
	 * @param password
	 *            Password
	 */
	public MySQL(String hostname, String port, String username,
			String password) {
		this(hostname, port, null, username, password);
	}

	/**
	 * Creates a new MySQL instance for a specific database
	 *
	 * @param hostname
	 *            Name of the host
	 * @param port
	 *            Port number
	 * @param database
	 *            Database name
	 * @param username
	 *            Username
	 * @param password
	 *            Password
	 */
	public MySQL(String hostname, String port, String database,
			String username, String password) {
		this.hostname = hostname;
		this.port = port;
		this.database = database;
		this.user = username;
		this.password = password;
	}

	@Override
	public Connection openConnection() throws SQLException,
			ClassNotFoundException {
		if (checkConnection()) {
			return connection;
		}

		String connectionURL = "jdbc:mysql://"
				+ this.hostname + ":" + this.port;
		if (database != null) {
			connectionURL = connectionURL + "/" + this.database
					+ "?autoReconnect=true&useUnicode=true&characterEncoding=utf8";
		}

		Class.forName("com.mysql.jdbc.Driver");
		connection = DriverManager.getConnection(connectionURL,
				this.user, this.password);
		return connection;
	}

	/**
	 * 新しいPreparedStatementを返します。
	 * @return 新しいPreparedStatement
	 * @throws SQLException 新しいPreparedStatementの取得中にSQLExceptionが発生した場合
	 * @throws ClassNotFoundException 新しいPreparedStatementの取得中にClassNotFoundExceptionが発生した場合
	 */
	public static PreparedStatement getNewPreparedStatement(String sql) throws SQLException, ClassNotFoundException {
		PreparedStatement statement;
		try {
			if (((System.currentTimeMillis() / 1000L) - AntiAlts3.ConnectionCreate) >= 3600) {
				// com.mysql.jdbc.exceptions.jdbc4.CommunicationsExceptionの発生を防ぐため、最後にコネクションを作成したときから1時間以上経っていればコネクションを作り直す。
				MySQL MySQL = new MySQL(AntiAlts3.sqlserver, "3306", "jaoafa", AntiAlts3.sqluser,
						AntiAlts3.sqlpassword);
				try {
					AntiAlts3.c = MySQL.openConnection();
					AntiAlts3.ConnectionCreate = System.currentTimeMillis() / 1000L;
				} catch (ClassNotFoundException | SQLException e) {
					AntiAlts3.report(e);
					throw e;
				}
			}
			statement = AntiAlts3.c.prepareStatement(sql);
		} catch (NullPointerException e) {
			MySQL MySQL = new MySQL(AntiAlts3.sqlserver, "3306", "jaoafa", AntiAlts3.sqluser, AntiAlts3.sqlpassword);
			try {
				AntiAlts3.c = MySQL.openConnection();
				AntiAlts3.ConnectionCreate = System.currentTimeMillis() / 1000L;
				statement = AntiAlts3.c.prepareStatement(sql);
			} catch (ClassNotFoundException | SQLException e1) {
				// TODO 自動生成された catch ブロック
				AntiAlts3.report(e);
				throw e1;
			}
		} catch (CommunicationsException e) {
			MySQL MySQL = new MySQL(AntiAlts3.sqlserver, "3306", "jaoafa", AntiAlts3.sqluser,
					AntiAlts3.sqlpassword);
			try {
				AntiAlts3.c = MySQL.openConnection();
				AntiAlts3.ConnectionCreate = System.currentTimeMillis() / 1000L;
				statement = AntiAlts3.c.prepareStatement(sql);
			} catch (ClassNotFoundException | SQLException ex) {
				AntiAlts3.report(ex);
				throw ex;
			}
		} catch (SQLException e) {
			// TODO 自動生成された catch ブロック
			AntiAlts3.report(e);
			throw e;
		}
		return statement;
	}
}