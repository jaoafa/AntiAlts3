package com.jaoafa.AntiAlts3.Event;

import java.net.InetAddress;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.common.net.InternetDomainName;
import com.jaoafa.AntiAlts3.AntiAlts3;
import com.jaoafa.AntiAlts3.AntiAltsPlayer;
import com.jaoafa.AntiAlts3.Discord;
import com.jaoafa.AntiAlts3.MySQL;
import com.jaoafa.AntiAlts3.PermissionsManager;

public class Event_AsyncPreLogin implements Listener {
	JavaPlugin plugin;

	public Event_AsyncPreLogin(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	/*
	 * ・データベースには、最新のプレイヤー名|UUID|AntiAlts固有のユーザーID|IP|Host|ドメイン|ベースドメイン|初回ログイン日時|最終ログイン日時|そのIPでの最終ログイン日時|データ挿入日時を記録
	 * ・UUIDが同じならばそれらのデータのAntiAlts固有のユーザーIDは同じでなければならない
	 * ・IPが同じならばそれらのデータのAntiAlts固有のユーザーIDは同じでなければならない
	 * ・データベースのデータはIPとUUIDが同一なデータが二つ以上あってはならない
	 *
	 * 1. ログイン試行 AsyncPlayerPreLoginEvent
	 *    各種情報をイベントデータから取得。ドメインをInternetDomainNameから、ベースドメインを後述の方法で出す
	 * 2. UUIDをMojangAPIから取得
	 * 3. UUIDが合致するデータ(プレイヤーデータ)がantialtsのデータベーステーブルにあるかどうか調べる。あればAntiAltsUserID取得
	 * 4. UUIDが同じでMinecraftIDがデータベースのデータと違ったらデータベース更新。Discord通知
	 * 5. データベースのプレイヤーデータのLastLogin(User, IP)更新。
	 * 6. ignoreの対象であれば以降の処理をしない。ログイン許可 -> return.
	 * 7. 同一AntiAltsUserIDをリスト化し、メインアカウント(一番最初)のUUIDに合うかどうか(→合わなければNG)。メインアカウントの判定方法は後述
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
	 * ベースドメイン
	 * トップドメインから逆順に調べる。数字が入っていたらそれ以前までのドメインを「ベースドメイン」とする。
	 * 例: 4.3.2.1.a.b.c.jp -> a.b.c.jp
	 *
	 * antialts_ignoreに登録されている場合、そのアカウントは判定しない。
	 */
	@EventHandler(priority = EventPriority.HIGHEST)
	public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
		boolean loginOK = true;
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
		if (BaseDomain != null)
			plugin.getLogger().info("BASEDOMAIN: " + BaseDomain.toString());

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
		plugin.getLogger().info("AntiAltsUserID: " + AntiAltsUserID);

		// 6. ignoreの対象であれば以降の処理をしない。ログイン許可

		if (isIgnoreUser(uuid)) {
			plugin.getLogger().info("This user ignored. login allowed.");
			return;
		}

		// 7. 同一AntiAltsUserIDをリスト化し、メインアカウントのUUIDに合うかどうか(→合わなければNG)。
		// antialts_mainに登録されている場合、同一AntiAltsUserIDは全てantialts_mainに登録されているアカウントをメインとする。
		// そうでない場合、同一AntiAltsUserIDのリストを取得して1番目をメインとする。(idが一番小さいもの)
		AntiAltsPlayer MainAccount = getMainUUID(AntiAltsUserID);
		String MainAltID = name;
		UUID MainAltUUID = uuid;
		if (MainAccount != null) {
			MainAltID = MainAccount.getName();
			MainAltUUID = MainAccount.getUniqueId();
		}
		plugin.getLogger().info("MainAltID: " + MainAltID);

		if (!uuid.equals(MainAltUUID)) {
			// メインアカウントではない
			plugin.getLogger().info("This account is not MainAccount. (MainAccount: " + MainAltID + ")");
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
			loginOK = false;
			plugin.getLogger().info("Login disallowed.");
		}

		// 8. 同一IPな一覧を取得。非同一UUIDがあったらNG。
		int IdenticalIPUsersCount = getIdenticalIPUsersCount(address, uuid);
		plugin.getLogger().info("IdenticalIPUsersCount: " + IdenticalIPUsersCount);
		if (loginOK && IdenticalIPUsersCount != 0) {
			// AntiAltsUserIDが一緒になるべき / 一番小さいAntiAltsUserIDへ変更
			int i = getIdenticalIPSmallestID_And_SetID(address, uuid);
			plugin.getLogger().info("getIdenticalIPSmallestID_And_SetID: " + i);
			if (i == -1) {
				AntiAltsUserID = i;
				plugin.getLogger().info("change AntiAltsUserID: " + AntiAltsUserID);
			}

			AntiAltsPlayer IdenticalIPMainAccount = getMainUUID(AntiAltsUserID);
			String IdenticalIPMainAltID = name;
			UUID IdenticalIPMainAltUUID = uuid;
			if (IdenticalIPMainAccount != null) {
				IdenticalIPMainAltID = IdenticalIPMainAccount.getName();
				IdenticalIPMainAltUUID = IdenticalIPMainAccount.getUniqueId();
			}
			plugin.getLogger().info("IdenticalIPMainAltID: " + IdenticalIPMainAltID);
			if (!uuid.equals(IdenticalIPMainAltUUID)) {
				plugin.getLogger().info(
						"This account is not MainAccount. (MainAccount: " + IdenticalIPMainAltID + " | IdenticalIP)");
				String message = ChatColor.RED + "----- ANTI ALTS -----\n"
						+ ChatColor.RESET + ChatColor.WHITE + "あなたは以下のアカウントで既にログインをされたことがあるようです。(2)\n"
						+ ChatColor.RESET + ChatColor.AQUA + IdenticalIPMainAltID + " ("
						+ IdenticalIPMainAltUUID.toString() + ")\n"
						+ ChatColor.RESET + ChatColor.WHITE
						+ "もしこの判定が誤判定と思われる場合は、公式Discord#supportでお問い合わせをお願い致します。";
				event.disallow(Result.KICK_BANNED, message);
				for (Player p : Bukkit.getServer().getOnlinePlayers()) {
					String group = PermissionsManager.getPermissionMainGroup(p);
					if (group.equalsIgnoreCase("Admin") || group.equalsIgnoreCase("Moderator")) {
						p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + name + ": サブアカウントログイン規制(2 - メイン: "
								+ IdenticalIPMainAltID + ")");
					}
				}
				Discord.send("597423444501463040",
						"__**[AntiAlts3]**__ `" + name + "`: サブアカウントログイン規制(2 - メイン: " + IdenticalIPMainAltID + ")");
			}
		}

