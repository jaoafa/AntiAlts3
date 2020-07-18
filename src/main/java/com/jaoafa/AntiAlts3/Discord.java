package com.jaoafa.AntiAlts3;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.JSONObject;

public class Discord {
	public static void start(JavaPlugin plugin, String token) {
		Discord.plugin = plugin;
		Discord.token = token;

		Bukkit.getLogger().info("Discord Connected!");
	}

	static JavaPlugin plugin;
	static String token;

	public static boolean send(String message) {
		Map<String, String> headers = new HashMap<String, String>();
		headers.put("Content-Type", "application/json");
		headers.put("Authorization", "Bot " + token);
		headers.put("User-Agent", "DiscordBot (https://jaoafa.com, v0.0.1)");

		Map<String, String> contents = new HashMap<String, String>();
		contents.put("content", message);
		return postHttpJsonByJson("https://discord.com/api/channels/250613942106193921/messages", headers, contents);
	}

	@SuppressWarnings("unchecked")
	private static boolean postHttpJsonByJson(String address, Map<String, String> headers,
			Map<String, String> contents) {
		StringBuilder builder = new StringBuilder();
		try {
			URL url = new URL(address);

			/*TrustManager[] tm = { new X509TrustManager() {
				@Override
				public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType)
						throws CertificateException {
					// TODO 自動生成されたメソッド・スタブ
			
				}
				@Override
				public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
						throws CertificateException {
					// TODO 自動生成されたメソッド・スタブ
			
				}
				@Override
				public java.security.cert.X509Certificate[] getAcceptedIssuers() {
					// TODO 自動生成されたメソッド・スタブ
					return null;
				}
			}};
			SSLContext sslcontext = SSLContext.getInstance("SSL");
			sslcontext.init(null, tm, null);
			HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
				@Override
				public boolean verify(String hostname, SSLSession session) {
					return true;
				}
			});
			 */
			HttpsURLConnection connect = (HttpsURLConnection) url.openConnection();
			connect.setRequestMethod("POST");
			// connect.setSSLSocketFactory(sslcontext.getSocketFactory());
			if (headers != null) {
				for (Map.Entry<String, String> header : headers.entrySet()) {
					connect.setRequestProperty(header.getKey(), header.getValue());
				}
			}

			connect.setDoOutput(true);
			OutputStreamWriter out = new OutputStreamWriter(connect.getOutputStream());
			//List<String> list = new ArrayList<>();
			JSONObject paramobj = new JSONObject();
			for (Map.Entry<String, String> content : contents.entrySet()) {
				//list.add(content.getKey() + "=" + content.getValue());
				paramobj.put(content.getKey(), content.getValue());
			}
			//String param = implode(list, "&");
			out.write(paramobj.toJSONString());
			//Bukkit.getLogger().info(paramobj.toJSONString());
			out.close();

			connect.connect();

			if (connect.getResponseCode() != HttpURLConnection.HTTP_OK) {
				InputStream in = connect.getErrorStream();

				BufferedReader reader = new BufferedReader(new InputStreamReader(in));
				String line;
				while ((line = reader.readLine()) != null) {
					builder.append(line);
				}
				in.close();
				connect.disconnect();

				Bukkit.getLogger().warning("DiscordWARN: " + builder.toString());
				return false;
			}

			InputStream in = connect.getInputStream();

			BufferedReader reader = new BufferedReader(new InputStreamReader(in));
			String line;
			while ((line = reader.readLine()) != null) {
				builder.append(line);
			}
			in.close();
			connect.disconnect();
			//JSONParser parser = new JSONParser();
			//Object obj = parser.parse(builder.toString());
			//JSONObject json = (JSONObject) obj;
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			//BugReport.report(e);
			return false;
		}
	}

	public static boolean send(String channelid, String message) {
		Map<String, String> headers = new HashMap<String, String>();
		headers.put("Content-Type", "application/json");
		headers.put("Authorization", "Bot " + token);
		headers.put("User-Agent", "DiscordBot (https://jaoafa.com, v0.0.1)");

		Map<String, String> contents = new HashMap<String, String>();
		contents.put("content", message);
		return postHttpJsonByJson("https://discord.com/api/channels/" + channelid + "/messages", headers, contents);
	}
}
