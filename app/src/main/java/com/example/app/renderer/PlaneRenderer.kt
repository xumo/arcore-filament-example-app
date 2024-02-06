package com.example.app.renderer

import android.content.Context
import com.example.app.R
import com.example.app.count
import com.example.app.dimenV2A
import com.example.app.dimenV4A
import com.example.app.filament.Filament
import com.example.app.horizontalToUV
import com.example.app.matrix
import com.example.app.polygonToUV
import com.example.app.polygonToVertices
import com.example.app.readUncompressedAsset
import com.example.app.size
import com.example.app.triangleIndexArrayCreate
import com.google.android.filament.Box
import com.google.android.filament.Entity
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.android.filament.utils.TextureType
import com.google.android.filament.utils.loadTexture
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.max
import kotlin.math.min

class PlaneRenderer(context: Context, private val filament: Filament) {
    companion object {
        private const val PLANE_VERTEX_BUFFER_SIZE: Int = 1000
        private const val PLANE_INDEX_BUFFER_SIZE: Int = (PLANE_VERTEX_BUFFER_SIZE - 2) * 3
    }

    private val textureMaterial: Material = context
        .readUncompressedAsset("materials/textured.filamat")
        .let { byteBuffer ->
            Material
                .Builder()
                .payload(byteBuffer, byteBuffer.remaining())
                .build(filament.engine)
        }

    private val textureMaterialInstance: MaterialInstance = textureMaterial
        .createInstance()
        .also { materialInstance ->
            materialInstance.setParameter(
                "texture",
                loadTexture(filament.engine, context.resources, R.drawable.sceneform_plane, TextureType.COLOR),
                TextureSampler().also { it.anisotropy = 8.0f },
            )

//            materialInstance.setParameter("alpha", 1f)
        }

    private val shadowMaterial: Material = context
        .readUncompressedAsset("materials/shadow.filamat")
        .let { byteBuffer ->
            Material
                .Builder()
                .payload(byteBuffer, byteBuffer.remaining())
                .build(filament.engine)
        }

    private val planeVertexFloatBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(PLANE_VERTEX_BUFFER_SIZE * dimenV4A * Float.size)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val planeUvFloatBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(PLANE_VERTEX_BUFFER_SIZE * dimenV2A * Float.size)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val planeIndexShortBuffer: ShortBuffer = ShortBuffer
        .allocate(PLANE_INDEX_BUFFER_SIZE)

    private val planeVertexBuffer: VertexBuffer = VertexBuffer
        .Builder()
        .vertexCount(PLANE_VERTEX_BUFFER_SIZE)
        .bufferCount(2)
        .attribute(
            VertexBuffer.VertexAttribute.POSITION,
            0,
            VertexBuffer.AttributeType.FLOAT4,
            0,
            0,
        )
        .attribute(
            VertexBuffer.VertexAttribute.UV0,
            1,
            VertexBuffer.AttributeType.FLOAT2,
            0,
            0,
        )
        .build(filament.engine)

    private val planeIndexBuffer: IndexBuffer = IndexBuffer
        .Builder()
        .indexCount(PLANE_INDEX_BUFFER_SIZE)
        .bufferType(IndexBuffer.Builder.IndexType.USHORT)
        .build(filament.engine)

    @Entity
    private val planeRenderable: Int =
        EntityManager.get().create().also { filament.scene.addEntity(it) }

