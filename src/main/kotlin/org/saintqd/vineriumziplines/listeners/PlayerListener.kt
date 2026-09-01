package org.saintqd.vineriumziplines.listeners

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.saintqd.vineriumlib.managers.LangManager
import org.saintqd.vineriumziplines.VineriumZiplines
import org.saintqd.vineriumziplines.managers.ZiplineManager

class PlayerListener : Listener {

    private val CREATE_ZIPLINE_KEY = NamespacedKey(VineriumZiplines.inst(),"create_zipline_item")
    private val REMOVE_ZIPLINE_KEY = NamespacedKey(VineriumZiplines.inst(),"remove_zipline_item")

    @EventHandler(priority = EventPriority.LOW)
    fun onPlayerJoin(event : PlayerJoinEvent) {
        ZiplineManager.instance.ziplinePlayers[event.player.uniqueId] = ZiplineManager.ZiplinePlayer()
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerQuit(event : PlayerQuitEvent) {
        ZiplineManager.instance.ziplinePlayers.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onBlockInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND)
            return
        if (event.player.uniqueId !in ZiplineManager.instance.ziplinePlayers.keys)
            return
        val ziplinePlayer = ZiplineManager.instance.ziplinePlayers[event.player.uniqueId]!!
        event.player.inventory.itemInMainHand.let { item ->
            event.clickedBlock?.let { block ->
                val loc = block.location
                if (item.persistentDataContainer.has(CREATE_ZIPLINE_KEY)) {
                    if (ZiplineManager.instance.ziplineLocations.contains(loc)) {
                        event.player.sendMessage(
                            LangManager.INSTANCE.parseLangString(
                                VineriumZiplines.inst(), "create_zipline_point_already_present"
                            )
                        )
                        event.isCancelled = true
                        return
                    }
                    if (event.action == Action.LEFT_CLICK_BLOCK) {
                        ziplinePlayer.firstPoint = loc
                        event.player.sendMessage(
                            LangManager.INSTANCE.parseLangString(
                                VineriumZiplines.inst(), "create_zipline_first_point_set",
                                loc.x.toString(), loc.y.toString(), loc.z.toString()
                            )
                        )
                        event.isCancelled = true
                        return
                    } else if (event.action == Action.RIGHT_CLICK_BLOCK) {
                        ziplinePlayer.secondPoint = loc
                        event.player.sendMessage(
                            LangManager.INSTANCE.parseLangString(
                                VineriumZiplines.inst(), "create_zipline_second_point_set",
                                loc.x.toString(), loc.y.toString(), loc.z.toString()
                            )
                        )
                        event.isCancelled = true
                        return
                    }
                }
                else if (item.persistentDataContainer.has(REMOVE_ZIPLINE_KEY)) {
                    ZiplineManager.instance.ziplineLocations[loc]?.let { ziplineUuid ->
                        ZiplineManager.instance.removeZipline(ziplineUuid)
                        event.player.sendMessage(LangManager.INSTANCE.parseLangString(
                            VineriumZiplines.inst(), "remove_zipline_success"))
                        event.isCancelled = true
                        return
                    }
                }
                val materialToUse = Material.valueOf(VineriumZiplines.inst().config.getString("Ziplines.RequiredItemToUse","BOW")!!)
                if (item.type == materialToUse && ZiplineManager.instance.ziplineLocations.contains(loc)) {
                    ZiplineManager.instance.ziplineLocations[loc]?.let { ziplineUuid ->
                        val zipline = ZiplineManager.instance.ziplines[ziplineUuid] ?: return
                        if (zipline.passengerSubpoints.contains(event.player.uniqueId)
                            || zipline.passengerSubpointsReverse.contains(event.player.uniqueId))
                            return

                        for (index in 0..<zipline.subpoints.size - 1) {
                            if (index >= 2 && index != zipline.subpoints.size - 2) {

                                val subpointLoc = zipline.subpoints[index].toBlockLocation()
                                if (subpointLoc == zipline.firstPoint || subpointLoc == zipline.secondPoint)
                                    continue
                                if (subpointLoc.block.type != Material.AIR) {
                                    val locString = "${subpointLoc.x.toInt()},${subpointLoc.y.toInt()},${subpointLoc.z.toInt()}"
                                    event.player.sendMessage(LangManager.INSTANCE.parseLangString(
                                        VineriumZiplines.inst(), "zipline_obstructed_message","<lang:${subpointLoc.block.type.translationKey()}>",locString))
                                    return
                                }
                            }
                        }

                        ZiplineManager.instance.ziplines[ziplineUuid]?.let { zipline ->
                            if (zipline.firstPoint == loc) {
                                ZiplineManager.instance.startMovement(event.player,ziplineUuid)
                            }
                            else if (zipline.secondPoint == loc) {
                                ZiplineManager.instance.startMovementReverse(event.player,ziplineUuid)
                            }
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        if (event.isCancelled) return
        if (ZiplineManager.instance.ziplineLocations.contains(event.block.location)) {
            event.player.sendMessage(LangManager.INSTANCE.parseLangString(
                VineriumZiplines.inst(), "zipline_location_at_block"))
            event.isCancelled = true
        }
    }
}