package com.qali.headline.renderer

import android.content.Context
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.google.android.filament.*
import com.google.android.filament.gltfio.*
import com.google.android.filament.utils.*
import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A renderer that uses Google's Filament engine to render 3D face masks.
 */
class FaceMaskRenderer(private val context: Context) {
    private var engine: Engine? = null
    private var scene: Scene? = null
    private var view: View? = null
    private var renderer: Renderer? = null
    private var camera: Camera? = null
    private var swapChain: SwapChain? = null

    private var assetLoader: AssetLoader? = null
    private var resourceLoader: ResourceLoader? = null
    private var filamentAsset: FilamentAsset? = null

    private val mainExecutor = context.mainExecutor
    private val loadExecutor = Executors.newSingleThreadExecutor()
    private val isDestroyed = AtomicBoolean(false)

    private var modelEntity: Int = 0

    fun init(surfaceView: SurfaceView) {
        val engine = Engine.create()
        this.engine = engine
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        camera = engine.createCamera(EntityManager.get().create())

        view?.let {
            it.scene = scene
            it.camera = camera
            it.blendMode = View.BlendMode.TRANSLUCENT
        }

        renderer?.setClearOptions(Renderer.ClearOptions().apply {
            clearColor = floatArrayOf(0f, 0f, 0f, 0f)
            clear = true
        })

        assetLoader = AssetLoader(engine, UbershaderProvider(engine), EntityManager.get())
        resourceLoader = ResourceLoader(engine)

        val light = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 1.0f, 1.0f)
            .intensity(100000.0f)
            .direction(0.0f, -1.0f, -1.0f)
            .build(engine, light)
        scene?.addEntity(light)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (isDestroyed.get()) return
                swapChain = engine.createSwapChain(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (isDestroyed.get()) return
                view?.viewport = Viewport(0, 0, width, height)
                val aspect = width.toDouble() / height.toDouble()
                camera?.setProjection(45.0, aspect, 0.1, 100.0, Camera.Fov.VERTICAL)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                swapChain?.let {
                    if (engine.isValid) {
                        engine.destroySwapChain(it)
                    }
                }
                swapChain = null
            }
        })
    }

    fun loadModel(buffer: ByteBuffer) {
        if (isDestroyed.get()) return

        loadExecutor.execute {
            try {
                val assetLoader = this.assetLoader ?: return@execute
                val scene = this.scene ?: return@execute

                val asset = assetLoader.createAsset(buffer)
                if (asset != null) {
                    resourceLoader?.loadResources(asset)
                    asset.releaseSourceData()

                    mainExecutor.execute {
                        if (isDestroyed.get()) {
                            // Too late, already destroyed
                            return@execute
                        }

                        // Remove old asset
                        filamentAsset?.let { oldAsset ->
                            scene.removeEntities(oldAsset.entities)
                            this.assetLoader?.destroyAsset(oldAsset)
                        }

                        filamentAsset = asset
                        scene.addEntities(asset.entities)
                        modelEntity = asset.root
                    }
                }
            } catch (e: Exception) {
                Log.e("FaceMaskRenderer", "Error loading model", e)
            }
        }
    }

    fun updateModelTransform(matrix: FloatArray) {
        val engine = this.engine ?: return
        if (modelEntity != 0 && !isDestroyed.get()) {
            val tm = engine.transformManager
            val instance = tm.getInstance(modelEntity)
            if (instance != 0) {
                tm.setTransform(instance, matrix)
            }
        }
    }

    fun render() {
        if (isDestroyed.get()) return
        val renderer = this.renderer ?: return
        val view = this.view ?: return
        val swapChain = this.swapChain ?: return

        if (renderer.beginFrame(swapChain, System.nanoTime())) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    fun onDestroy() {
        if (isDestroyed.getAndSet(true)) return

        loadExecutor.shutdown()
        mainExecutor.execute {
            val engine = this.engine ?: return@execute

            filamentAsset?.let {
                assetLoader?.destroyAsset(it)
            }
            assetLoader?.destroy()
            resourceLoader?.destroy()

            engine.destroy()
            this.engine = null
        }
    }
}
