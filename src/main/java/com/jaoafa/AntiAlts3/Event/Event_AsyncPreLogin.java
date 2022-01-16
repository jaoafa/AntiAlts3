package com.jaoafa.AntiAlts3.Event;

import com.google.common.net.InternetDomainName;
import com.jaoafa.AntiAlts3.AntiAltsPlayer;
import com.jaoafa.AntiAlts3.Main;
import com.jaoafa.AntiAlts3.MySQLDBManager;
import com.jaoafa.AntiAlts3.PermissionsManager;
import net.dv8tion.jda.api.EmbedBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.JSONObject;

import java.awt.*;
import java.io.IOException;
import java.net.InetAddress;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings("UnstableApiUsage")
public class Event_AsyncPreLogin implements Listener {
    final JavaPlugin plugin;
    final long jaotanChannelId = 597423444501463040L;
    final long antialtsChannelId = 619637580987760656L;

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
            plugin.getLogger().info("BASEDOMAIN: " + BaseDomain);

        MySQLDBManager MySQLDBManager = Main.MySQLDBManager;
        if (MySQLDBManager == null) {
            plugin.getLogger().info("MySQLDBManager is null");
            return;
        }

        // 2. UUIDをMojangAPIから取得
        UUID uuid = Main.getUUIDByDB(name);
        if (uuid != null) {
            plugin.getLogger().info("The uuid was got from DB.");
        } else {
            uuid = Main.getUUID(name);
            plugin.getLogger().info("The uuid was got from Mojang API.");
        }
        if (uuid == null) {
            plugin.getLogger().warning("uuid = null.");
            Component component = Component.text().append(
                Component.text("----- ANTI ALTS -----", NamedTextColor.RED),
                Component.newline(),
                Component.text("システム不具合によりUUIDの取得に失敗しました。少し時間をおいてから再度お試しください。", NamedTextColor.WHITE)
            ).build();
            event.disallow(Result.KICK_BANNED, component);
            return;
        }
        plugin.getLogger().info("UUID: " + uuid);

