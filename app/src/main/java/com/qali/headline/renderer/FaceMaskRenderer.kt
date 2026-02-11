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
import java.nio.ByteOrder
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
    private val isDestroyed = AtomicBoolean(false)

    private var modelEntity: Int = 0
    private var debugCubeEntity: Int = 0
    private var debugMode: Boolean = false

    fun setDebugMode(enabled: Boolean) {
        this.debugMode = enabled
        mainExecutor.execute {
            if (debugMode) {
                createDebugCube()
            } else {
                removeDebugCube()
            }
        }
    }

    private fun createDebugCube() {
        val engine = this.engine ?: return
        if (debugCubeEntity != 0) return

        debugCubeEntity = EntityManager.get().create()

        // Simple cube vertices (pos, color)
        val vertices = floatArrayOf(
            -0.05f, -0.05f,  0.05f, 1f, 0f, 0f,
             0.05f, -0.05f,  0.05f, 0f, 1f, 0f,
             0.05f,  0.05f,  0.05f, 0f, 0f, 1f,
            -0.05f,  0.05f,  0.05f, 1f, 1f, 0f,
            -0.05f, -0.05f, -0.05f, 1f, 0f, 1f,
             0.05f, -0.05f, -0.05f, 0f, 1f, 1f,
             0.05f,  0.05f, -0.05f, 1f, 1f, 1f,
            -0.05f,  0.05f, -0.05f, 0f, 0f, 0f
        )
        val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
            .flip()

        val vb = VertexBuffer.Builder()
            .vertexCount(8)
            .bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 24)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 0, VertexBuffer.AttributeType.FLOAT3, 12, 24)
            .build(engine)

        vb.setBufferAt(engine, 0, vertexBuffer)

        val indices = shortArrayOf(
            0, 1, 2, 2, 3, 0,
            1, 5, 6, 6, 2, 1,
            5, 4, 7, 7, 6, 5,
            4, 0, 3, 3, 7, 4,
            3, 2, 6, 6, 7, 3,
            0, 1, 5, 5, 4, 0
        )
        val indexBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(indices)
            .flip()

        // Using fully qualified name for IndexType based on jar inspection
        val ib = IndexBuffer.Builder()
            .indexCount(indices.size)
            .bufferType(com.google.android.filament.IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)

        ib.setBuffer(engine, indexBuffer)

        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, 0.05f, 0.05f, 0.05f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib)
            .build(engine, debugCubeEntity)

        scene?.addEntity(debugCubeEntity)
    }

    private fun removeDebugCube() {
        val engine = this.engine ?: return
        if (debugCubeEntity != 0) {
            scene?.removeEntity(debugCubeEntity)
            engine.destroyEntity(debugCubeEntity)
            debugCubeEntity = 0
        }
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
                val sc = swapChain
                if (sc != null && engine.isValid) {
                    engine.destroySwapChain(sc)
                }
                swapChain = null
            }
        })
    }

    fun loadModel(buffer: ByteBuffer) {
        if (isDestroyed.get()) return

        mainExecutor.execute {
            if (isDestroyed.get()) return@execute

            try {
                val assetLoader = this.assetLoader ?: return@execute
                val scene = this.scene ?: return@execute

                filamentAsset?.let { oldAsset ->
                    scene.removeEntities(oldAsset.entities)
                    assetLoader.destroyAsset(oldAsset)
                    filamentAsset = null
                }

                val asset = assetLoader.createAsset(buffer)
                if (asset != null) {
                    resourceLoader?.loadResources(asset)
                    asset.releaseSourceData()

                    filamentAsset = asset
                    scene.addEntities(asset.entities)
                    modelEntity = asset.root
                }
            } catch (e: Exception) {
                Log.e("FaceMaskRenderer", "Error loading model", e)
            }
        }
    }

    fun updateModelTransform(matrix: FloatArray) {
        val engine = this.engine ?: return
        if (isDestroyed.get()) return

        if (debugMode) {
            Log.d("FaceMaskRenderer", "Final Matrix: ${matrix.joinToString(", ")}")
        }

        if (modelEntity != 0) {
            val tm = engine.transformManager
            val instance = tm.getInstance(modelEntity)
            if (instance != 0) {
                tm.setTransform(instance, matrix)
            }
        }

        if (debugCubeEntity != 0) {
            val tm = engine.transformManager
            val instance = tm.getInstance(debugCubeEntity)
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

        mainExecutor.execute {
            val engine = this.engine ?: return@execute

            filamentAsset?.let {
                assetLoader?.destroyAsset(it)
            }
            removeDebugCube()
            assetLoader?.destroy()
            resourceLoader?.destroy()

            engine.destroy()
            this.engine = null
        }
    }
}
