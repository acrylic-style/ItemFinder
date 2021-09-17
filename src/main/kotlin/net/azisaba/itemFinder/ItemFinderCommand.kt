package net.azisaba.itemFinder

import net.azisaba.itemFinder.listener.ScanChunkListener
import net.azisaba.itemFinder.util.Util.toHoverEvent
import net.azisaba.itemFinder.util.Util.wellRound
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

object ItemFinderCommand: TabExecutor {
    private val commands = listOf("on", "off", "add", "remove", "removeall", "clearlogs", "scanall", "scanhere", "info", "reload")
    private val scanStatus = mutableMapOf<String, Pair<Int, AtomicInteger>>()

    // 1-64, 1C(1728), 1LC(3456), 1C(1728)*1C(27), 1C(1728)*1LC(64)
    private val listOf64 = (1..64)
        .toMutableList()
        .apply { addAll(listOf(1728, 3456, 46656, 93312)) }
        .map { it.toString() }

    override fun onCommand(sender: CommandSender, command: Command, s: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("${ChatColor.RED}/itemfinder <on|off|add|remove|removeall|clearlogs|scanall|scanhere|info>")
            return true
        }
        when (args[0]) {
            "off" -> {
                ScanChunkListener.enabled = false
                sender.sendMessage("${ChatColor.GREEN}チャンクのスキャンをオフにしました。")
            }
            "on" -> {
                ScanChunkListener.enabled = true
                sender.sendMessage("${ChatColor.GREEN}チャンクのスキャンをオンにしました。")
            }
            "add" -> {
                if (sender !is Player) {
                    sender.sendMessage("${ChatColor.RED}このコマンドはコンソールからは実行できません。")
                    return true
                }
                val amount = args.getOrNull(1)?.toIntOrNull()
                if (amount == null) {
                    sender.sendMessage("${ChatColor.RED}/itemfinder add <最低アイテム数>")
                    return true
                }
                if (sender.inventory.itemInMainHand.type.isAir) {
                    sender.sendMessage("${ChatColor.RED}メインハンドにアイテムを持ってください。")
                    return true
                }
                ItemFinder.itemsToFind.removeIf { it.isSimilar(sender.inventory.itemInMainHand) }
                ItemFinder.itemsToFind.add(sender.inventory.itemInMainHand.clone().apply { this.amount = amount })
                val text = TextComponent("探す対象のアイテムを追加しました。")
                text.color = net.md_5.bungee.api.ChatColor.GREEN
                text.hoverEvent = sender.inventory.itemInMainHand.toHoverEvent()
                sender.spigot().sendMessage(text)
            }
            "remove" -> {
                if (sender !is Player) {
                    sender.sendMessage("${ChatColor.RED}このコマンドはコンソールからは実行できません。")
                    return true
                }
                if (sender.inventory.itemInMainHand.type.isAir) {
                    sender.sendMessage("${ChatColor.RED}メインハンドにアイテムを持ってください。")
                    return true
                }
                ItemFinder.itemsToFind.removeIf { it.isSimilar(sender.inventory.itemInMainHand) }
                val text = TextComponent("探す対象のアイテムを削除しました。")
                text.color = net.md_5.bungee.api.ChatColor.GREEN
                text.hoverEvent = sender.inventory.itemInMainHand.toHoverEvent()
                sender.spigot().sendMessage(text)
            }
            "removeall" -> {
                ItemFinder.itemsToFind.clear()
                sender.sendMessage("${ChatColor.GREEN}探す対象のアイテムリストをすべて削除しました。")
            }
            "clearlogs" -> {
                ItemFinder.seen.values.forEach { it.clear() }
                sender.sendMessage("${ChatColor.GREEN}スキャンされたチャンクリストを削除しました。")
            }
            "scanall" -> {
                if (sender !is Player) {
                    sender.sendMessage("${ChatColor.RED}このコマンドはコンソールからは実行できません。")
                    return true
                }
                if (scanStatus.containsKey(sender.world.name)) {
                    sender.sendMessage("${ChatColor.GREEN}このワールドはすでにスキャン中です。")
                    return true
                }
                sender.sendMessage("${ChatColor.GREEN}${sender.world.name}ワールド内の読み込まれているすべてのチャンクのデータを取得中です。")
                val snapshots = sender.world.loadedChunks.map { it.chunkSnapshot }
                val count = AtomicInteger(0)
                scanStatus[sender.world.name] = Pair(snapshots.size, count)
                Command.broadcastCommandMessage(sender, "${ChatColor.GREEN}${sender.world.name}ワールド内の読み込まれているすべてのチャンクのスキャンを開始しました。しばらく時間がかかります。", true)
                ScanChunkListener.chunkScannerExecutor.submit {
                    val futures = snapshots.map {
                        CompletableFuture.runAsync({
                            try {
                                ScanChunkListener.checkChunk(it)
                            } catch (e: Exception) {
                                ItemFinder.instance.logger.warning("Failed to check chunk ${it.x to it.z}")
                                e.printStackTrace()
                            } finally {
                                count.incrementAndGet()
                            }
                        }, ScanChunkListener.chunkScannerExecutor)
                    }
                    CompletableFuture.allOf(*futures.toTypedArray()).get()
                    scanStatus.remove(sender.world.name)
                    Command.broadcastCommandMessage(sender, "${ChatColor.GREEN}${sender.world.name}ワールド内のスキャンが完了しました。", true)
                }
            }
            "scanhere" -> {
                if (sender !is Player) {
                    sender.sendMessage("${ChatColor.RED}このコマンドはコンソールからは実行できません。")
                    return true
                }
                val c = sender.location.chunk
                ItemFinder.seen.getOrPut(sender.world.name) { mutableListOf() }.remove(c.x to c.z)
                sender.sendMessage("${ChatColor.GREEN}チャンクをスキャン中です。")
                ScanChunkListener.checkChunkAsync(c) {
                    sender.sendMessage("${ChatColor.GREEN}チャンクのスキャンが完了しました。")
                }
            }
            "info" -> {
                scanStatus.forEach { (world, pair) ->
                    val percentage = ((pair.second.get() / pair.first.toDouble()) * 100.0).wellRound()
                    sender.sendMessage("${ChatColor.GREEN}ワールド '${ChatColor.RED}${world}${ChatColor.GREEN}' のスキャン状況: ${ChatColor.RED}${pair.second.get()} ${ChatColor.GOLD}/ ${ChatColor.RED}${pair.first} ${ChatColor.GOLD}(${ChatColor.YELLOW}$percentage%${ChatColor.GOLD})")
                }
                if (sender is Player) {
                    sender.sendMessage("${ChatColor.GREEN}ワールド '${ChatColor.RED}${sender.world.name}${ChatColor.GREEN}' 内の読み込まれているチャンク数: ${ChatColor.RED}${sender.world.loadedChunks.size}")
                }
            }
            "reload" -> {
                ItemFinder.instance.config.load(File("./plugins/ItemFinder/config.yml"))
                sender.sendMessage("${ChatColor.GREEN}設定を再読み込みしました。")
            }
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, s: String, args: Array<String>): List<String> {
        if (args.isEmpty()) return emptyList()
        if (args.size == 1) return commands.filter(args[0])
        if (args.size == 2) {
            if (args[0] == "add") return listOf64.filter(args[1])
        }
        return emptyList()
    }

    private fun List<String>.filter(s: String): List<String> = distinct().filter { s1 -> s1.lowercase().startsWith(s.lowercase()) }
}