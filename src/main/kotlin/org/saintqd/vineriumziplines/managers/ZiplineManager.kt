package org.saintqd.vineriumziplines.managers

import org.bukkit.*
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import org.saintqd.vineriumlib.managers.LangManager
import org.saintqd.vineriumziplines.VineriumZiplines
import java.nio.file.Paths
import java.util.*
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt


class ZiplineManager {

    companion object {
        val instance : ZiplineManager = ZiplineManager()
    }

    val ziplinePlayers = hashMapOf<UUID, ZiplinePlayer>()
    val ziplines = hashMapOf<UUID, VineriumZipline>()
    val ziplineLocations = hashMapOf<Location, UUID>()
    var particleTask : BukkitTask? = null
    var arcCoef = 2.5
    var minArcHeight = 2.0
    var subpointsPerBlock = 3
    var particleRadius = 25.0

    data class ZiplinePlayer(
        var firstPoint: Location? = null,
        var secondPoint: Location? = null
    )

    data class VineriumZipline(
        val firstPoint : Location,
        val secondPoint : Location,
        val axis: Boolean,
        val midArcPoint : Location,
        val subpoints : List<Location>,
        val passengerSubpoints : HashMap<UUID, Int>,
        var movementTask : BukkitTask?,
        val passengerSubpointsReverse : HashMap<UUID, Int>,
        var movementTaskReverse : BukkitTask?
    )

    fun loadParams(plugin : Plugin) {
        ziplines.clear()
        ziplineLocations.clear()

        arcCoef = VineriumZiplines.inst().config.getDouble("Ziplines.ArcCoef",1.25)
        minArcHeight = VineriumZiplines.inst().config.getDouble("Ziplines.MinArcHeight",2.0)
        subpointsPerBlock = VineriumZiplines.inst().config.getInt("Ziplines.SubpointsPerBlock",3)
        particleRadius = VineriumZiplines.inst().config.getDouble("Ziplines.ParticleRadius",25.0)

        val ziplineFilePath = Paths.get(plugin.dataFolder.path, "ZiplineData.yml")
        val ziplineFile = ziplineFilePath.toFile()
        ziplineFile.createNewFile()

        val ziplineYaml = YamlConfiguration.loadConfiguration(ziplineFile)
        for (ziplineData in ziplineYaml.getStringList("Ziplines")) {
            val ziplineDataList = ziplineData.split(";")
            val firstPoint = deserializeZiplinePoint(ziplineDataList[0].split(","))
            val secondPoint = deserializeZiplinePoint(ziplineDataList[1].split(","))
            val axis = ziplineDataList[2].toBoolean()

            createZipline(firstPoint,secondPoint,axis,true)
        }
        val ziplinesToRemove = hashSetOf<UUID>()
        for (ziplineLoc in ziplineLocations.keys) {
            if (ziplineLoc.block.type == Material.AIR) {
                ziplinesToRemove.add(ziplineLocations[ziplineLoc]!!)
            }
        }
        for (ziplineUUID in ziplinesToRemove) {
            removeZipline(ziplineUUID)
        }
        //setupParticleTask()
    }

    fun saveZiplineData(plugin : Plugin) {

        val ziplineFilePath = Paths.get(plugin.dataFolder.path, "ZiplineData.yml")
        val ziplineFile = ziplineFilePath.toFile()
        ziplineFile.createNewFile()

        val ziplineYaml = YamlConfiguration.loadConfiguration(ziplineFile)
        val data = mutableListOf<String>()
        for (zipline in ziplines) {
            val firstPoint = serializeZiplinePoint(zipline.value.firstPoint)
            val secondPoint = serializeZiplinePoint(zipline.value.secondPoint)
            val finalString = "$firstPoint;$secondPoint;${zipline.value.axis}"
            data.add(finalString)
        }
        ziplineYaml.set("Ziplines",data)
        ziplineYaml.save(ziplineFile)
    }