    fun doFrame(frame: Frame) {
        // update plane trackables
        val planeTrackables = frame
            .getUpdatedTrackables(Plane::class.java)
            .map { plane -> plane.subsumedBy ?: plane }
            .toSet()
            .filter { plane -> plane.trackingState == TrackingState.TRACKING }
            .also { if (it.isEmpty()) return }
            .sortedBy { it.type != Plane.Type.HORIZONTAL_UPWARD_FACING }

        val indexNotUpwardFacing = planeTrackables
            .indexOfFirst { it.type != Plane.Type.HORIZONTAL_UPWARD_FACING }

        var xMin = Float.POSITIVE_INFINITY
        var xMax = Float.NEGATIVE_INFINITY
        var yMin = Float.POSITIVE_INFINITY
        var yMax = Float.NEGATIVE_INFINITY
        var zMin = Float.POSITIVE_INFINITY
        var zMax = Float.NEGATIVE_INFINITY

        var vertexBufferOffset = 0
        var indexBufferOffset = 0

        var indexWithoutShadow: Int? = null

        planeVertexFloatBuffer.rewind()
        planeUvFloatBuffer.rewind()
        planeIndexShortBuffer.rewind()

        for (i in 0 until planeTrackables.count()) {
            val plane = planeTrackables[i]

            // index of first triangle that doesn't have shadows applied
            if (i == indexNotUpwardFacing) {
                indexWithoutShadow = indexBufferOffset
            }

            // gets plane vertices in world space
            val planeVertices = plane.polygon.polygonToVertices(plane.centerPose.matrix())

            // triangle fan of indices over convex polygon
            val planeTriangleIndices =
                triangleIndexArrayCreate(
                    planeVertices.count - 2,
                    { vertexBufferOffset.toShort() },
                    { k -> (vertexBufferOffset + k + 1).toShort() },
                    { k -> (vertexBufferOffset + k + 2).toShort() },
                )

            // check for for buffer overflow
            if (vertexBufferOffset + planeVertices.count > PLANE_VERTEX_BUFFER_SIZE ||
                indexBufferOffset + planeTriangleIndices.shortArray.count() > PLANE_INDEX_BUFFER_SIZE
            ) {
                break
            }

            for (k in planeVertices.floatArray.indices step 4) {
                xMin = min(planeVertices.floatArray[k + 0], xMin)
                xMax = max(planeVertices.floatArray[k + 0], xMax)
                yMin = min(planeVertices.floatArray[k + 1], yMin)
                yMax = max(planeVertices.floatArray[k + 1], yMax)
                zMin = min(planeVertices.floatArray[k + 2], zMin)
                zMax = max(planeVertices.floatArray[k + 2], zMax)
            }

            // push out data to nio buffers
            planeVertexFloatBuffer.put(planeVertices.floatArray)

            planeUvFloatBuffer.put(
                if (plane.type == Plane.Type.VERTICAL) {
                    // uv coordinates from model space
                    plane.polygon.polygonToUV()
                } else {
                    // uv coordinates from world space
                    planeVertices.horizontalToUV()
                }.floatArray,
            )

            planeIndexShortBuffer.put(planeTriangleIndices.shortArray)

            vertexBufferOffset += planeVertices.count
            indexBufferOffset += planeTriangleIndices.shortArray.count()
        }

        // push nio buffers to gpu
        var count = planeVertexFloatBuffer.capacity() - planeVertexFloatBuffer.remaining()
        planeVertexFloatBuffer.rewind()

        planeVertexBuffer.setBufferAt(
            filament.engine,
            0,
            planeVertexFloatBuffer,
            0,
            count,
        )

        count = planeUvFloatBuffer.capacity() - planeUvFloatBuffer.remaining()
        planeUvFloatBuffer.rewind()

        planeVertexBuffer.setBufferAt(
            filament.engine,
            1,
            planeUvFloatBuffer,
            0,
            count,
        )

        count = planeIndexShortBuffer.capacity() - planeIndexShortBuffer.remaining()
        planeIndexShortBuffer.rewind()

        planeIndexBuffer.setBuffer(
            filament.engine,
            planeIndexShortBuffer,
            0,
            count,
        )

        // update renderable index buffer count
        RenderableManager
            .Builder(2)
            .castShadows(false)
            .receiveShadows(true)
            .culling(true)
            .boundingBox(
                Box(
                    (xMin + xMax) / 2f,
                    (yMin + yMax) / 2f,
                    (zMin + zMax) / 2f,
                    (xMax - xMin) / 2f,
                    (yMax - yMin) / 2f,
                    (zMax - zMin) / 2f,
                )
            )
            .geometry( // texture is applied to all triangles
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                planeVertexBuffer,
                planeIndexBuffer,
                0,
                indexBufferOffset,
            )
            .material(0, textureMaterialInstance)
            .geometry( // shadows are applied to upward facing triangles
                1,
                RenderableManager.PrimitiveType.TRIANGLES,
                planeVertexBuffer,
                planeIndexBuffer,
                0,
                indexWithoutShadow ?: indexBufferOffset,
            )
            .material(1, shadowMaterial.defaultInstance)
            .build(filament.engine, planeRenderable)
    }
}
