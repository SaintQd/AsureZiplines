package org.saintqd.vineriumziplines

import org.bukkit.plugin.java.JavaPlugin
import org.saintqd.vineriumlib.VineriumLib
import org.saintqd.vineriumlib.utils.ResourceUtils
import org.saintqd.vineriumziplines.commands.VinZiplinesCommands
import org.saintqd.vineriumziplines.listeners.PlayerListener
import org.saintqd.vineriumziplines.managers.ZiplineManager
import java.io.File
import java.util.concurrent.TimeUnit

class VineriumZiplines : JavaPlugin() {

    companion object {
        private var plugin : VineriumZiplines? = null

        fun inst() : VineriumZiplines {
            return plugin!!
        }
    }

    override fun onLoad() {
        plugin = this
    }

    override fun onEnable() {
        ResourceUtils.fetchAllResources(this, file)

        loadData()

        VinZiplinesCommands.setupCommands(this)

        server.pluginManager.registerEvents(PlayerListener(), this)

        server.asyncScheduler.runAtFixedRate(this, {
            saveData()
        }, 30L, 30L, TimeUnit.MINUTES)
    }

    override fun onDisable() {
        saveData()
    }

    fun saveData() {
        ZiplineManager.instance.saveZiplineData(this)
        logger.info("Zipline data saved.")
    }

    fun loadData() {
        reloadConfig()

        val selectedLang = getConfig().getString("Language")
        val langLines = VineriumLib.inst().langManager.loadLanguageFile(
            this,
            dataFolder.path + File.separator + "lang" + File.separator + selectedLang + ".yml"
        )
        VineriumLib.inst().langManager.registerLangLines(langLines)

        var prevTime = System.currentTimeMillis()
        ZiplineManager.instance.loadParams(this)
        var time = System.currentTimeMillis()
        logger.info("Loaded " + ZiplineManager.instance.ziplines.size + " ziplines. ("+(time-prevTime)+" ms)")
        prevTime = System.currentTimeMillis()

    }
}