		if (isNeedINSERT(uuid, address)) {
			try {
				PreparedStatement statement_insert = MySQL.getNewPreparedStatement(
						"INSERT INTO antialts_new (player, uuid, userid, ip, host, domain, basedomain, firstlogin, lastlogin, iplastlogin) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
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

		setIPLastLogin(uuid, address);
		setLastLogin(uuid);
		setFirstLogin(uuid);

		// 9. ログイン許可？
		if (!loginOK) {
			return;
		}

		// 10. 同一AntiAltsUserIDのプレイヤーリストを管理部・モデレーター・常連に表示(Discordにも。)
		Set<AntiAltsPlayer> IdenticalUserIDPlayers = getUsers(AntiAltsUserID, uuid);
		if (!IdenticalUserIDPlayers.isEmpty()) {
			List<String> names = IdenticalUserIDPlayers.stream().map(_player -> _player.getName())
					.collect(Collectors.toList());
			for (Player p : Bukkit.getServer().getOnlinePlayers()) {
				String group = PermissionsManager.getPermissionMainGroup(p);
				if (group.equalsIgnoreCase("Admin") || group.equalsIgnoreCase("Moderator")
						|| group.equalsIgnoreCase("Regular")) {
					p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + "|-- " + name + " : - : サブアカウント情報 --|");
					p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + "このプレイヤーには、以下、" + IdenticalUserIDPlayers.size()
							+ "個見つかっています。");
					p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + String.join(", ", names));
				}
			}
			Discord.send("619637580987760656", "__**[AntiAlts3]**__ `" + name + "` : - : サブアカウント情報\n"
					+ "このプレイヤーには、以下、" + IdenticalUserIDPlayers.size() + "個のアカウントが見つかっています。\n"
					+ String.join(", ", names));
		}

