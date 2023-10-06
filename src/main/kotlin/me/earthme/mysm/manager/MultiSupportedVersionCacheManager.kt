package me.earthme.mysm.manager

import me.earthme.mysm.data.YsmModelFileInstance
import me.earthme.mysm.data.YsmPasswordFileInstance
import me.earthme.mysm.data.YsmVersionMeta
import me.earthme.mysm.data.YsmVersionMetaArray
import me.earthme.mysm.utils.AsyncExecutor
import me.earthme.mysm.utils.FileUtils
import me.earthme.mysm.utils.HttpsUtils
import me.earthme.mysm.utils.ysm.MD5Utils
import org.bukkit.NamespacedKey
import org.bukkit.plugin.Plugin
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import java.util.function.Function
import java.util.logging.Level
import kotlin.collections.ArrayList

object MultiSupportedVersionCacheManager {
    private val versionMetaMap: MutableSet<YsmVersionMeta> = ConcurrentHashMap.newKeySet()
    private val loadCacheMap: MutableMap<YsmVersionMeta,URLClassLoader> = ConcurrentHashMap()
    private val loadedModel: MutableMap<String,MutableMap<YsmVersionMeta,String>> = ConcurrentHashMap()
    private val loadedModelFile: MutableMap<String,File> = ConcurrentHashMap()

    private var baseCacheDir: File = File("caches")
    private var modJarFolder: File = File("modjars")
    private var modelDir: File = File("models")

    private var passwordFile: File = File("password.ysmdata")
    private var passwordFileInstance: YsmPasswordFileInstance = YsmPasswordFileInstance.random
    private var pluginInstance: Plugin? = null
    private var metaArray: YsmVersionMetaArray? = null

    fun reload(){
        this.dropAllCaches()
        this.dropAll()
        this.writeAllModelsToCache()
    }

    private fun dropAll(){
        this.loadCacheMap.clear()
        this.loadedModel.clear()
        this.loadedModelFile.clear()
    }

    fun refreshCache(modelName: String){
        val needToRemove: MutableList<File> = ArrayList()
        for ((version,fileName) in loadedModel[modelName]!!){
            val targetFolder = File(baseCacheDir,"version_${version.version}_${version.modLoader}")
            val targetFile = File(targetFolder,fileName)

            needToRemove.add(targetFile)
        }

        for (file in needToRemove){
            file.delete()
        }

        for (singleVersion in versionMetaMap){
            val targetFile = this.loadedModelFile[modelName]!!

            this.writeToCache(targetFile,singleVersion)
        }
    }
    
    fun getModelDir(): File{
        return this.modelDir
    }

    fun getVersionMeta(modLoader: String,protocolId: Int): YsmVersionMeta? {
        for (singleMeta in versionMetaMap){
            if (singleMeta.version == protocolId && singleMeta.modLoader == modLoader){
                return singleMeta
            }
        }

        return null
    }

    fun getCacheDataWithMd5(md5Excludes: List<String>,actionIfNotContained: Consumer<ByteArray>,actionIfContained: Consumer<String>,version: YsmVersionMeta){
        for (entry in loadedModel){
            val version2Model = entry.value
            val md5WithFileName = version2Model[version]!!
            if (md5Excludes.contains(md5WithFileName)){
                actionIfContained.accept(md5WithFileName)
                continue
            }

            val targetFolder = File(baseCacheDir,"version_${version.version}_${version.modLoader}")
            val targetFile = File(targetFolder,md5WithFileName)
            actionIfNotContained.accept(Files.readAllBytes(targetFile.toPath()))
        }
    }

    fun writeAllModelsToCache(){
        if (modelDir.mkdir()){
            return
        }

        modelDir.listFiles()?.let{
            CompletableFuture.allOf(
                *Arrays.stream(it)
                    .map { file -> CompletableFuture.runAsync({
                        try {
                            if (!file.isDirectory){
                                return@runAsync
                            }

                            for (singleVersionMeta in versionMetaMap){
                                writeToCache(file,singleVersionMeta)
                            }

                            loadedModelFile[FileUtils.fileNameWithoutExtension(file.name)] = file
                        }catch (e: Exception){
                            this.pluginInstance!!.logger.log(Level.SEVERE,"Error while loading model ${file.name}!",e)
                        }
                    }, AsyncExecutor.ASYNC_EXECUTOR_INSTANCE) }
                    .toArray { i -> arrayOfNulls(i) }
            ).join()
        }
    }

    fun getPasswordData(): ByteArray{
        return this.passwordFileInstance.data
    }

