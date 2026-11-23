package com.exciteddev.flironepro.pipeline

import com.exciteddev.flironepro.media.VideoRecorder

/** The video recording that is running, or null. It belongs to the pipeline, so it goes on when the screen is recreated. */
val StreamPipeline.recorder: VideoRecorder? get() = processor?.recorder

/** Ends the running video recording and finishes its file in the background ([StreamPipeline.onVideoSaved]); false if there was none. */
fun StreamPipeline.stopRecording(): Boolean {
    val rec = processor?.recorder ?: return false
    processor?.recorder = null
    Thread({ onVideoSaved(rec.stop()) }, "flir-rec-stop").start()
    return true
}