    fun createZipline(firstPoint: Location, secondPoint: Location, axis : Boolean, load : Boolean = false) : VineriumZipline? {

        if (axis) {

            if (firstPoint.x > secondPoint.x) {
                val buffer = firstPoint.clone()
                firstPoint.set(secondPoint.x,secondPoint.y,secondPoint.z)
                secondPoint.set(buffer.x,buffer.y,buffer.z)
            }

            val midPointX = (firstPoint.x + secondPoint.x) / 2.0
            val midPointY = (firstPoint.y + secondPoint.y) / 2.0

            val distance = hypot(secondPoint.x - firstPoint.x, secondPoint.y - firstPoint.y)
            val radius = distance * arcCoef
            if (distance > 2.0 * radius) {
                // Points are too far apart; no real circle exists
                return null
            }
            if (distance == 0.0) {
                // Points are identical; infinite circles possible
                return null
            }
            if (distance > VineriumZiplines.inst().config.getDouble("Ziplines.MaxLength"))
                return null

            val halfDistance = distance / 2
            val centerToMidpoint = sqrt(radius * radius - halfDistance * halfDistance)

            val centerX = centerToMidpoint * (firstPoint.y - secondPoint.y) / distance
            val centerY = centerToMidpoint * (secondPoint.x - firstPoint.x) / distance

            val centerPoint = Location(firstPoint.world,midPointX + centerX, midPointY + centerY,firstPoint.z)

            val arcHeight = (radius - centerToMidpoint).coerceAtLeast(minArcHeight)
            val midPoint = Location(firstPoint.world, midPointX, midPointY,firstPoint.z)
            val midArcPoint = midPoint.clone().add(0.0,-arcHeight,0.0)

            val pointsAmount = (distance * subpointsPerBlock).toInt()

            val subpoints = mutableListOf<Location>()

            for (index in 0..pointsAmount) {
                val time = index.toDouble() / pointsAmount

                // Quadratic Bezier curve formulas
                val x = (1 - time) * (1 - time) * firstPoint.x + 2.0 *
                        (1 - time) * time * midArcPoint.x + time * time * secondPoint.x
                val y = (1 - time) * (1 - time) * firstPoint.y + 2.0 *
                        (1 - time) * time * midArcPoint.y + time * time * secondPoint.y

                val subpointLocation = Location(firstPoint.world,x,y,firstPoint.z)

                subpoints.add(subpointLocation)
            }

            val zipline = VineriumZipline(firstPoint,
                secondPoint,axis,midArcPoint,subpoints,hashMapOf(),
                null,hashMapOf(),null)
            val uuid = UUID.randomUUID()
            ziplines[uuid] = zipline
            ziplineLocations[zipline.firstPoint] = uuid
            ziplineLocations[zipline.secondPoint] = uuid

            if (!load) {
                val blockDisplayPointsAmount = distance.toInt()

                for (index in 0..blockDisplayPointsAmount) {
                    val time = index.toDouble() / blockDisplayPointsAmount

                    // Quadratic Bezier curve formulas
                    val x = (1 - time) * (1 - time) * firstPoint.x + 2.0 *
                            (1 - time) * time * midArcPoint.x + time * time * secondPoint.x
                    val y = (1 - time) * (1 - time) * firstPoint.y + 2.0 *
                            (1 - time) * time * midArcPoint.y + time * time * secondPoint.y

                    firstPoint.world.spawn(Location(firstPoint.world,x,y + 0.5,firstPoint.z), BlockDisplay::class.java) { blockDisplay ->
                        val blockData = Material.IRON_CHAIN.createBlockData()
                        blockDisplay.block = blockData

                        val deltaY = y - centerPoint.y
                        val deltaX = x - centerPoint.x

                        val radians = atan2(deltaY, deltaX)

                        val rotation = AxisAngle4f(radians.toFloat(), 0f, 0f, 1f)
                        val transform = Transformation(Vector3f(), rotation,
                            Vector3f(1f,1.2f,1f), AxisAngle4f())
                        blockDisplay.transformation = transform
                    }
                }
            }

            return zipline

        }
        else {

            if (firstPoint.z > secondPoint.z) {
                val buffer = firstPoint.clone()
                firstPoint.set(secondPoint.x,secondPoint.y,secondPoint.z)
                secondPoint.set(buffer.x,buffer.y,buffer.z)
            }

            val midPointZ = (firstPoint.z + secondPoint.z) / 2.0
            val midPointY = (firstPoint.y + secondPoint.y) / 2.0
            val distance = hypot(secondPoint.z - firstPoint.z, secondPoint.y - firstPoint.y)
            val radius = distance * arcCoef
            if (distance > 2.0 * radius) {
                // Points are too far apart; no real circle exists
                return null;
            }
            if (distance == 0.0) {
                // Points are identical; infinite circles possible
                return null;
            }

            val halfDistance = distance / 2
            val centerToMidpoint = sqrt(radius * radius - halfDistance * halfDistance)

            val centerZ = centerToMidpoint * (firstPoint.y - secondPoint.y) / distance
            val centerY = centerToMidpoint * (secondPoint.z - firstPoint.z) / distance

            val centerPoint = Location(firstPoint.world,firstPoint.x, midPointY + centerY,midPointZ + centerZ)

            val arcHeight = (radius - centerToMidpoint).coerceAtLeast(minArcHeight)
            val midPoint = Location(firstPoint.world, firstPoint.x, midPointY,midPointZ)
            val midArcPoint = midPoint.clone().add(0.0,-arcHeight,0.0)

            val pointsAmount = (distance * subpointsPerBlock).toInt()

            val subpoints = mutableListOf<Location>()

            for (index in 0..pointsAmount) {
                val time = index.toDouble() / pointsAmount

                // Quadratic Bezier curve formulas
                val z = (1 - time) * (1 - time) * firstPoint.z + 2.0 *
                        (1 - time) * time * midArcPoint.z + time * time * secondPoint.z
                val y = (1 - time) * (1 - time) * firstPoint.y + 2.0 *
                        (1 - time) * time * midArcPoint.y + time * time * secondPoint.y

                subpoints.add(Location(firstPoint.world,firstPoint.x,y,z))
            }

            val zipline = VineriumZipline(firstPoint,
                secondPoint,axis,midArcPoint,subpoints,hashMapOf(),
                null,hashMapOf(),null)
            val uuid = UUID.randomUUID()
            ziplines[uuid] = zipline
            ziplineLocations[zipline.firstPoint] = uuid
            ziplineLocations[zipline.secondPoint] = uuid

            if (!load) {
                val blockDisplayPointsAmount = distance.toInt()

                for (index in 0..blockDisplayPointsAmount) {
                    val time = index.toDouble() / blockDisplayPointsAmount

                    // Quadratic Bezier curve formulas
                    val z = (1 - time) * (1 - time) * firstPoint.z + 2.0 *
                            (1 - time) * time * midArcPoint.z + time * time * secondPoint.z
                    val y = (1 - time) * (1 - time) * firstPoint.y + 2.0 *
                            (1 - time) * time * midArcPoint.y + time * time * secondPoint.y

                    firstPoint.world.spawn(Location(firstPoint.world,firstPoint.x,y + 0.5,z), BlockDisplay::class.java) { blockDisplay ->
                        val blockData = Material.IRON_CHAIN.createBlockData()
                        blockDisplay.block = blockData

                        val deltaY = y - centerPoint.y
                        val deltaZ = z - centerPoint.z

                        val radians = -atan2(deltaY, deltaZ)

                        val rotation = AxisAngle4f(radians.toFloat(), 1f, 0f, 0f)
                        val transform = Transformation(Vector3f(), rotation,
                            Vector3f(1f,1.2f,1f), AxisAngle4f())
                        blockDisplay.transformation = transform
                    }
                }
            }

            return zipline
        }
    }