    private fun writeToCache(fileModelFile: File, version: YsmVersionMeta){
        val dataClassLoader: URLClassLoader = getCacheDataClassLoaderForVersion(version)!!
        val modelFileInstance: YsmModelFileInstance = YsmModelFileInstance.createFromFolder(
            arrayListOf(*fileModelFile.listFiles()!!),
            fileModelFile.name,
            Function { return@Function this.needModelAuth(it) },
            dataClassLoader.loadClass(version.dataClassName),
            dataClassLoader as ClassLoader
        )

        val cacheData = modelFileInstance.toWritableBytes(modelFileInstance, passwordFileInstance.secretKey,
            passwordFileInstance.algorithmParameterSpec)
        val fileName = MD5Utils.getMd5(cacheData)
        val targetFolder = File(baseCacheDir,"version_${version.version}_${version.modLoader}")
        targetFolder.mkdir()
        val targetCacheFile = File(targetFolder,fileName)
        Files.write(targetCacheFile.toPath(),cacheData)

        if (!loadedModel.containsKey(modelFileInstance.getModelName())) {
            loadedModel[modelFileInstance.getModelName()] = ConcurrentHashMap()
        }

        loadedModel[modelFileInstance.getModelName()]!![version] = fileName
    }

    private fun needModelAuth(modelName: String): Boolean{
        return ModelPermissionManager.isModelNeedAuth(NamespacedKey("yes_steve_model",modelName))
    }

    private fun getCacheDataClassLoaderForVersion(version: YsmVersionMeta): URLClassLoader?{
        return loadCacheMap[version]
    }

    private fun loadVersionMeta(){
        val metaArrayInputStream = MultiSupportedVersionCacheManager::class.java.classLoader.getResourceAsStream("ysm_data/ysm_version_meta.json")
        val loadData = FileUtils.readInputStreamToByte(metaArrayInputStream!!)
        this.metaArray = YsmVersionMetaArray.readFromJson(String(loadData!!))
    }

    private fun downloadModJars(){
        this.modJarFolder.mkdir()
        val asyncTasks: MutableList<CompletableFuture<Int>> = ArrayList()
        for (singleMeta in metaArray!!.versionMetas){
            val targetFile = File(this.modJarFolder,"modjar_"+singleMeta.version+"_"+singleMeta.modLoader+".jar")

            if (!targetFile.exists()){
                this.pluginInstance!!.logger.info("Mod jar ${targetFile.name} has not found!Downloading from curse forge")
                asyncTasks.add(CompletableFuture.supplyAsync({
                    try {
                        val downloaded = HttpsUtils.downloadFrom(singleMeta.downloadLink)!!
                        Files.write(targetFile.toPath(),downloaded)

                        loadCacheMap[singleMeta] = URLClassLoader(arrayOf(targetFile.toURI().toURL()), MultiSupportedVersionCacheManager::class.java.classLoader)
                        versionMetaMap.add(singleMeta)
                    }catch (e: Exception){
                        this.pluginInstance!!.logger.log(Level.SEVERE,"Error while downloading jar file!",e)
                    }

                    return@supplyAsync singleMeta.version
                },AsyncExecutor.ASYNC_EXECUTOR_INSTANCE))
            }else{
                this.pluginInstance!!.logger.info("Loading exists jar ${targetFile.name}")
                loadCacheMap[singleMeta] = URLClassLoader(arrayOf(targetFile.toURI().toURL()), MultiSupportedVersionCacheManager::class.java.classLoader)
                versionMetaMap.add(singleMeta)
            }
        }

        for (singleTask in asyncTasks){
            try {
                this.pluginInstance!!.logger.info("Loaded jar for mc version ${singleTask.join()}")
            }catch (e: Exception){
                e.printStackTrace()
            }
        }
    }

    fun dropAllCaches(){
        FileUtils.forEachFolder(baseCacheDir){
            try {
                if (!it.isDirectory){
                    it.delete()
                }
            }catch (e : Exception){
                this.pluginInstance!!.logger.log(Level.SEVERE,"Error while deleting cache file ${it.name}!",e)
            }
        }
    }

    fun init(pluginInstance: Plugin){
        this.pluginInstance = pluginInstance
        modelDir = File(pluginInstance.dataFolder,"ysm_models")
        baseCacheDir = File(pluginInstance.dataFolder,"ysm_caches")
        passwordFile = File(pluginInstance.dataFolder,"password.ysmdata")
        this.modJarFolder = File(pluginInstance.dataFolder,"mod_jars")

        if (passwordFile.exists()){
            pluginInstance.logger.info("Loading exists password file")
            passwordFileInstance = YsmPasswordFileInstance.readFromFile(passwordFile)!!
        }else{
            pluginInstance.logger.info("Creating password file")
            Files.write(passwordFile.toPath(), passwordFileInstance.encodeToByte())
        }

        this.dropAllCaches()
        this.loadVersionMeta()
        this.downloadModJars()
        this.writeAllModelsToCache()
    }
}