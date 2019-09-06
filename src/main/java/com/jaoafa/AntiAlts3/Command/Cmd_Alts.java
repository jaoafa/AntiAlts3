package com.jaoafa.AntiAlts3.Command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public class Cmd_Alts implements CommandExecutor {
	JavaPlugin plugin;
	public Cmd_Alts(JavaPlugin plugin) {
		this.plugin = plugin;
	}
	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

		return true;
	}

}