    fun removeZipline(uuid : UUID) {
        ziplines[uuid]?.let { zipline ->

            for (subpoint in zipline.subpoints) {
                subpoint.getNearbyEntitiesByType(BlockDisplay::class.java,2.0).forEach { blockDisplay ->
                    blockDisplay.remove()
                }
            }

            ziplineLocations.remove(zipline.firstPoint)
            ziplineLocations.remove(zipline.secondPoint)
            ziplines.remove(uuid)
        }
    }

    private fun serializeZiplinePoint(loc : Location): String {
        return "${loc.world.name},${loc.x},${loc.y},${loc.z}"
    }

    private fun deserializeZiplinePoint(ziplineData : List<String>) : Location {
        val world = Bukkit.getWorld(ziplineData[0])
        val x = ziplineData[1].toDouble()
        val y = ziplineData[2].toDouble()
        val z = ziplineData[3].toDouble()
        return Location(world, x, y, z)
    }

    fun startMovement(player : Player, uuid : UUID) {
        ziplines[uuid]?.let { zipline ->

            val firstSubpoint = zipline.subpoints[0]
            val secondSubpoint = zipline.subpoints[1]

            val vector = secondSubpoint.toVector().subtract(firstSubpoint.toVector())
            val newLocation = player.location.clone()
            newLocation.direction = vector
            player.teleport(newLocation)

            if (zipline.passengerSubpoints.isEmpty()) {
                zipline.passengerSubpoints[player.uniqueId] = 0
                val runnable : Runnable = {
                    val passengersToRemove = hashSetOf<UUID>()
                    for (passengerData in zipline.passengerSubpoints) {
                        val passengerPlayer = Bukkit.getPlayer(passengerData.key)
                        if (passengerPlayer == null || !passengerPlayer.isValid) {
                            passengersToRemove.add(passengerData.key)
                            continue
                        }
                        var nextSubpoint = passengerData.value

                        val teleportLoc = zipline.subpoints[nextSubpoint].clone().add(0.0,-1.5,0.0)
                        teleportLoc.direction = vector

                        passengerPlayer.teleport(teleportLoc)
                        nextSubpoint++
                        if (nextSubpoint < zipline.subpoints.size && !passengerPlayer.isSneaking)
                            zipline.passengerSubpoints[passengerPlayer.uniqueId] = nextSubpoint
                        else
                            passengersToRemove.add(passengerData.key)
                    }
                    for (passengerUuid in passengersToRemove) {
                        zipline.passengerSubpoints.remove(passengerUuid)
                        if (zipline.passengerSubpoints.isEmpty()) {
                            zipline.movementTask?.cancel()
                        }
                    }
                }
                zipline.movementTask = Bukkit.getScheduler().runTaskTimer(
                    VineriumZiplines.inst(),runnable,1L,1L)
            }
            else
                zipline.passengerSubpoints[player.uniqueId] = 0
        }
    }

