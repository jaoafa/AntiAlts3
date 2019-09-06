package com.jaoafa.AntiAlts3.Event;

import java.net.InetAddress;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.common.net.InternetDomainName;
import com.jaoafa.AntiAlts3.AntiAlts3;
import com.jaoafa.AntiAlts3.Discord;
import com.jaoafa.AntiAlts3.MySQL;
import com.jaoafa.AntiAlts3.PermissionsManager;

public class Event_AsyncPreLogin implements Listener {
	JavaPlugin plugin;

	public Event_AsyncPreLogin(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	/*
	 * 1. ログイン試行 AsyncPlayerPreLoginEvent
	 * 2. UUIDをMojangAPIから取得
	 * 3. UUIDが合致するデータ(プレイヤーデータ)がantialtsのデータベーステーブルにあるかどうか調べる。あればAntiAltsUserID取得
	 * 4. UUIDが同じでMinecraftIDがデータベースのデータと違ったらデータベース更新。Discord通知
	 * 5. データベースのプレイヤーデータのLastLogin更新
	 * 6. ignoreの対象であれば以降の処理をしない。ログイン許可
	 * 7. 同一AntiAltsUserIDをリスト化し、メインアカウントのUUIDに合うかどうか(→合わなければNG)。メインアカウントの判定方法は後述
	 * 8. 同一IPな一覧を取得。非同一UUIDがあったらNG。
	 * 9. ログイン許可？
	 * 10. 同一IPからログインしてきたプレイヤーリストを管理部・モデレーター・常連に表示(Discordにも。)
	 * 11. 同一ドメインの非同一UUIDで、48h以内にラストログインしたプレイヤーをリスト化。管理部・モデレーターに出力
	 *
	 * IPが違ったら基本INSERT
	 *
	 * AntiAltsUserID: 基本的に自動採番のidを使用。同一UUIDならば同一AntiAltsUserIDでなければならない。同一IPならば同一AntiAltsUserIDでなければならない。
	 *
	 * メインアカウントの判定方法
	 * antialts_mainに登録されている場合、同一AntiAltsUserIDは全てantialts_mainに登録されているアカウントをメインとする。
	 * そうでない場合、同一AntiAltsUserIDのリストを取得して1番目をメインとする。(idが一番小さいもの)
	 *
	 * antialts_ignoreに登録されている場合、そのアカウントは判定しない。
	 */
	@EventHandler(priority = EventPriority.HIGHEST)
	public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
		// 1. ログイン試行 AsyncPlayerPreLoginEvent

		String name = event.getName();
		InetAddress address = event.getAddress();
		String ip = address.getHostAddress();
		String host = address.getHostName();
		String domain = "";
		InternetDomainName BaseDomain = null;
		if (!ip.equalsIgnoreCase(host) && InternetDomainName.from(host).hasPublicSuffix()) {
			domain = InternetDomainName.from(host).topPrivateDomain().toString();
			BaseDomain = InternetDomainName.from(host);
			while (BaseDomain.hasParent()) {
				if (BaseDomain.toString().replaceAll(BaseDomain.parent().toString(), "")
						.matches(".*[0-9].*")) {
					BaseDomain = BaseDomain.parent();
				} else {
					break;
				}
				if (BaseDomain.toString().equals(domain)) {
					break;
				}
			}
		}

		plugin.getLogger().info("Name: " + name);
		plugin.getLogger().info("IP: " + ip);
		plugin.getLogger().info("HOST: " + host);
		plugin.getLogger().info("DOMAIN: " + domain);

		// 2. UUIDをMojangAPIから取得
		UUID uuid = AntiAlts3.getUUID(name);

		// 3. UUIDが合致するデータ(プレイヤーデータ)がantialtsのデータベーステーブルにあるかどうか調べる。あればAntiAltsUserID取得
		int AntiAltsUserID = getAntiAltsUserID(uuid);
		if (AntiAltsUserID != -1) {
			// 4. UUIDが同じでMinecraftIDがデータベースのデータと違ったらデータベース更新。Discord通知
			String oldName = changePlayerID(uuid, name);
			if (oldName != null) {
				plugin.getLogger().info("The player ID has been changed. (" + oldName + " -> " + name + ")");

				for (Player p : Bukkit.getServer().getOnlinePlayers()) {
					String group = PermissionsManager.getPermissionMainGroup(p);
					if (group.equalsIgnoreCase("Admin") || group.equalsIgnoreCase("Moderator")
							|| group.equalsIgnoreCase("Regular")) {
						p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + "|-- " + name + " : - : プレイヤー名変更情報 --|");
						p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + "このプレイヤーは、前回ログインからプレイヤー名を変更しています。(旧名: "
								+ oldName + ")");
					}
				}
				Discord.send("597423444501463040", "__**[AntiAlts3]**__ `" + name + "` : - : プレイヤー名変更情報\n"
						+ "このプレイヤーは、前回ログインからプレイヤー名を変更しています。(旧名: " + oldName + ")\n"
						+ "https://ja.namemc.com/profile/" + uuid.toString());

				// 5. データベースのプレイヤーデータのLastLogin更新
				changeLastLogin(uuid);
			} else {
				plugin.getLogger().info("The player ID has not been changed.");
			}
		} else {
			AntiAltsUserID = getLastID() + 1;
		}

		// 6. ignoreの対象であれば以降の処理をしない。ログイン許可

		if (isIgnoreUser(uuid)) {
			return;
		}

		// 7. 同一AntiAltsUserIDをリスト化し、メインアカウントのUUIDに合うかどうか(→合わなければNG)。
		// antialts_mainに登録されている場合、同一AntiAltsUserIDは全てantialts_mainに登録されているアカウントをメインとする。
		// そうでない場合、同一AntiAltsUserIDのリストを取得して1番目をメインとする。(idが一番小さいもの)
		OfflinePlayer MainAccount = getMainUUID(AntiAltsUserID);
		String MainAltID = name;
		UUID MainAltUUID = uuid;
		if (MainAccount != null) {
			MainAltID = MainAccount.getName();
			MainAltUUID = MainAccount.getUniqueId();
		}

		if (!uuid.equals(MainAltUUID)) {
			// メインアカウントではない
			String message = ChatColor.RED + "----- ANTI ALTS -----\n"
					+ ChatColor.RESET + ChatColor.WHITE + "あなたは以下のアカウントで既にログインをされたことがあるようです。(1)\n"
					+ ChatColor.RESET + ChatColor.AQUA + MainAltID + " (" + MainAltUUID.toString() + ")\n"
					+ ChatColor.RESET + ChatColor.WHITE
					+ "もしこの判定が誤判定と思われる場合は、公式Discord#supportでお問い合わせをお願い致します。";
			event.disallow(Result.KICK_BANNED, message);
			for (Player p : Bukkit.getServer().getOnlinePlayers()) {
				String group = PermissionsManager.getPermissionMainGroup(p);
				if (group.equalsIgnoreCase("Admin") || group.equalsIgnoreCase("Moderator")) {
					p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + name + ": サブアカウントログイン規制(1 - メイン: "
							+ MainAltID + ")");
				}
			}
			Discord.send("597423444501463040",
					"__**[AntiAlts3]**__ `" + name + "`: サブアカウントログイン規制(1 - メイン: " + MainAltID + ")");
			return;
		}

		// 8. 同一IPな一覧を取得。非同一UUIDがあったらNG。
		if (getIdenticalIPUsersCount(address, uuid) != 0) {
			// AntiAltsUserIDが一緒になるべき / 一番小さいAntiAltsUserIDへ変更
			int i = getIdenticalIPSmallestID_And_SetID(address, uuid);
			if (i == -1)
				AntiAltsUserID = i;
			MainAccount = getMainUUID(AntiAltsUserID);
			MainAltID = MainAccount.getName();
			MainAltUUID = MainAccount.getUniqueId();
			if (!uuid.equals(MainAltUUID)) {
				String message = ChatColor.RED + "----- ANTI ALTS -----\n"
						+ ChatColor.RESET + ChatColor.WHITE + "あなたは以下のアカウントで既にログインをされたことがあるようです。(2)\n"
						+ ChatColor.RESET + ChatColor.AQUA + MainAltID + " (" + MainAltUUID.toString() + ")\n"
						+ ChatColor.RESET + ChatColor.WHITE
						+ "もしこの判定が誤判定と思われる場合は、公式Discord#supportでお問い合わせをお願い致します。";
				event.disallow(Result.KICK_BANNED, message);
				for (Player p : Bukkit.getServer().getOnlinePlayers()) {
					String group = PermissionsManager.getPermissionMainGroup(p);
					if (group.equalsIgnoreCase("Admin") || group.equalsIgnoreCase("Moderator")) {
						p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + name + ": サブアカウントログイン規制(2 - メイン: "
								+ MainAltID + ")");
					}
				}
				Discord.send("597423444501463040",
						"__**[AntiAlts3]**__ `" + name + "`: サブアカウントログイン規制(2 - メイン: " + MainAltID + ")");
				return;
			}
		}

		setFirstLogin(uuid);
		setLastLogin(uuid);
		if (isNeedINSERT(uuid, address)) {
			try {
				PreparedStatement statement_insert = MySQL.getNewPreparedStatement(
						"INSERT INTO antialts_new (player, uuid, userid, ip, host, domain, basedomain, firstlogin, lastlogi) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
				statement_insert.setString(1, name);
				statement_insert.setString(2, uuid.toString());
				statement_insert.setInt(3, AntiAltsUserID);
				statement_insert.setString(4, ip);
				statement_insert.setString(5, host);
				if (!domain.isEmpty()) {
					statement_insert.setString(6, domain);
				} else {
					statement_insert.setString(6, null);
				}

				if (BaseDomain != null) {
					statement_insert.setString(7, BaseDomain.toString());
				} else {
					statement_insert.setString(7, null);
				}
				statement_insert.executeUpdate();
			} catch (ClassNotFoundException | SQLException e) {
				AntiAlts3.report(e);
			}
		}
	}

	/**
	 * UUIDからAntiAltsUserIDを取得します。
	 * @param uuid 取得する元のUUID
	 * @return AntiAltsUserID　取得したAntiAltsUserID。取得できなければ-1
	 */
	@Nonnull
	int getAntiAltsUserID(UUID uuid) {
		try {
			PreparedStatement statement = MySQL.getNewPreparedStatement("SELECT * FROM antialts_new WHERE uuid = ?");
			statement.setString(1, uuid.toString());
			ResultSet res = statement.executeQuery();
			if (res.next()) {
				return res.getInt("userid");
			} else {
				return -1;
			}
		} catch (ClassNotFoundException | SQLException e) {
			AntiAlts3.report(e);
			return -1;
		}
	}

	/**
	 * データベースにある同一UUIDデータのプレイヤー名が違う項目があったときに一括で変更します。
	 * @param uuid 対象のプレイヤーのUUID
	 * @param newPlayerID 新しいPlayerID
	 * @return 古いPlayerID。変更されていなければnull
	 */
	@Nullable
	String changePlayerID(UUID uuid, String newPlayerID) {
		try {
			PreparedStatement statement = MySQL
					.getNewPreparedStatement("SELECT * FROM antialts_new WHERE uuid = ? AND player != ?");
			statement.setString(1, uuid.toString());
			statement.setString(2, newPlayerID);
			ResultSet res = statement.executeQuery();
			if (res.next()) {
				PreparedStatement statement_update = MySQL
						.getNewPreparedStatement("UPDATE antialts_new SET player = ? WHERE uuid = ?");
				statement_update.setString(1, newPlayerID);
				statement_update.setString(2, uuid.toString());
				statement_update.executeUpdate();
				return res.getString("player");
			}
			return null;
		} catch (ClassNotFoundException | SQLException e) {
			AntiAlts3.report(e);
			return null;
		}
	}

	/**
	 * 対象プレイヤーのLastLoginを変更します。
	 * @param uuid 対象のプレイヤーのUUID
	 */
	void changeLastLogin(UUID uuid) {
		try {
			PreparedStatement statement = MySQL
					.getNewPreparedStatement("UPDATE antialts_new SET lastlogin = CURRENT_TIMESTAMP WHERE uuid = ?");
			statement.setString(1, uuid.toString());
			statement.executeUpdate();
		} catch (ClassNotFoundException | SQLException e) {
			AntiAlts3.report(e);
		}
	}

	/**
	 * AntiAltsの判定対象で<b>ないか</b>どうかを返す。
	 * @param uuid 対象のプレイヤーのUUID
	 * @return 対象で<b>なければ</b>true
	 */
	boolean isIgnoreUser(UUID uuid) {
		try {
			PreparedStatement statement = MySQL.getNewPreparedStatement("SELECT * FROM antialts_ignore WHERE uuid = ?");
			statement.setString(1, uuid.toString());
			ResultSet res = statement.executeQuery();
			if (res.next()) {
				return true;
			} else {
				return false;
			}
		} catch (ClassNotFoundException | SQLException e) {
			AntiAlts3.report(e);
			return false;
		}
	}

	/**
	 * メインアカウントを取得します。
	 * @param AntiAltsUserID 対象のAntiAltsUserID
	 * @return メインアカウント
	 */
	@Nullable
	OfflinePlayer getMainUUID(int AntiAltsUserID) {
		try {
			// antialts_mainに登録されている場合、同一AntiAltsUserIDは全てantialts_mainに登録されているアカウントをメインとする。
			PreparedStatement statement = MySQL.getNewPreparedStatement("SELECT * FROM antialts_main WHERE userid = ?");
			statement.setInt(1, AntiAltsUserID);
			ResultSet res = statement.executeQuery();
			if (res.next()) {
				return Bukkit.getOfflinePlayer(UUID.fromString(res.getString("uuid")));
			}

			// そうでない場合、同一AntiAltsUserIDのリストを取得して1番目をメインとする。(idが一番小さいもの)
			PreparedStatement statement_useridlist = MySQL
					.getNewPreparedStatement("SELECT * FROM antialts_new WHERE userid = ? ORDER BY id ASC");
			statement_useridlist.setInt(1, AntiAltsUserID);
			ResultSet useridlist_res = statement_useridlist.executeQuery();
			if (useridlist_res.next()) {
				return Bukkit.getOfflinePlayer(UUID.fromString(useridlist_res.getString("uuid")));
			}
			return null;
		} catch (ClassNotFoundException | SQLException e) {
			AntiAlts3.report(e);
			return null;
		}
	}

	/**
	 * 指定したInetAddressのIPアドレスと同一なプレイヤー数を返します。exceptUUIDに指定したUUIDのプレイヤーを除外します。
	 * @param address 対象のInetAddress
	 * @param exceptUUID 除外するプレイヤーのUUID
	 * @return 指定したInetAddressのIPアドレスと同一なプレイヤー数
	 */
	int getIdenticalIPUsersCount(InetAddress address, UUID exceptUUID) {
		try {
			PreparedStatement statement = MySQL
					.getNewPreparedStatement("SELECT COUNT(*) FROM antialts_new WHERE ip = ? AND uuid != ?");
			statement.setString(1, address.getHostAddress());
			statement.setString(2, exceptUUID.toString());
			ResultSet res = statement.executeQuery();
			if (res.next()) {
				return res.getInt(1);
			}
			return 0;
		} catch (ClassNotFoundException | SQLException e) {
			AntiAlts3.report(e);
			return 0;
		}
	}

	/**
	 * 指定したInetAddressのIPアドレスと同一なデータで最も小さいIDを返します。さらに対象のすべてのデータに最も小さいIDを設定します。exceptUUIDに指定したUUIDのプレイヤーを除外します。
	 * @param address 対象のInetAddress
	 * @param exceptUUID 除外するプレイヤーのUUID
	 * @return 指定したInetAddressのIPアドレスと同一なデータで最も小さいID
	 */
	int getIdenticalIPSmallestID_And_SetID(InetAddress address, UUID exceptUUID) {
		try {
			int AntiAltsUserID = Integer.MAX_VALUE;
			PreparedStatement statement_sameIP = MySQL
					.getNewPreparedStatement("SELECT * FROM antialts_new WHERE ip = ? AND uuid != ?");
			statement_sameIP.setString(1, address.getHostAddress());
			statement_sameIP.setString(2, exceptUUID.toString());
			ResultSet res_sameIP = statement_sameIP.executeQuery();
			while (res_sameIP.next() && AntiAltsUserID > res_sameIP.getInt("userid")) {
				AntiAltsUserID = res_sameIP.getInt("userid");
			}

			// 対象のすべての行に小さいAntiAltsUserIDを設定 -> 同一
			if (AntiAltsUserID == Integer.MAX_VALUE) {
				return -1;
			}
			res_sameIP.first();
			while (res_sameIP.next()) {
				PreparedStatement statement_updateUserid = MySQL
						.getNewPreparedStatement("UPDATE antialts_new SET userid = ? WHERE id = ?");
				statement_updateUserid.setInt(1, AntiAltsUserID);
				statement_updateUserid.setInt(2, res_sameIP.getInt("id"));
				statement_updateUserid.executeUpdate();
			}

			return AntiAltsUserID;
		} catch (ClassNotFoundException | SQLException e) {
			AntiAlts3.report(e);
			return -1;
		}
	}

	/**
	 * 指定したUUIDに合致するすべてのデータに対して一番小さいFirstLoginを設定します。
	 * @param uuid 対象のUUID
	 */
	void setFirstLogin(UUID uuid) {
		try {
			Timestamp firstlogin = new Timestamp(System.currentTimeMillis());
			PreparedStatement statement_FirstLogin = MySQL
					.getNewPreparedStatement("SELECT * FROM antialts_new WHERE uuid = ?");
			statement_FirstLogin.setString(1, uuid.toString());
			ResultSet res_FirstLogin = statement_FirstLogin.executeQuery();
			while (res_FirstLogin.next()) {
				if (res_FirstLogin.getTimestamp("firstlogin").before(firstlogin)) {
					firstlogin = res_FirstLogin.getTimestamp("firstlogin");
				}
			}

			res_FirstLogin.first();
			while (res_FirstLogin.next()) {
				PreparedStatement statement_updatefirstlogin = MySQL
						.getNewPreparedStatement("UPDATE antialts_new SET firstlogin = ? WHERE id = ?");
				statement_updatefirstlogin.setTimestamp(1, firstlogin);
				statement_updatefirstlogin.setInt(2, res_FirstLogin.getInt("id"));
				statement_updatefirstlogin.executeUpdate();
			}
		} catch (ClassNotFoundException | SQLException e) {
			AntiAlts3.report(e);
		}
	}

	/**
	 * 指定したUUIDに合致するすべてのデータに対して現在の時刻を設定します。
	 * @param uuid 対象のUUID
	 */
	void setLastLogin(UUID uuid) {
		try {
			PreparedStatement statement_LastLogin = MySQL
					.getNewPreparedStatement("SELECT * FROM antialts_new WHERE uuid = ?");
			statement_LastLogin.setString(1, uuid.toString());
			ResultSet res_LastLogin = statement_LastLogin.executeQuery();
			while (res_LastLogin.next()) {
				PreparedStatement statement_updatelastlogin = MySQL
						.getNewPreparedStatement("UPDATE antialts_new SET lastlogin = CURRENT_TIMESTAMP WHERE id = ?");
				statement_updatelastlogin.setInt(1, res_LastLogin.getInt("id"));
				statement_updatelastlogin.executeUpdate();
			}
		} catch (ClassNotFoundException | SQLException e) {
			AntiAlts3.report(e);
		}
	}

	/**
	 * 登録すべきかどうかを判定します。
	 * @param uuid 対象のUUID
	 * @param address 対象のInetAddress
	 * @return
	 */
	boolean isNeedINSERT(UUID uuid, InetAddress address) {
		try {
			PreparedStatement statement_selectAlready = MySQL
					.getNewPreparedStatement("SELECT COUNT(*) FROM antialts_new WHERE uuid = ? AND ip = ?");
			statement_selectAlready.setString(1, uuid.toString());
			statement_selectAlready.setString(2, address.getHostAddress());
			ResultSet res_selectAlready = statement_selectAlready.executeQuery();
			if (res_selectAlready.next() && res_selectAlready.getInt(1) != 0) {
				return false;
			}
			return true;
		} catch (ClassNotFoundException | SQLException e) {
			AntiAlts3.report(e);
			return true;
		}
	}

	/**
	 * 最終行のIDを返却します。
	 * @return 最終行のID。取得できない場合は-1
	 */
	int getLastID() {
		try {
			PreparedStatement statement = MySQL
					.getNewPreparedStatement("SELECT * FROM antialts_new ORDER BY userid DESC LIMIT 1");
			ResultSet res = statement.executeQuery();
			if (res.next()) {
				return res.getInt(1);
			}
			return -1;
		} catch (ClassNotFoundException | SQLException e) {
			AntiAlts3.report(e);
			return -1;
		}
	}
}