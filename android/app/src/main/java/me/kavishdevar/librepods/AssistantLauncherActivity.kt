/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2026 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import java.util.UUID

class AssistantLauncherActivity : ComponentActivity() {
    private enum class State { WAITING, ACCEPTED, YIELDED }

    // Retained across configuration changes without retaining the activity or its window.
    private class Launch {
        val id = UUID.randomUUID().toString()
        var state = State.WAITING
        var attached = false
        var attempts = 0
        var deadline = SystemClock.uptimeMillis() + START_TIMEOUT_MS
        var nextAttemptAt = 0L
    }

    private var launch: Launch? = null
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var drawn = false
    private var content: View? = null

    private val update = Runnable { updateLaunch() }
    private val timeout = Runnable {
        when (launch?.state) {
            State.WAITING -> fail(
                if (launch?.attempts == 0) "Helper did not become foreground"
                else "Assistant request rejected"
            )
            State.ACCEPTED -> {
                Log.w(TAG, "Request accepted, but no handoff observed")
                finish()
            }
            else -> Unit
        }
    }
    private val drawListener = ViewTreeObserver.OnDrawListener {
        if (!drawn) {
            drawn = true
            // The posted update runs after this draw has completed.
            scheduleUpdate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = currentLaunch?.takeIf {
            it.id == intent.getStringExtra(EXTRA_REQUEST_ID) && !it.attached
        }
        if (request == null) {
            // Expired starts and process-death restoration must not open another session.
            Log.d(TAG, "Discarding stale helper")
            finish()
            return
        }
        launch = request
        request.attached = true
        mainHandler.removeCallbacks(startTimeout)
        Log.d(TAG, "Helper created: ${request.state}")

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setFinishOnTouchOutside(request.state == State.WAITING)
        onBackPressedDispatcher.addCallback(this) { complete("Cancelled") }
        content = View(this).apply {
            // Keep a nonzero window for foreground readiness without drawing visible content.
            minimumWidth = 1
            minimumHeight = 1
            viewTreeObserver.addOnDrawListener(drawListener)
        }
        setContentView(content)
        scheduleTimeout()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        scheduleUpdate()
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(update)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            handler.removeCallbacks(update)
            if (launch?.state == State.ACCEPTED && !isChangingConfigurations) {
                launch?.state = State.YIELDED
                handler.removeCallbacks(timeout)
                Log.d(TAG, "Helper yielded focus after accepted request")
            }
        } else {
            scheduleUpdate()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing) {
            complete("Helper stopped: ${launch?.state}")
        }
    }

    private fun scheduleUpdate() {
        val request = launch ?: return
        handler.removeCallbacks(update)
        handler.postAtTime(update, request.nextAttemptAt)
    }

    private fun updateLaunch() {
        if (drawn) removeDrawListener()
        if (isFinishing || isDestroyed || !resumed || !hasWindowFocus() || !drawn) return
        val request = launch ?: return
        when (request.state) {
            // An overlay may leave us resumed throughout the conversation.
            State.YIELDED -> return complete("Focus returned after accepted request")
            State.ACCEPTED -> return
            State.WAITING -> Unit
        }
        if (SystemClock.uptimeMillis() >= request.deadline) {
            timeout.run()
            return
        }
        if (request.attempts == 0) {
            Log.d(TAG, "Helper ready: resumed, focused and drawn")
            request.deadline = SystemClock.uptimeMillis() + RETRY_WINDOW_MS
            scheduleTimeout()
        }
        request.attempts++
        val accepted = try {
            showAssist(Bundle())
        } catch (e: Exception) {
            fail("Assistant request failed", e)
            return
        }
        Log.d(TAG, "showAssist attempt ${request.attempts}: $accepted")
        if (accepted) {
            request.state = State.ACCEPTED
            // Keep the caller alive while Android asynchronously shows the assistant.
            setFinishOnTouchOutside(false)
            request.deadline = SystemClock.uptimeMillis() + HANDOFF_TIMEOUT_MS
            scheduleTimeout()
        } else if (request.attempts < MAX_ATTEMPTS) {
            request.nextAttemptAt = SystemClock.uptimeMillis() + RETRY_DELAY_MS
            scheduleUpdate()
        } else {
            fail("Assistant request rejected")
        }
    }