    fun startMovementReverse(player : Player, uuid : UUID) {
        ziplines[uuid]?.let { zipline ->
            val firstSubpoint = zipline.subpoints[zipline.subpoints.size - 1]
            val secondSubpoint = zipline.subpoints[zipline.subpoints.size - 2]

            val vector = secondSubpoint.toVector().subtract(firstSubpoint.toVector())
            val newLocation = player.location.clone()
            newLocation.direction = vector
            player.teleport(newLocation)

            if (zipline.passengerSubpointsReverse.isEmpty()) {
                zipline.passengerSubpointsReverse[player.uniqueId] = zipline.subpoints.size - 1
                val runnable : Runnable = {
                    val passengersToRemove = hashSetOf<UUID>()
                    for (passengerData in zipline.passengerSubpointsReverse) {
                        val passengerPlayer = Bukkit.getPlayer(passengerData.key)
                        if (passengerPlayer == null || !passengerPlayer.isValid) {
                            passengersToRemove.add(passengerData.key)
                            continue
                        }
                        var nextSubpoint = passengerData.value

                        val teleportLoc = zipline.subpoints[nextSubpoint].clone().add(0.0,-1.5,0.0)
                        teleportLoc.direction = vector

                        passengerPlayer.teleport(teleportLoc)
                        nextSubpoint--
                        if (nextSubpoint >= 0 && !passengerPlayer.isSneaking)
                            zipline.passengerSubpointsReverse[passengerPlayer.uniqueId] = nextSubpoint
                        else
                            passengersToRemove.add(passengerData.key)
                    }
                    for (passengerUuid in passengersToRemove) {
                        zipline.passengerSubpointsReverse.remove(passengerUuid)
                        if (zipline.passengerSubpointsReverse.isEmpty()) {
                            zipline.movementTaskReverse?.cancel()
                        }
                    }
                }
                zipline.movementTaskReverse = Bukkit.getScheduler().runTaskTimer(
                    VineriumZiplines.inst(),runnable,1L,1L)
            }
            else
                zipline.passengerSubpointsReverse[player.uniqueId] = zipline.subpoints.size - 1
        }
    }
}