package com.jaoafa.AntiAlts3;

import com.jaoafa.AntiAlts3.Event.Event_AsyncPreLogin;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.hooks.AnnotatedEventManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

public class Main extends JavaPlugin {
    public static JavaPlugin JavaPlugin;
    public static MySQLDBManager MySQLDBManager = null;
    public static String sqlserver = "jaoafa.com";
    public static String sqlport = "3306";
    public static String sqluser;
    public static String sqlpassword;
    public static String proxycheck_apikey = null;
    public static FileConfiguration conf;
    private static JDA jda;

    /**
     * プラグインが起動したときに呼び出し
     *
     * @author mine_book000
     * @since 2018/02/15
     */
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new Event_AsyncPreLogin(this), this);

        Load_Config(); // Config Load
    }

    /**
     * コンフィグ読み込み
     *
     * @author mine_book000
     */
    private void Load_Config() {
        conf = getConfig();

        if (conf.contains("discordtoken")) {
			try {
				jda = JDABuilder.createDefault(conf.getString("discordtoken"))
						.setAutoReconnect(true)
						.setBulkDeleteSplittingEnabled(false)
						.setContextEnabled(false)
						.setEventManager(new AnnotatedEventManager()).build().awaitReady();
			}catch(Exception e){
				getLogger().info("Discordへの接続に失敗しました。 [Exception]");
				getLogger().info("Disable AntiAlts3...");
				getServer().getPluginManager().disablePlugin(this);
			}
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

		if (conf.contains("proxycheck_apikey")){
			proxycheck_apikey = (String) conf.get("proxycheck_apikey");
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

    private static JSONObject getHttpJson(String address) {
        try {
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder().url(address).get().build();
            Response response = client.newCall(request).execute();
            if (response.code() != 200) {
                Main.getAntiAltsLogger().info("[AntiAlts3] URLGetConnected(Error): " + address);
                Main.getAntiAltsLogger().info("[AntiAlts3] ResponseCode: " + response.code());
                if (response.body() != null) {
                    Main.getAntiAltsLogger().info("[AntiAlts3] Response: " + Objects.requireNonNull(response.body()).string());
                }
                response.close();
                return null;
            }
            JSONObject obj = new JSONObject(Objects.requireNonNull(response.body()).string());
            response.close();
            return obj;
        } catch (IOException | JSONException e) {
            e.printStackTrace();
            return null;
        }
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
        discordSend(618569153422426113L, "AntiAlts3でエラーが発生しました。" + "\n"
            + "```" + sw + "```\n"
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
			return UUID.fromString(uuid_hyphenated);
		} else {
			return null;
		}
	}

	public static UUID getUUIDByDB(String name) {
		JSONObject json = getHttpJson("https://api.jaoafa.com/users/" + name);
        if (json == null) {
            return null;
        } else if (json.has("data")) {
            String uuid_hyphenated = json.getJSONObject("data").getString("uuid");
            return UUID.fromString(uuid_hyphenated);
        } else {
            return null;
        }
    }

    public static boolean discordSend(long channel_id, String contents) {
        TextChannel channel = jda.getTextChannelById(channel_id);
        if (channel == null) {
            return false;
        }
        channel.sendMessage(contents).queue();
        return true;
	}

	public static boolean discordSend(long channel_id, MessageEmbed embed) {
		TextChannel channel = jda.getTextChannelById(channel_id);
		if(channel == null){
			return false;
		}
        channel.sendMessageEmbeds(embed).queue();
		return true;
	}

	public static JavaPlugin getJavaPlugin() {
		return JavaPlugin;
	}

    public static Logger getAntiAltsLogger() {
        return JavaPlugin.getLogger();
    }
}