    private fun scheduleTimeout() {
        handler.removeCallbacks(timeout)
        launch?.takeIf { it.state == State.WAITING || it.state == State.ACCEPTED }?.let {
            handler.postAtTime(timeout, it.deadline)
        }
    }

    private fun fail(reason: String, cause: Exception? = null) {
        Log.w(TAG, reason, cause)
        Toast.makeText(this, R.string.assistant_launch_failed, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun complete(reason: String) {
        Log.d(TAG, "Closing helper: $reason")
        finish()
    }

    override fun finish() {
        cancelCallbacks()
        releaseLaunch()
        // Never remove the task: an assistant activity could now share it.
        super.finish()
    }

    override fun onDestroy() {
        cancelCallbacks()
        launch?.attached = false
        if (!isChangingConfigurations) releaseLaunch()
        super.onDestroy()
    }

    private fun cancelCallbacks() {
        handler.removeCallbacksAndMessages(null)
        removeDrawListener()
    }

    private fun removeDrawListener() {
        content?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnDrawListener(drawListener)
    }

    private fun releaseLaunch() {
        if (currentLaunch === launch) currentLaunch = null
        launch = null
    }

    companion object {
        private const val TAG = "AssistantLauncher"
        private const val EXTRA_REQUEST_ID = "assistant_request_id"
        private const val DEBOUNCE_MS = 1_000L
        private const val START_TIMEOUT_MS = 5_000L
        private const val HANDOFF_TIMEOUT_MS = 10_000L
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 250L
        private const val RETRY_WINDOW_MS = 1_000L

        // All launch state is confined to the main thread, including stem events from IO.
        private val mainHandler = Handler(Looper.getMainLooper())
        private var currentLaunch: Launch? = null
        private var nextLaunchAt = 0L
        private val startTimeout = Runnable {
            Log.w(TAG, "Helper was not created; activity start may have been blocked")
            currentLaunch = null
        }

        private fun voiceCommandIntent(context: Context): Intent? {
            val packageManager = context.packageManager
            val assistant = packageManager.queryIntentServices(
                Intent(VoiceInteractionService.SERVICE_INTERFACE), 0
            ).mapNotNull { it.serviceInfo }.firstOrNull {
                VoiceInteractionService.isActiveService(
                    context, ComponentName(it.packageName, it.name)
                )
            } ?: return null

            // Some assistants only start listening through their voice-command entry point.
            val intent = Intent(Intent.ACTION_VOICE_COMMAND).setPackage(assistant.packageName)
            val activity = packageManager.resolveActivity(
                intent, PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo ?: return null
            if (!activity.exported || activity.packageName != assistant.packageName) return null
            if (activity.permission != null &&
                context.checkSelfPermission(activity.permission) != PackageManager.PERMISSION_GRANTED
            ) return null

            return intent.setClassName(activity.packageName, activity.name)
        }

        fun launch(context: Context) {
            val application = context.applicationContext
            mainHandler.post {
                val now = SystemClock.uptimeMillis()
                if (currentLaunch != null || now < nextLaunchAt) {
                    Log.d(TAG, "Coalescing assistant launch")
                    return@post
                }
                // Also absorb bursts when no helper remains in flight.
                nextLaunchAt = now + DEBOUNCE_MS
                try {
                    val voiceCommand = voiceCommandIntent(application)
                    if (voiceCommand != null) {
                        Log.d(TAG, "Starting selected assistant's voice command")
                        application.startActivity(voiceCommand.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return@post
                    }
                    val request = Launch()
                    currentLaunch = request
                    mainHandler.postAtTime(startTimeout, request.deadline)
                    Log.d(TAG, "Starting helper")
                    application.startActivity(
                        Intent(application, AssistantLauncherActivity::class.java).apply {
                            // Isolate each request without reusing or clearing an assistant's task.
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                            putExtra(EXTRA_REQUEST_ID, request.id)
                        }
                    )
                } catch (e: Exception) {
                    mainHandler.removeCallbacks(startTimeout)
                    currentLaunch = null
                    Log.w(TAG, "Could not launch assistant", e)
                    Toast.makeText(
                        application, R.string.assistant_launch_failed, Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
