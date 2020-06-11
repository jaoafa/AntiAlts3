package com.jaoafa.AntiAlts3;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.JSONException;
import org.json.JSONObject;

import com.jaoafa.AntiAlts3.Command.Cmd_Alts;
import com.jaoafa.AntiAlts3.Event.Event_AsyncPreLogin;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Main extends JavaPlugin {
	/**
	 * プラグインが起動したときに呼び出し
	 * @author mine_book000
	 * @since 2018/02/15
	 */
	@Override
	public void onEnable() {
		getCommand("alts").setExecutor(new Cmd_Alts(this));
		getServer().getPluginManager().registerEvents(new Event_AsyncPreLogin(this), this);

		Load_Config(); // Config Load
	}

	public static JavaPlugin JavaPlugin;
	public static MySQLDBManager MySQLDBManager = null;
	public static String sqlserver = "jaoafa.com";
	public static String sqlport = "3306";
	public static String sqluser;
	public static String sqlpassword;
	public static long ConnectionCreate = 0;
	public static FileConfiguration conf;

	/**
	 * コンフィグ読み込み
	 * @author mine_book000
	 */
	private void Load_Config() {
		conf = getConfig();

		if (conf.contains("discordtoken")) {
			Discord.start(this, conf.getString("discordtoken"));
		} else {
			getLogger().info("Discordへの接続に失敗しました。 [conf NotFound]");
			getLogger().info("Disable AntiAlts3...");
			getServer().getPluginManager().disablePlugin(this);
		}
		if (conf.contains("sqluser") && conf.contains("sqlpassword")) {
			Main.sqluser = conf.getString("sqluser");
			Main.sqlpassword = conf.getString("sqlpassword");
		} else {
			getLogger().info("MySQL Connect err. [conf NotFound]");
			getLogger().info("Disable AntiAlts3...");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}

		if (conf.contains("sqlserver")) {
			sqlserver = (String) conf.get("sqlserver");
		}

		if (conf.contains("sqlport")) {
			sqlport = (String) conf.get("sqlport");
		}

		try {
			MySQLDBManager = new MySQLDBManager(
					sqlserver,
					sqlport,
					"jaoafa",
					sqluser,
					sqlpassword);
		} catch (ClassNotFoundException e) {
			getLogger().warning("MySQLへの接続に失敗しました。(MySQL接続するためのクラスが見つかりません)");
			getLogger().warning("AntiAlts3プラグインを終了します。");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}

		JavaPlugin = this;
	}

	/**
	 * プラグインが停止したときに呼び出し
	 * @author mine_book000
	 * @since 2018/02/15
	 */
	@Override
	public void onDisable() {

	}

	public static void SendMessage(CommandSender sender, Command cmd, String text) {
		sender.sendMessage("[AntiAlts3] " + ChatColor.YELLOW + text);
	}

	public static void report(Throwable exception) {
		exception.printStackTrace();
		for (Player p : Bukkit.getServer().getOnlinePlayers()) {
			String group = PermissionsManager.getPermissionMainGroup(p);
			if (group.equalsIgnoreCase("Admin") || group.equalsIgnoreCase("Moderator")) {
				p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + "AntiAlts3のシステム障害が発生しました。");
				p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + "エラー: " + exception.getMessage());
			}
		}
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		exception.printStackTrace(pw);
		Discord.send("618569153422426113", "AntiAlts3でエラーが発生しました。" + "\n"
				+ "```" + sw.toString() + "```\n"
				+ "Cause: `" + exception.getCause() + "`");
	}

	public static UUID getUUID(String name) {
		// https://api.mojang.com/users/profiles/minecraft/
		JSONObject json = getHttpJson("https://api.mojang.com/users/profiles/minecraft/" + name);
		if (json == null) {
			return null;
		} else if (json.has("id")) {
			String uuid_hyphenated = new StringBuilder(json.getString("id"))
					.insert(8, "-")
					.insert(13, "-")
					.insert(18, "-")
					.insert(23, "-")
					.toString();
			UUID uuid = UUID.fromString(uuid_hyphenated);
			return uuid;
		} else {
			return null;
		}
	}

	public static UUID getUUIDByDB(String name) {
		JSONObject json = getHttpJson("https://api.jaoafa.com/users/" + name);
		if (json == null) {
			return null;
		} else if (json.has("data")) {
			String uuid_hyphenated = new StringBuilder(json.getJSONObject("data").getString("uuid"))
					.toString();
			UUID uuid = UUID.fromString(uuid_hyphenated);
			return uuid;
		} else {
			return null;
		}
	}

	private static JSONObject getHttpJson(String address) {
		try {
			OkHttpClient client = new OkHttpClient();
			Request request = new Request.Builder().url(address).get().build();
			Response response = client.newCall(request).execute();
			if (response.code() != 200) {
				System.out.println("[AntiAlts3] URLGetConnected(Error): " + address);
				System.out.println("[AntiAlts3] ResponseCode: " + response.code());
				if (response.body() != null) {
					System.out.println("[AntiAlts3] Response: " + response.body().string());
				}
				return null;
			}
			JSONObject obj = new JSONObject(response.body().string());
			response.close();
			return obj;
		} catch (IOException | JSONException e) {
			e.printStackTrace();
			return null;
		}
	}

	public static JavaPlugin getJavaPlugin() {
		return JavaPlugin;
	}
}
