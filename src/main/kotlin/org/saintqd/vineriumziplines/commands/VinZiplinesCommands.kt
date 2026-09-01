package org.saintqd.vineriumziplines.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.saintqd.vineriumlib.VineriumLib
import org.saintqd.vineriumziplines.VineriumZiplines
import org.saintqd.vineriumziplines.managers.ZiplineManager
import java.util.UUID
import kotlin.math.hypot

class VinZiplinesCommands {

    companion object {

        fun setupCommands(plugin: VineriumZiplines) {
            val manager = plugin.lifecycleManager
            manager.registerEventHandler(LifecycleEvents.COMMANDS) {
                val commands: Commands = it.registrar()
                commands.register(
                    Commands.literal("vinzipline")
                        .executes { commandContext: CommandContext<CommandSourceStack> ->
                            commandContext.getSource().sender.sendMessage(
                                VineriumLib.inst().langManager.parseLangString(
                                    VineriumZiplines.inst(),
                                    "not_enough_arguments"
                                )
                            )
                            Command.SINGLE_SUCCESS
                        }
                        .then(
                            Commands.literal("reload")
                                .requires { predicate: CommandSourceStack ->
                                    predicate.sender.hasPermission("vineriumziplines.admin")
                                }
                                .executes { ctx: CommandContext<CommandSourceStack> ->
                                    reloadCommand(
                                        ctx.getSource().sender
                                    )
                                    Command.SINGLE_SUCCESS
                                }
                        )
                        .then(
                            Commands.literal("savedata")
                                .requires { predicate: CommandSourceStack ->
                                    predicate.sender.hasPermission("vineriumziplines.admin")
                                }
                                .executes { ctx: CommandContext<CommandSourceStack> ->
                                    saveDataCommand(
                                        ctx.getSource().sender
                                    )
                                    Command.SINGLE_SUCCESS
                                }
                        )
                        .then(
                            Commands.literal("create")
                                .requires { predicate: CommandSourceStack ->
                                    predicate.sender.hasPermission("vineriumziplines.create")
                                            && predicate.sender is Player
                                }
                                .executes { ctx: CommandContext<CommandSourceStack> ->
                                    createZiplineCommand(
                                        ctx.getSource().sender
                                    )
                                    Command.SINGLE_SUCCESS
                                }
                        )
                        .then(
                            Commands.literal("remove")
                                .requires { predicate: CommandSourceStack ->
                                    predicate.sender.hasPermission("vineriumziplines.remove")
                                }
                                .then(
                                    Commands.argument("uuid", StringArgumentType.word())
                                        .suggests { _, builder ->
                                            val partName = builder.remaining
                                            ZiplineManager.instance.ziplines.keys.forEach { uuid ->
                                                val stringUuid = uuid.toString()
                                                if (stringUuid.startsWith(partName.lowercase()))
                                                    builder.suggest(stringUuid)
                                            }
                                            return@suggests builder.buildFuture()
                                        }
                                        .executes { ctx: CommandContext<CommandSourceStack> ->
                                            removeZiplineCommand(
                                                ctx.getSource().sender,
                                                ctx.getArgument("uuid", String::class.java)
                                            )
                                            Command.SINGLE_SUCCESS
                                        }
                                )
                        )
                        .build(),
                    "Основная команда."
                )
            }
        }

        private fun reloadCommand(sender: CommandSender) {
            VineriumZiplines.inst().loadData()
            if (sender is Player) sender.sendMessage(
                VineriumLib.inst().langManager.parseLangString(VineriumZiplines.inst(), "reload_message")
            )
        }

        private fun saveDataCommand(sender: CommandSender) {
            VineriumZiplines.inst().saveData()
            if (sender is Player) sender.sendMessage(
                VineriumLib.inst().langManager.parseLangString(VineriumZiplines.inst(), "save_data_message")
            )
        }

        private fun createZiplineCommand(sender: CommandSender) {

            if (sender !is Player) {
                return
            }
            val ziplinePlayer = ZiplineManager.instance.ziplinePlayers[sender.uniqueId]
            if (ziplinePlayer == null) {
                sender.sendMessage(VineriumLib.inst().langManager.parseLangString(
                    VineriumZiplines.inst(), "command_no_zipline_player",sender.name))
            }
            else {
                if (ziplinePlayer.firstPoint == null || ziplinePlayer.secondPoint == null) {
                    sender.sendMessage(VineriumLib.inst().langManager.parseLangString(
                        VineriumZiplines.inst(), "command_create_zipline_must_set_points"))
                    return
                }
                val distance = hypot(ziplinePlayer.secondPoint!!.x - ziplinePlayer.firstPoint!!.x,
                    ziplinePlayer.secondPoint!!.y - ziplinePlayer.firstPoint!!.y)
                if (distance > VineriumZiplines.inst().config.getDouble("Ziplines.MaxLength",50.0)) {
                    sender.sendMessage(VineriumLib.inst().langManager.parseLangString(
                        VineriumZiplines.inst(), "command_create_zipline_over_distance"))
                    return
                }

                val xDif = ziplinePlayer.firstPoint!!.x - ziplinePlayer.secondPoint!!.x
                val zDif = ziplinePlayer.firstPoint!!.z - ziplinePlayer.secondPoint!!.z
                if (xDif != 0.0 && zDif != 0.0) {
                    sender.sendMessage(VineriumLib.inst().langManager.parseLangString(
                        VineriumZiplines.inst(), "command_create_zipline_wrong_axis"))
                    return
                }
                var axis = true
                if (zDif != 0.0)
                    axis = false

                if (sender.gameMode != GameMode.CREATIVE) {
                    val requiredMaterialName =
                        VineriumZiplines.inst().config.getString("Ziplines.RequiredMaterial", "IRON_CHAIN")!!
                    val material = Material.getMaterial(requiredMaterialName)
                    if (material != null) {

                        var requiredMaterialAmount = (distance * VineriumZiplines.inst().config
                            .getDouble("Ziplines.MaterialAmountPerDistance", 1.0)).toInt()
                        val originalAmount = requiredMaterialAmount

                        for (itemStack in sender.inventory) {
                            itemStack?.let {
                                if (itemStack.type == material) {
                                    requiredMaterialAmount -= it.amount
                                }
                            }
                        }
                        if (requiredMaterialAmount > 0) {
                            sender.sendMessage(
                                VineriumLib.inst().langManager.parseLangString(
                                    VineriumZiplines.inst(),
                                    "command_create_zipline_no_required_items",
                                    "<lang:${material.translationKey()}>",
                                    originalAmount.toString()
                                )
                            )
                            return
                        }

                        requiredMaterialAmount = (distance * VineriumZiplines.inst().config
                            .getDouble("Ziplines.MaterialAmountPerDistance", 1.0)).toInt()

                        for (itemStack in sender.inventory) {
                            itemStack?.let {
                                if (itemStack.type == material) {
                                    val newAmount = requiredMaterialAmount - it.amount
                                    if (newAmount > 0) {
                                        requiredMaterialAmount -= it.amount
                                        it.amount = 0
                                    } else {
                                        it.amount -= requiredMaterialAmount
                                        requiredMaterialAmount = 0
                                    }
                                    if (requiredMaterialAmount <= 0)
                                        break
                                }
                            }
                        }
                    }
                }

                val zipline = ZiplineManager.instance.createZipline(ziplinePlayer.firstPoint!!,
                    ziplinePlayer.secondPoint!!,axis)
                if (zipline != null)
                    sender.sendMessage(VineriumLib.inst().langManager.parseLangString(
                        VineriumZiplines.inst(), "command_create_zipline_success"))
                else
                    sender.sendMessage(VineriumLib.inst().langManager.parseLangString(
                        VineriumZiplines.inst(), "command_create_zipline_error"))
            }
        }

        private fun removeZiplineCommand(sender: CommandSender, uuidString : String) {

            val uuid = UUID.fromString(uuidString)
            val zipline = ZiplineManager.instance.ziplines[uuid]
            if (zipline == null) {
                sender.sendMessage(VineriumLib.inst().langManager.parseLangString(
                    VineriumZiplines.inst(), "command_remove_zipline_not_present",uuidString))
                return
            }
            ZiplineManager.instance.removeZipline(uuid)
            sender.sendMessage(VineriumLib.inst().langManager.parseLangString(
                VineriumZiplines.inst(), "command_remove_zipline_success",uuidString))
        }
    }

}