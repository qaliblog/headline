package com.google.mediapipe.examples.facelandmarker.renderer

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.google.android.filament.*
import com.google.android.filament.gltfio.*
import com.google.android.filament.utils.*
import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * A renderer that uses Google's Filament engine to render 3D face masks.
 *
 * Supported formats:
 * - .glb: Best supported (self-contained).
 * - .gltf: Supported (self-contained preferred).
 * - .obj, .fbx, .ply: Not natively supported by Filament on Android.
 *   Note: These formats should be converted to .glb using tools like Blender or
 *   gltf-pipeline before loading.
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

    private var modelEntity: Int = 0

    init {
        Filament.init()
        Gltfio.init()
    }

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
            // Set up view for transparent background
            it.blendMode = View.BlendMode.TRANSLUCENT
        }

        renderer?.setClearOptions(Renderer.ClearOptions().apply {
            clearColor = floatArrayOf(0f, 0f, 0f, 0f)
            clear = true
        })

        assetLoader = AssetLoader(engine, UbershaderProvider(engine), EntityManager.get())
        resourceLoader = ResourceLoader(engine)

        // Add a simple light
        val light = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 1.0f, 1.0f)
            .intensity(100000.0f)
            .direction(0.0f, -1.0f, -1.0f)
            .build(engine, light)
        scene?.addEntity(light)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                swapChain = engine.createSwapChain(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                view?.viewport = Viewport(0, 0, width, height)
                val aspect = width.toDouble() / height.toDouble()
                camera?.setProjection(45.0, aspect, 0.1, 100.0, Camera.Fov.VERTICAL)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = null
            }
        })
    }

    fun loadModel(buffer: ByteBuffer) {
        loadExecutor.execute {
            val assetLoader = this.assetLoader ?: return@execute
            val scene = this.scene ?: return@execute

            mainExecutor.execute {
                val assetToDestroy = filamentAsset
                if (assetToDestroy != null) {
                    scene.removeEntities(assetToDestroy.entities)
                    assetLoader.destroyAsset(assetToDestroy)
                    filamentAsset = null
                }
            }

            val asset = assetLoader.createAsset(buffer)
            if (asset != null) {
                resourceLoader?.loadResources(asset)
                asset.releaseSourceData()
                filamentAsset = asset
                mainExecutor.execute {
                    scene.addEntities(asset.entities)
                    modelEntity = asset.root
                }
            }
        }
    }

    fun updateModelTransform(matrix: FloatArray) {
        val engine = this.engine ?: return
        if (modelEntity != 0) {
            val tm = engine.transformManager
            val instance = tm.getInstance(modelEntity)
            tm.setTransform(instance, matrix)
        }
    }

    fun render() {
        val renderer = this.renderer ?: return
        val view = this.view ?: return
        val swapChain = this.swapChain ?: return

        if (renderer.beginFrame(swapChain, System.nanoTime())) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    fun onDestroy() {
        loadExecutor.shutdown()
        val engine = this.engine ?: return
        assetLoader?.let { it.destroy() }
        resourceLoader?.let { it.destroy() }
        engine.destroy()
    }
}