		// 11. 同一ドメインの非同一UUIDで、48h以内にラストログインしたプレイヤーをリスト化。管理部・モデレーターに出力
		Set<AntiAltsPlayer> IdenticalBaseDomainPlayers = getUsers(BaseDomain, uuid);
		if (!IdenticalBaseDomainPlayers.isEmpty()) {
			List<String> names = IdenticalBaseDomainPlayers.stream().map(_player -> _player.getName())
					.collect(Collectors.toList());
			for (Player p : Bukkit.getServer().getOnlinePlayers()) {
				String group = PermissionsManager.getPermissionMainGroup(p);
				if (group.equalsIgnoreCase("Admin") || group.equalsIgnoreCase("Moderator")) {
					p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + "|-- " + name + " : - : 同一ベースドメイン情報 --|");
					p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + "このプレイヤードメインと同一のプレイヤーが"
							+ IdenticalBaseDomainPlayers.size()
							+ "個見つかっています。");
					p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + String.join(", ", names));
				}
			}
			Discord.send("619637580987760656",
					"__**[AntiAlts3]**__ `" + name + "` : - : 同一ベースドメイン情報 (`" + BaseDomain.toString() + "`)\n"
							+ "このプレイヤードメインと同一のプレイヤーが" + IdenticalBaseDomainPlayers.size()
							+ "個見つかっています。\n"
							+ String.join(", ", names));
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
				int userid = res.getInt("userid");
				statement.close();
				return userid;
			} else {
				statement.close();
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
				String player = res.getString("player");
				statement.close();
				return player;
			}
			statement.close();
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
	AntiAltsPlayer getMainUUID(int AntiAltsUserID) {
		try {
			// antialts_mainに登録されている場合、同一AntiAltsUserIDは全てantialts_mainに登録されているアカウントをメインとする。
			PreparedStatement statement = MySQL.getNewPreparedStatement("SELECT * FROM antialts_main WHERE userid = ?");
			statement.setInt(1, AntiAltsUserID);
			ResultSet res = statement.executeQuery();
			if (res.next()) {
				String name = res.getString("player");
				UUID uuid = UUID.fromString(res.getString("uuid"));
				statement.close();
				return new AntiAltsPlayer(name, uuid);
				//return Bukkit.getOfflinePlayer(UUID.fromString(res.getString("uuid")));
			}

			// そうでない場合、同一AntiAltsUserIDのリストを取得して1番目をメインとする。(idが一番小さいもの)
			PreparedStatement statement_useridlist = MySQL
					.getNewPreparedStatement("SELECT * FROM antialts_new WHERE userid = ? ORDER BY id ASC");
			statement_useridlist.setInt(1, AntiAltsUserID);
			ResultSet useridlist_res = statement_useridlist.executeQuery();
			if (useridlist_res.next()) {
				String name = useridlist_res.getString("player");
				UUID uuid = UUID.fromString(useridlist_res.getString("uuid"));
				statement.close();
				return new AntiAltsPlayer(name, uuid);
				//return Bukkit.getOfflinePlayer(UUID.fromString(useridlist_res.getString("uuid")));
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
			PreparedStatement statement_updatelastlogin = MySQL
					.getNewPreparedStatement("UPDATE antialts_new SET lastlogin = CURRENT_TIMESTAMP WHERE uuid = ?");
			statement_updatelastlogin.setString(1, uuid.toString());
			statement_updatelastlogin.executeUpdate();
		} catch (ClassNotFoundException | SQLException e) {
			AntiAlts3.report(e);
		}
	}

	/**
	 * 指定したIPに合致するすべてのデータに対して現在の時刻を設定します。
	 * @param uuid 対象のUUID
	 */
	void setIPLastLogin(UUID uuid, InetAddress address) {
		try {
			PreparedStatement statement_updatelastlogin = MySQL
					.getNewPreparedStatement("UPDATE antialts_new SET iplastlogin = CURRENT_TIMESTAMP WHERE ip = ?");
			statement_updatelastlogin.setString(1, address.getHostAddress());
			statement_updatelastlogin.executeUpdate();
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
					.getNewPreparedStatement("SELECT * FROM antialts_new ORDER BY id DESC LIMIT 1");
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

	@Nonnull
	Set<AntiAltsPlayer> getUsers(int AntiAltsUserID, UUID exceptUUID) {
		try {
			PreparedStatement statement = MySQL
					.getNewPreparedStatement("SELECT * FROM antialts_new WHERE userid = ?");
			statement.setInt(1, AntiAltsUserID);
			ResultSet res = statement.executeQuery();
			Set<AntiAltsPlayer> players = new HashSet<>();
			while (res.next()) {
				String name = res.getString("player");
				UUID uuid = UUID.fromString(res.getString("uuid"));
				if (uuid.equals(exceptUUID)) {
					continue;
				}
				AntiAltsPlayer player = new AntiAltsPlayer(name, uuid);
				List<AntiAltsPlayer> filtered = players.stream().filter(
						_player -> _player != null && _player.getUniqueId().equals(player.getUniqueId()))
						.collect(Collectors.toList());
				if (!filtered.isEmpty()) {
					continue;
				}
				players.add(player);
			}
			return players;
		} catch (ClassNotFoundException | SQLException e) {
			AntiAlts3.report(e);
			return new HashSet<>();
		}
	}

	@Nonnull
	Set<AntiAltsPlayer> getUsers(InternetDomainName BaseDomain, UUID exceptUUID) {
		try {
			PreparedStatement statement = MySQL
					.getNewPreparedStatement(
							"SELECT * FROM antialts_new WHERE basedomain = ? AND DATE_ADD(date, INTERVAL 2 DAY) > NOW()");
			statement.setString(1, BaseDomain.toString());
			ResultSet res = statement.executeQuery();
			Set<AntiAltsPlayer> players = new HashSet<>();
			while (res.next()) {
				String name = res.getString("player");
				UUID uuid = UUID.fromString(res.getString("uuid"));
				if (uuid.equals(exceptUUID)) {
					continue;
				}
				AntiAltsPlayer player = new AntiAltsPlayer(name, uuid);
				List<AntiAltsPlayer> filtered = players.stream().filter(
						_player -> _player != null && _player.getUniqueId().equals(player.getUniqueId()))
						.collect(Collectors.toList());
				if (!filtered.isEmpty()) {
					continue;
				}
				players.add(player);
			}
			return players;
		} catch (ClassNotFoundException | SQLException e) {
			AntiAlts3.report(e);
			return new HashSet<>();
		}
	}
}