        // 3. UUIDが合致するデータ(プレイヤーデータ)がAntiAltsのデータベーステーブルにあるかどうか調べる。あればAntiAltsUserID取得
        int AntiAltsUserID = getAntiAltsUserID(uuid);
        if (AntiAltsUserID != -1) {
            // 4. UUIDが同じでMinecraftIDがデータベースのデータと違ったらデータベース更新。Discord通知
            String oldName = changePlayerID(uuid, name);
            if (oldName != null) {
                plugin.getLogger().info("The player ID has been changed. (" + oldName + " -> " + name + ")");

                for (Player p : Bukkit.getServer().getOnlinePlayers()) {
                    if (!isAMR(p)) continue;
                    p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + "|-- " + name + " : - : プレイヤー名変更情報 --|");
                    p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + "このプレイヤーは、前回ログインからプレイヤー名を変更しています。(旧名: " + oldName + ")");
                }
                EmbedBuilder builder = new EmbedBuilder()
                    .setTitle("AntiAlts3 プレイヤー名変更情報",
                        String.format("https://users.jaoafa.com/%s", uuid))
                    .setDescription("このプレイヤーは、前回ログインからプレイヤー名を変更しています。")
                    .setColor(Color.YELLOW)
                    .addField("旧名", oldName, false)
                    .addField("NameMC", String.format("https://ja.namemc.com/profile/%s", uuid), false)
                    .setAuthor(name,
                        String.format("https://users.jaoafa.com/%s", uuid),
                        String.format("https://crafatar.com/renders/head/%s", uuid))
                    .setTimestamp(Instant.now());
                Main.discordSend(jaotanChannelId, builder.build());

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
            MainAltID = MainAccount.name();
            MainAltUUID = MainAccount.uuid();
        }
        plugin.getLogger().info("MainAltID: " + MainAltID);

        if (!uuid.equals(MainAltUUID)) {
            // メインアカウントではない
            plugin.getLogger().info("This account is not MainAccount. (MainAccount: " + MainAltID + ")");

            Component component = Component.text().append(
                Component.text("----- ANTI ALTS -----", NamedTextColor.RED),
                Component.newline(),
                Component.text("あなたは以下のアカウントで既にログインをされたことがあるようです。(1)", NamedTextColor.WHITE),
                Component.newline(),
                Component.text(MainAltID + " (" + MainAltUUID.toString() + ")", NamedTextColor.AQUA),
                Component.newline(),
                Component.text("もしこの判定が誤判定と思われる場合は、公式Discord#supportでお問い合わせをお願い致します。", NamedTextColor.WHITE)
            ).build();
            event.disallow(Result.KICK_BANNED, component);
            for (Player p : Bukkit.getServer().getOnlinePlayers()) {
                if (isAM(p)) continue;
                p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + name + ": サブアカウントログイン規制(1 - メイン: "
                    + MainAltID + ")");
            }

            EmbedBuilder builder = new EmbedBuilder()
                .setTitle("AntiAlts3 サブアカウントログイン規制",
                    String.format("https://users.jaoafa.com/%s", uuid))
                .setDescription("このプレイヤーからサブアカウントが検出されました。")
                .setColor(Color.RED)
                .addField("メインアカウント", MainAltID, false)
                .addField("メインアカウントユーザーページ",
                    String.format("https://users.jaoafa.com/%s", MainAltUUID), false)
                .addField("検出種別", "AntiAltsUserID同一・UUID差異 (1)", false)
                .setAuthor(name,
                    String.format("https://users.jaoafa.com/%s", uuid),
                    String.format("https://crafatar.com/renders/head/%s", uuid))
                .setTimestamp(Instant.now());
            Main.discordSend(jaotanChannelId, builder.build());
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
            if (i != -1) {
                AntiAltsUserID = i;
                plugin.getLogger().info("change AntiAltsUserID: " + AntiAltsUserID);
            }

            AntiAltsPlayer IdenticalIPMainAccount = getMainUUID(AntiAltsUserID);
            String IdenticalIPMainAltID = name;
            UUID IdenticalIPMainAltUUID = uuid;
            if (IdenticalIPMainAccount != null) {
                IdenticalIPMainAltID = IdenticalIPMainAccount.name();
                IdenticalIPMainAltUUID = IdenticalIPMainAccount.uuid();
            }
            plugin.getLogger().info("IdenticalIPMainAltID: " + IdenticalIPMainAltID);
            if (!uuid.equals(IdenticalIPMainAltUUID)) {
                plugin.getLogger().info(
                    "This account is not MainAccount. (MainAccount: " + IdenticalIPMainAltID + " | IdenticalIP)");

                Component component = Component.text().append(
                    Component.text("----- ANTI ALTS -----", NamedTextColor.RED),
                    Component.newline(),
                    Component.text("あなたは以下のアカウントで既にログインをされたことがあるようです。(2)", NamedTextColor.WHITE),
                    Component.newline(),
                    Component.text(IdenticalIPMainAltID + " (" + IdenticalIPMainAltUUID.toString() + ")", NamedTextColor.AQUA),
                    Component.newline(),
                    Component.text("もしこの判定が誤判定と思われる場合は、公式Discord#supportでお問い合わせをお願い致します。", NamedTextColor.WHITE)
                ).build();
                event.disallow(Result.KICK_BANNED, component);
                for (Player p : Bukkit.getServer().getOnlinePlayers()) {
                    if (!isAM(p)) continue;
                    p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + name + ": サブアカウントログイン規制(2 - メイン: "
                        + IdenticalIPMainAltID + ")");
                }

                EmbedBuilder builder = new EmbedBuilder()
                    .setTitle("AntiAlts3 サブアカウントログイン規制",
                        String.format("https://users.jaoafa.com/%s", uuid))
                    .setDescription("このプレイヤーからサブアカウントが検出されました。")
                    .setColor(Color.RED)
                    .addField("メインアカウント", IdenticalIPMainAltID, false)
                    .addField("メインアカウントユーザーページ",
                        String.format("https://users.jaoafa.com/%s", IdenticalIPMainAltUUID), false)
                    .addField("検出種別", "IP同一・UUID差異 (2)", false)
                    .setAuthor(name,
                        String.format("https://users.jaoafa.com/%s", uuid),
                        String.format("https://crafatar.com/renders/head/%s", uuid))
                    .setTimestamp(Instant.now());
                Main.discordSend(jaotanChannelId, builder.build());
            }
        }

        if (isNeedINSERT(uuid, address)) {
            try {
                Connection conn = MySQLDBManager.getConnection();
                PreparedStatement statement_insert = conn.prepareStatement(
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
            } catch (SQLException e) {
                Main.report(e);
            }
        }

        int finalAntiAltsUserID = AntiAltsUserID;
        InternetDomainName finalBaseDomain = BaseDomain;
        UUID finalUUID = uuid;

        new BukkitRunnable() {
            public void run() {
                setIPLastLogin(address);
                setLastLogin(finalUUID);
                setFirstLogin(finalUUID);

                // プロキシからのログインかどうかを判定し、そうであれば管理部・モデレーター・常連に表示(Discordにも。)
                checkProxy(name, finalUUID, ip);
            }
        }.runTaskAsynchronously(Main.getJavaPlugin());

        // 9. ログイン許可？
        if (!loginOK) {
            return;
        }

        new BukkitRunnable() {
            public void run() {
                // 10. 同一AntiAltsUserIDのプレイヤーリストを管理部・モデレーター・常連に表示(Discordにも。)
                Set<AntiAltsPlayer> IdenticalUserIDPlayers = getUsers(finalAntiAltsUserID, finalUUID);
                if (!IdenticalUserIDPlayers.isEmpty()) {
                    List<String> names = IdenticalUserIDPlayers.stream().map(AntiAltsPlayer::name)
                        .collect(Collectors.toList());
                    for (Player p : Bukkit.getServer().getOnlinePlayers()) {
                        if (!isAMR(p)) continue;
                        p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + "|-- " + name + " : - : サブアカウント情報 --|");
                        p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + String.format("このプレイヤーには、%d個のサブアカウントが検出されています。", IdenticalUserIDPlayers.size()));
                        p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + String.join(", ", names));
                    }

                    EmbedBuilder builder = new EmbedBuilder()
                        .setTitle("AntiAlts3 サブアカウント情報",
                            String.format("https://users.jaoafa.com/%s", finalUUID))
                        .setDescription(String.format("このプレイヤーには、%d個のサブアカウントが検出されています。", IdenticalUserIDPlayers.size()))
                        .setColor(Color.YELLOW)
                        .addField("サブアカウント", String.join(", ", names), false)
                        .setAuthor(name,
                            String.format("https://users.jaoafa.com/%s", finalUUID),
                            String.format("https://crafatar.com/renders/head/%s", finalUUID))
                        .setTimestamp(Instant.now());
                    Main.discordSend(antialtsChannelId, builder.build());
                }

                // 11. 同一ドメインの非同一UUIDで、48h以内にラストログインしたプレイヤーをリスト化。管理部・モデレーターに出力
                Set<AntiAltsPlayer> IdenticalBaseDomainPlayers = getUsers(finalBaseDomain, finalUUID);
                if (!IdenticalBaseDomainPlayers.isEmpty()) {
                    List<String> names = IdenticalBaseDomainPlayers.stream().map(AntiAltsPlayer::name)
                        .collect(Collectors.toList());
                    for (Player p : Bukkit.getServer().getOnlinePlayers()) {
                        if (!isAM(p)) continue;
                        p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + "|-- " + name + " : - : 同一ベースドメイン情報 --|");
                        p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + "このプレイヤードメインと同一のプレイヤーが"
                            + IdenticalBaseDomainPlayers.size()
                            + "個見つかっています。");
                        p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + String.join(", ", names));
                    }

                    EmbedBuilder builder = new EmbedBuilder()
                        .setTitle("AntiAlts3 同一ベースドメイン情報",
                            String.format("https://users.jaoafa.com/%s", finalUUID))
                        .setDescription(String.format("このプレイヤーのドメイン(%s)と同一のプレイヤーが%d個検出されています。",
                            finalBaseDomain != null ? finalBaseDomain.toString() : "null",
                            IdenticalBaseDomainPlayers.size()))
                        .setColor(Color.YELLOW)
                        .addField("サブアカウント", String.join(", ", names), false)
                        .setAuthor(name,
                            String.format("https://users.jaoafa.com/%s", finalUUID),
                            String.format("https://crafatar.com/renders/head/%s", finalUUID))
                        .setTimestamp(Instant.now());
                    Main.discordSend(antialtsChannelId, builder.build());
                }
            }
        }.runTaskAsynchronously(Main.getJavaPlugin());
    }

    /**
     * UUIDからAntiAltsUserIDを取得します。
     *
     * @param uuid 取得する元のUUID
     * @return AntiAltsUserID　取得したAntiAltsUserID。取得できなければ-1
     */
    int getAntiAltsUserID(UUID uuid) {
        try {
            MySQLDBManager MySQLDBManager = Main.MySQLDBManager;
            if (MySQLDBManager == null) {
                return -1;
            }
            Connection conn = MySQLDBManager.getConnection();
            PreparedStatement statement = conn.prepareStatement("SELECT * FROM antialts_new WHERE uuid = ?");
            statement.setString(1, uuid.toString());
            ResultSet res = statement.executeQuery();
            if (res.next()) {
                int userid = res.getInt("userid");
                res.close();
                statement.close();
                return userid;
            } else {
                res.close();
                statement.close();
                return -1;
            }
        } catch (SQLException e) {
            Main.report(e);
            return -1;
        }
    }

    /**
     * データベースにある同一UUIDデータのプレイヤー名が違う項目があったときに一括で変更します。
     *
     * @param uuid        対象のプレイヤーのUUID
     * @param newPlayerID 新しいPlayerID
     * @return 古いPlayerID。変更されていなければnull
     */
    String changePlayerID(UUID uuid, String newPlayerID) {
        try {
            MySQLDBManager MySQLDBManager = Main.MySQLDBManager;
            if (MySQLDBManager == null) {
                return null;
            }
            Connection conn = MySQLDBManager.getConnection();
            PreparedStatement statement = conn
                .prepareStatement("SELECT * FROM antialts_new WHERE uuid = ? AND player != ?");
            statement.setString(1, uuid.toString());
            statement.setString(2, newPlayerID);
            ResultSet res = statement.executeQuery();
            if (res.next()) {
                PreparedStatement statement_update = conn
                    .prepareStatement("UPDATE antialts_new SET player = ? WHERE uuid = ?");
                statement_update.setString(1, newPlayerID);
                statement_update.setString(2, uuid.toString());
                statement_update.executeUpdate();
                String player = res.getString("player");
                res.close();
                statement.close();
                return player;
            }
            res.close();
            statement.close();
            return null;
        } catch (SQLException e) {
            Main.report(e);
            return null;
        }
    }

    /**
     * 対象プレイヤーのLastLoginを変更します。
     *
     * @param uuid 対象のプレイヤーのUUID
     */
    void changeLastLogin(UUID uuid) {
        try {
            MySQLDBManager MySQLDBManager = Main.MySQLDBManager;
            if (MySQLDBManager == null) {
                return;
            }
            Connection conn = MySQLDBManager.getConnection();
            PreparedStatement statement = conn
                .prepareStatement("UPDATE antialts_new SET lastlogin = CURRENT_TIMESTAMP WHERE uuid = ?");
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
            statement.close();
        } catch (SQLException e) {
            Main.report(e);
        }
    }

    /**
     * AntiAltsの判定対象で<b>ないか</b>どうかを返す。
     *
     * @param uuid 対象のプレイヤーのUUID
     * @return 対象で<b>なければ</b>true
     */
    boolean isIgnoreUser(UUID uuid) {
        try {
            MySQLDBManager MySQLDBManager = Main.MySQLDBManager;
            if (MySQLDBManager == null) {
                return false;
            }
            Connection conn = MySQLDBManager.getConnection();
            PreparedStatement statement = conn.prepareStatement("SELECT * FROM antialts_ignore WHERE uuid = ?");
            statement.setString(1, uuid.toString());
            ResultSet res = statement.executeQuery();
            boolean bool = res.next();
            res.close();
            statement.close();
            return bool;
        } catch (SQLException e) {
            Main.report(e);
            return false;
        }
    }

    /**
     * メインアカウントを取得します。
     *
     * @param AntiAltsUserID 対象のAntiAltsUserID
     * @return メインアカウント
     */
    AntiAltsPlayer getMainUUID(int AntiAltsUserID) {
        try {
            // antialts_mainに登録されている場合、同一AntiAltsUserIDは全てantialts_mainに登録されているアカウントをメインとする。
            MySQLDBManager MySQLDBManager = Main.MySQLDBManager;
            if (MySQLDBManager == null) {
                return null;
            }
            Connection conn = MySQLDBManager.getConnection();
            PreparedStatement statement = conn.prepareStatement("SELECT * FROM antialts_main WHERE userid = ?");
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
            PreparedStatement statement_useridlist = conn
                .prepareStatement("SELECT * FROM antialts_new WHERE userid = ? ORDER BY id");
            statement_useridlist.setInt(1, AntiAltsUserID);
            ResultSet useridlist_res = statement_useridlist.executeQuery();
            if (useridlist_res.next()) {
                String name = useridlist_res.getString("player");
                UUID uuid = UUID.fromString(useridlist_res.getString("uuid"));
                useridlist_res.close();
                statement_useridlist.close();
                return new AntiAltsPlayer(name, uuid);
                //return Bukkit.getOfflinePlayer(UUID.fromString(useridlist_res.getString("uuid")));
            }
            useridlist_res.close();
            statement_useridlist.close();
            return null;
        } catch (SQLException e) {
            Main.report(e);
            return null;
        }
    }

    /**
     * 指定したInetAddressのIPアドレスと同一なプレイヤー数を返します。exceptUUIDに指定したUUIDのプレイヤーを除外します。
     *
     * @param address    対象のInetAddress
     * @param exceptUUID 除外するプレイヤーのUUID
     * @return 指定したInetAddressのIPアドレスと同一なプレイヤー数
     */
    int getIdenticalIPUsersCount(InetAddress address, UUID exceptUUID) {
        try {
            MySQLDBManager MySQLDBManager = Main.MySQLDBManager;
            if (MySQLDBManager == null) {
                return 0;
            }
            Connection conn = MySQLDBManager.getConnection();
            PreparedStatement statement = conn
                .prepareStatement("SELECT COUNT(*) FROM antialts_new WHERE ip = ? AND uuid != ?");
            statement.setString(1, address.getHostAddress());
            statement.setString(2, exceptUUID.toString());
            ResultSet res = statement.executeQuery();
            int i = 0;
            if (res.next()) i = res.getInt(1);
            res.close();
            statement.close();
            return i;
        } catch (SQLException e) {
            Main.report(e);
            return 0;
        }
    }

    /**
     * 指定したInetAddressのIPアドレスと同一なデータで最も小さいIDを返します。さらに対象のすべてのデータに最も小さいIDを設定します。exceptUUIDに指定したUUIDのプレイヤーを除外します。
     *
     * @param address    対象のInetAddress
     * @param exceptUUID 除外するプレイヤーのUUID
     * @return 指定したInetAddressのIPアドレスと同一なデータで最も小さいID
     */
    int getIdenticalIPSmallestID_And_SetID(InetAddress address, UUID exceptUUID) {
        try {
            MySQLDBManager MySQLDBManager = Main.MySQLDBManager;
            if (MySQLDBManager == null) {
                return -1;
            }
            Connection conn = MySQLDBManager.getConnection();
            int AntiAltsUserID = Integer.MAX_VALUE;
            PreparedStatement statement_sameIP = conn
                .prepareStatement("SELECT * FROM antialts_new WHERE ip = ? AND uuid != ?",
                    ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_UPDATABLE);
            statement_sameIP.setString(1, address.getHostAddress());
            statement_sameIP.setString(2, exceptUUID.toString());
            ResultSet res_sameIP = statement_sameIP.executeQuery();
            while (res_sameIP.next() && AntiAltsUserID > res_sameIP.getInt("userid")) {
                AntiAltsUserID = res_sameIP.getInt("userid");
            }

            // 対象のすべての行に小さいAntiAltsUserIDを設定 -> 同一
            if (AntiAltsUserID == Integer.MAX_VALUE) {
                res_sameIP.close();
                statement_sameIP.close();
                return -1;
            }
            res_sameIP.first();
            while (res_sameIP.next()) {
                res_sameIP.updateInt("userid", AntiAltsUserID);
                res_sameIP.updateRow();
            }

            res_sameIP.close();
            statement_sameIP.close();

            // 更に小さいAntiAltsUserIDを探す
            Set<Integer> replaceIds = new HashSet<>();
            PreparedStatement statement_sameIPorUUID = conn
                .prepareStatement("SELECT * FROM antialts_new WHERE ip = ? OR uuid = ?");
            statement_sameIPorUUID.setString(1, address.getHostAddress());
            statement_sameIPorUUID.setString(2, exceptUUID.toString());
            ResultSet res_sameIPorUUID = statement_sameIPorUUID.executeQuery();
            while (res_sameIPorUUID.next()) {
                if (AntiAltsUserID > res_sameIPorUUID.getInt("userid")) {
                    AntiAltsUserID = res_sameIPorUUID.getInt("userid");
                }
                replaceIds.add(res_sameIPorUUID.getInt("userid"));
            }
            res_sameIPorUUID.close();
            statement_sameIPorUUID.close();
            replaceIds.remove(AntiAltsUserID);
            for (int replaceId : replaceIds) {
                System.out.printf("[getIdenticalIPSmallestID_And_SetID] %d -> %d%n", replaceId, AntiAltsUserID);
                PreparedStatement replaceStatement = conn.prepareStatement("UPDATE antialts_new SET userid = ? WHERE userid = ?");
                replaceStatement.setInt(1, AntiAltsUserID);
                replaceStatement.setInt(2, replaceId);
                replaceStatement.executeUpdate();
                replaceStatement.close();
            }

            return AntiAltsUserID;
        } catch (SQLException e) {
            Main.report(e);
            return -1;
        }
    }

    /**
     * 指定したUUIDに合致するすべてのデータに対して一番小さいFirstLoginを設定します。
     *
     * @param uuid 対象のUUID
     */
    void setFirstLogin(UUID uuid) {
        try {
            MySQLDBManager MySQLDBManager = Main.MySQLDBManager;
            if (MySQLDBManager == null) {
                return;
            }
            Connection conn = MySQLDBManager.getConnection();
            Timestamp firstlogin = new Timestamp(System.currentTimeMillis());
            PreparedStatement statement = conn.prepareStatement("SELECT * FROM antialts_new WHERE uuid = ?", ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
            statement.setString(1, uuid.toString());
            ResultSet res = statement.executeQuery();
            while (res.next()) {
                if (res.getTimestamp("firstlogin").before(firstlogin)) {
                    firstlogin = res.getTimestamp("firstlogin");
                }
            }

            res.first();
            while (res.next()) {
                PreparedStatement statement_updatefirstlogin = conn
                    .prepareStatement("UPDATE antialts_new SET firstlogin = ? WHERE id = ?");
                statement_updatefirstlogin.setTimestamp(1, firstlogin);
                statement_updatefirstlogin.setInt(2, res.getInt("id"));
                statement_updatefirstlogin.executeUpdate();
                statement_updatefirstlogin.close();
            }
            res.close();
            statement.close();
        } catch (SQLException e) {
            Main.report(e);
        }
    }

    /**
     * 指定したUUIDに合致するすべてのデータに対して現在の時刻を設定します。
     *
     * @param uuid 対象のUUID
     */
    void setLastLogin(UUID uuid) {
        try {
            MySQLDBManager MySQLDBManager = Main.MySQLDBManager;
            if (MySQLDBManager == null) {
                return;
            }
            Connection conn = MySQLDBManager.getConnection();
            PreparedStatement statement = conn
                .prepareStatement("UPDATE antialts_new SET lastlogin = CURRENT_TIMESTAMP WHERE uuid = ?");
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
            statement.close();
        } catch (SQLException e) {
            Main.report(e);
        }
    }

    /**
     * 指定したIPに合致するすべてのデータに対して現在の時刻を設定します。
     *
     * @param address 対象のIP
     */
    void setIPLastLogin(InetAddress address) {
        try {
            MySQLDBManager MySQLDBManager = Main.MySQLDBManager;
            if (MySQLDBManager == null) {
                return;
            }
            Connection conn = MySQLDBManager.getConnection();
            PreparedStatement statement = conn
                .prepareStatement("UPDATE antialts_new SET iplastlogin = CURRENT_TIMESTAMP WHERE ip = ?");
            statement.setString(1, address.getHostAddress());
            statement.executeUpdate();
            statement.close();
        } catch (SQLException e) {
            Main.report(e);
        }
    }

    /**
     * 登録すべきかどうかを判定します。
     *
     * @param uuid    対象のUUID
     * @param address 対象のInetAddress
     * @return 登録すべきか
     */
    boolean isNeedINSERT(UUID uuid, InetAddress address) {
        try {
            MySQLDBManager MySQLDBManager = Main.MySQLDBManager;
            if (MySQLDBManager == null) {
                return true;
            }
            Connection conn = MySQLDBManager.getConnection();
            PreparedStatement statement_selectAlready = conn
                .prepareStatement("SELECT id FROM antialts_new WHERE uuid = ? AND ip = ? LIMIT 1");
            statement_selectAlready.setString(1, uuid.toString());
            statement_selectAlready.setString(2, address.getHostAddress());
            ResultSet res_selectAlready = statement_selectAlready.executeQuery();
            boolean bool = !res_selectAlready.next();
            res_selectAlready.close();
            statement_selectAlready.close();
            return bool;
        } catch (SQLException e) {
            Main.report(e);
            return true;
        }
    }

    /**
     * 最終行のIDを返却します。
     *
     * @return 最終行のID。取得できない場合は-1
     */
    int getLastID() {
        try {
            MySQLDBManager MySQLDBManager = Main.MySQLDBManager;
            if (MySQLDBManager == null) {
                return -1;
            }
            Connection conn = MySQLDBManager.getConnection();
            PreparedStatement statement = conn.prepareStatement("SELECT * FROM antialts_new ORDER BY id DESC LIMIT 1");
            ResultSet res = statement.executeQuery();
            int i = -1;
            if (res.next()) i = res.getInt(1);
            res.close();
            statement.close();
            return i;
        } catch (SQLException e) {
            Main.report(e);
            return -1;
        }
    }

    Set<AntiAltsPlayer> getUsers(int AntiAltsUserID, UUID exceptUUID) {
        try {
            MySQLDBManager MySQLDBManager = Main.MySQLDBManager;
            if (MySQLDBManager == null) {
                return new HashSet<>();
            }
            Connection conn = MySQLDBManager.getConnection();
            PreparedStatement statement = conn.prepareStatement("SELECT * FROM antialts_new WHERE userid = ?");
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
                    _player -> _player != null && _player.uuid().equals(player.uuid())).toList();
                if (!filtered.isEmpty()) {
                    continue;
                }
                players.add(player);
            }
            res.close();
            statement.close();
            return players;
        } catch (SQLException e) {
            Main.report(e);
            return new HashSet<>();
        }
    }

    Set<AntiAltsPlayer> getUsers(InternetDomainName BaseDomain, UUID exceptUUID) {
        try {
            if (BaseDomain == null) {
                return new HashSet<>();
            }
            MySQLDBManager MySQLDBManager = Main.MySQLDBManager;
            if (MySQLDBManager == null) {
                return new HashSet<>();
            }
            Connection conn = MySQLDBManager.getConnection();
            PreparedStatement statement = conn.prepareStatement(
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
                    _player -> _player != null && _player.uuid().equals(player.uuid())).toList();
                if (!filtered.isEmpty()) {
                    continue;
                }
                players.add(player);
            }
            res.close();
            statement.close();
            return players;
        } catch (SQLException e) {
            Main.report(e);
            return new HashSet<>();
        }
    }

    void checkProxy(String name, UUID uuid, String ip) {
        try {
            MySQLDBManager MySQLDBManager = Main.MySQLDBManager;
            if (MySQLDBManager == null) {
                return;
            }
            Connection conn = MySQLDBManager.getConnection();
            PreparedStatement statement = conn.prepareStatement("SELECT * FROM antialts_new WHERE uuid = ? AND ip = ? AND is_proxy is NOT NULL");
            statement.setString(1, uuid.toString());
            statement.setString(2, ip);
            ResultSet res = statement.executeQuery();
            Boolean is_proxy = null;
            String proxy_type = null;
            Integer proxy_risk = null;
            if (res.next()) {
                is_proxy = res.getBoolean("is_proxy");
                proxy_type = res.getString("proxy_type");
                proxy_risk = res.getInt("proxy_risk");
            }
            res.close();
            statement.close();

            if (is_proxy == null) {
                // APIからプロキシかどうかを取得
                String api_url = String.format("https://proxycheck.io/v2/%s?key=%s&vpn=1&asn=1&risk=1", ip, Main.proxycheck_apikey);
                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder().url(api_url).get().build();
                Response response = client.newCall(request).execute();
                if (response.code() != 200) {
                    System.out.println("[AntiAlts3] URLGetConnected(Error): " + api_url);
                    System.out.println("[AntiAlts3] ResponseCode: " + response.code());
                    if (response.body() == null) {
                        return;
                    }
                    response.close();
                    return;
                }

                String response_text = Objects.requireNonNull(response.body()).string();
                System.out.println("[AntiAlts3] Response: " + response_text);
                JSONObject result = new JSONObject(response_text);
                if (!result.getString("status").equals("ok")) {
                    System.out.println("[AntiAlts3] ProxyCheck: " + result.getString("status") + " | " + result.getString("status"));
                }
                if (result.getString("status").equals("denied") || result.getString("status").equals("error")) {
                    return;
                }
                JSONObject ip_data = result.getJSONObject(ip);
                is_proxy = ip_data.getString("proxy").equals("yes");
                proxy_type = ip_data.getString("type");
                proxy_risk = ip_data.getInt("risk");
                System.out.printf("[AntiAlts3] ProxyCheck: proxy:%s | type:%s | risk:%s%n", ip_data.getString("proxy"), proxy_type, proxy_risk);

                PreparedStatement stmt_update = conn.prepareStatement("UPDATE antialts_new SET is_proxy = ?, proxy_type = ?, proxy_risk = ? WHERE uuid = ?");
                stmt_update.setBoolean(1, is_proxy);
                stmt_update.setString(2, proxy_type);
                stmt_update.setInt(3, proxy_risk);
                stmt_update.setString(4, uuid.toString());
                stmt_update.executeUpdate();
                stmt_update.close();
            }

            if (is_proxy) {
                for (Player p : Bukkit.getServer().getOnlinePlayers()) {
                    if (!isAMR(p)) continue;
                    p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + "|-- " + name + " : - : プロキシ情報 --|");
                    p.sendMessage("[AntiAlts3] " + ChatColor.GREEN + "このプレイヤーはプロキシ(" + proxy_type + ")を使用している可能性があります。");
                }

                EmbedBuilder builder = new EmbedBuilder()
                    .setTitle("AntiAlts3 プロキシ情報",
                        String.format("https://users.jaoafa.com/%s", uuid))
                    .setDescription("このプレイヤーはプロキシを使用している可能性があります。")
                    .setColor(Color.YELLOW)
                    .addField("プロキシ種別", proxy_type, false)
                    .addField("プロキシリスク", String.format("%d / 100", proxy_risk), false)
                    .setAuthor(name,
                        String.format("https://users.jaoafa.com/%s", uuid),
                        String.format("https://crafatar.com/renders/head/%s", uuid))
                    .setTimestamp(Instant.now());
                Main.discordSend(jaotanChannelId, builder.build());
            }
        } catch (SQLException | IOException e) {
            Main.report(e);
        }
    }

    boolean isAM(Player player) {
        String group = PermissionsManager.getPermissionMainGroup(player);
        return group.equalsIgnoreCase("Admin") || group.equalsIgnoreCase("Moderator");
    }

    boolean isAMR(Player player) {
        String group = PermissionsManager.getPermissionMainGroup(player);
        return group.equalsIgnoreCase("Admin") || group.equalsIgnoreCase("Moderator") || group.equalsIgnoreCase("Regular");
    }
}