package com.example.app.renderer

import android.content.Context
import com.example.app.V3
import com.example.app.aractivity.ScreenPosition
import com.example.app.arcore.ArCore
import com.example.app.clampToTau
import com.example.app.filament.Filament
import com.example.app.m4Identity
import com.example.app.rotate
import com.example.app.scale
import com.example.app.toDegrees
import com.example.app.translate
import com.example.app.v3Origin
import com.example.app.x
import com.example.app.y
import com.example.app.z
import com.google.ar.core.Frame
import com.google.ar.core.Point
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class ModelRenderer(context: Context, private val arCore: ArCore, private val filament: Filament) {
    sealed class ModelEvent {
        data class Move(val screenPosition: ScreenPosition) : ModelEvent()
        data class Update(val rotate: Float, val scale: Float) : ModelEvent()
    }

    val modelEvents: MutableSharedFlow<ModelEvent> =
        MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val doFrameEvents: MutableSharedFlow<Frame> =
        MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val canDrawBehavior: MutableStateFlow<Unit?> =
        MutableStateFlow(null)

    private var translation: V3 = v3Origin
    private var rotate: Float = 0f
    private var scale: Float = 1f

    private val coroutineScope: CoroutineScope =
        CoroutineScope(Dispatchers.Main)

    init {
        coroutineScope.launch {
            val filamentAsset =
                withContext(Dispatchers.IO) {
                    context.assets
                        .open("eren-hiphop-dance.glb")
                        .use { input ->
                            val bytes = ByteArray(input.available())
                            input.read(bytes)
                            filament.assetLoader.createAsset(ByteBuffer.wrap(bytes))!!
                        }
                }
                    .also { filament.resourceLoader.loadResources(it) }

            launch {
                // translation
                modelEvents
                    .mapNotNull { modelEvent ->
                        (modelEvent as? ModelEvent.Move)
                            ?.let {
                                arCore.frame
                                    .hitTest(
                                        filament.surfaceView.width.toFloat() * modelEvent.screenPosition.x,
                                        filament.surfaceView.height.toFloat() * modelEvent.screenPosition.y,
                                    )
                                    .maxByOrNull { it.trackable is Point }
                            }
                            ?.let { V3(it.hitPose.translation) }
                    }
                    .collect {
                        canDrawBehavior.tryEmit(Unit)
                        translation = it
                    }
            }

            launch {
                // rotation and scale
                modelEvents.collect { modelEvent ->
                    when (modelEvent) {
                        is ModelEvent.Update ->
                            Pair((rotate + modelEvent.rotate).clampToTau, scale * modelEvent.scale)
                        else ->
                            Pair(rotate, scale)
                    }
                        .let { (r, s) ->
                            rotate = r
                            scale = s
                        }
                }
            }

            launch {
                canDrawBehavior.filterNotNull().first()

                doFrameEvents.collect { frame ->
                    // update animator
                    val animator = filamentAsset.instance.animator

                    if (animator.animationCount > 0) {
                        animator.applyAnimation(
                            0,
                            (frame.timestamp /
                                    TimeUnit.SECONDS.toNanos(1).toDouble())
                                .toFloat() %
                                    animator.getAnimationDuration(0),
                        )

                        animator.updateBoneMatrices()
                    }

                    filament.scene.addEntities(filamentAsset.entities)

                    filament.engine.transformManager.setTransform(
                        filament.engine.transformManager.getInstance(filamentAsset.root),
                        m4Identity()
                            .translate(translation.x, translation.y, translation.z)
                            .rotate(rotate.toDegrees, 0f, 1f, 0f)
                            .scale(scale, scale, scale)
                            .floatArray,
                    )
                }
            }
        }
    }

    fun destroy() {
        coroutineScope.cancel()
    }

    fun doFrame(frame: Frame) {
        doFrameEvents.tryEmit(frame)
    }
}
