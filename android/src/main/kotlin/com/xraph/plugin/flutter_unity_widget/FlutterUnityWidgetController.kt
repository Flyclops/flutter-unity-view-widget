package com.xraph.plugin.flutter_unity_widget

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.unity3d.player.IUnityPlayerLifecycleEvents
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.platform.PlatformView


@SuppressLint("NewApi")
class FlutterUnityWidgetController(
        private val id: Int,
        private val context: Context?,
        binaryMessenger: BinaryMessenger,
        lifecycleProvider: LifecycleProvider
) :     PlatformView,
        DefaultLifecycleObserver,
        FlutterUnityWidgetOptionsSink,
        MethodCallHandler,
        UnityEventListener,
        IUnityPlayerLifecycleEvents {

    //#region Members
    private val LOG_TAG = "FlutterUnityController"
    private var lifecycleProvider: LifecycleProvider = lifecycleProvider
    private var options: FlutterUnityWidgetOptions = FlutterUnityWidgetOptions()

    private val methodChannel: MethodChannel

    private var methodChannelResult: MethodChannel.Result? = null
    private var view: FrameLayout
    private var disposed: Boolean = false
    private var attached: Boolean = false
    private var loadedCallbackPending: Boolean = false

    init {
        Log.d(LOG_TAG, "INIT")

        Log.d(LOG_TAG, "START UnityPlayerUtils.controllers.add(this)")
        UnityPlayerUtils.controllers.add(this)
        Log.d(LOG_TAG, "END UnityPlayerUtils.controllers.add(this)")

        Log.d(LOG_TAG, "START view.setBackgroundColor(Color.WHITE)")
        var tempContext = UnityPlayerUtils.activity as Context
        if (context != null) tempContext = context
        // set layout view
        view = FrameLayout(tempContext)
        view.setBackgroundColor(Color.WHITE)
        Log.d(LOG_TAG, "END view.setBackgroundColor(Color.WHITE)")

        Log.d(LOG_TAG, "START MethodChannel(binaryMessenger, 'plugin.xraph.com/unity_view_$id')")
        // setup method channel
        methodChannel = MethodChannel(binaryMessenger, "plugin.xraph.com/unity_view_$id")
        methodChannel.setMethodCallHandler(this)
        Log.d(LOG_TAG, "END MethodChannel(binaryMessenger, 'plugin.xraph.com/unity_view_$id')")

        Log.d(LOG_TAG, "START addUnityEventListener")
        // Set unity listener
        UnityPlayerUtils.addUnityEventListener(this)
        Log.d(LOG_TAG, "END addUnityEventListener")

        if(UnityPlayerUtils.unityPlayer == null) {
            Log.d(LOG_TAG, "START UnityPlayerUtils.unityPlayer == null")
            Log.d(LOG_TAG, "(1) createPlayer")
            createPlayer()
            Log.d(LOG_TAG, "(2) refocusUnity")
            refocusUnity()
            Log.d(LOG_TAG, "END UnityPlayerUtils.unityPlayer == null")
        } else if(!UnityPlayerUtils.unityLoaded) {
            Log.d(LOG_TAG, "START !UnityPlayerUtils.unityLoaded")
            Log.d(LOG_TAG, "(3) createPlayer")
            createPlayer()
            Log.d(LOG_TAG, "(4) attachToView")
            attachToView()
            Log.d(LOG_TAG, "END !UnityPlayerUtils.unityLoaded")
        } else {
            Log.d(LOG_TAG, "START (5) attachToView")
            // attach unity to controller
            attachToView()
            Log.d(LOG_TAG, "END (5) attachToView")

        }
    }

    //#endregion

    //#region Flutter Overrides
    override fun getView(): View {
        Log.d(LOG_TAG, "getView()")
//        if(UnityPlayerUtils.unityPlayer == null)
//            return UnityPlayerUtils.unityPlayer!!

        return view
    }

    override fun dispose() {
        Log.d(LOG_TAG, "START dispose() - this controller disposed")
        UnityPlayerUtils.removeUnityEventListener(this)
        if (disposed) {
            return
        }

        detachView()
        destroyUnityViewIfNecessary()

        val lifecycle = lifecycleProvider.getLifecycle()
        lifecycle.removeObserver(this)

        disposed = true
        Log.d(LOG_TAG, "END dispose()")
    }

    override fun onMethodCall(methodCall: MethodCall, result: MethodChannel.Result) {
        when (methodCall.method) {
            "unity#waitForUnity" -> {
                Log.d(LOG_TAG, "START  waitForUnity()")

                if (UnityPlayerUtils.unityPlayer != null) {
                    result.success(null)
                    return
                }
                result.success(null)
                methodChannelResult = result
                Log.d(LOG_TAG, "END  waitForUnity()")
            }
            "unity#createPlayer" -> {
                Log.d(LOG_TAG, "START  createPlayer()")

                invalidateFrameIfNeeded()
                this.createPlayer()
                refocusUnity()
                result.success(null)
                Log.d(LOG_TAG, "END  createPlayer()")

            }
            "unity#isReady" -> {
                Log.d(LOG_TAG, "START  isReady()")

                result.success(UnityPlayerUtils.unityPlayer != null)
                Log.d(LOG_TAG, "END  isReady()")
            }
            "unity#isLoaded" -> {
                Log.d(LOG_TAG, "START  isLoaded()")
                result.success(UnityPlayerUtils.unityLoaded)
                Log.d(LOG_TAG, "END  isLoaded()")
            }
            "unity#isPaused" -> {
                Log.d(LOG_TAG, "START  isPaused()")
                result.success(UnityPlayerUtils.unityPaused)
                Log.d(LOG_TAG, "END  isPaused()")
            }
            "unity#postMessage" -> {
                Log.d(LOG_TAG, "START  postMessage()")
                invalidateFrameIfNeeded()
                val gameObject: String = methodCall.argument<String>("gameObject").toString()
                val methodName: String = methodCall.argument<String>("methodName").toString()
                val message: String = methodCall.argument<String>("message").toString()
                UnityPlayerUtils.postMessage(gameObject, methodName, message)
                result.success(true)
                Log.d(LOG_TAG, "END  postMessage()")
            }
            "unity#pausePlayer" -> {
                Log.d(LOG_TAG, "START  pausePlayer()")
                invalidateFrameIfNeeded()
                UnityPlayerUtils.pause()
                result.success(true)
                Log.d(LOG_TAG, "END  pausePlayer()")
            }
            "unity#openInNativeProcess" -> {
                Log.d(LOG_TAG, "START  openInNativeProcess()")
                openNativeUnity()
                result.success(true)
                Log.d(LOG_TAG, "END  openInNativeProcess()")
            }
            "unity#resumePlayer" -> {
                Log.d(LOG_TAG, "START  resumePlayer()")
                invalidateFrameIfNeeded()
                UnityPlayerUtils.resume()
                result.success(true)
                Log.d(LOG_TAG, "END  resumePlayer()")
            }
            "unity#unloadPlayer" -> {
                Log.d(LOG_TAG, "START  unloadPlayer()")
                invalidateFrameIfNeeded()
                UnityPlayerUtils.unload()
                result.success(true)
                Log.d(LOG_TAG, "END  unloadPlayer()")

            }
            "unity#dispose" -> {
                Log.d(LOG_TAG, "START  dispose()")
                // destroyUnityViewIfNecessary()
                // if ()
                // dispose()
                result.success(null)
                Log.d(LOG_TAG, "END  dispose()")

            }
            "unity#silentQuitPlayer" -> {
                Log.d(LOG_TAG, "START  silentQuitPlayer()")
                UnityPlayerUtils.quitPlayer()
                result.success(true)
                Log.d(LOG_TAG, "END  silentQuitPlayer()")
            }
            "unity#quitPlayer" -> {
                Log.d(LOG_TAG, "START  silentQuitPlayer()")
                if (UnityPlayerUtils.unityPlayer != null) {
                    UnityPlayerUtils.unityPlayer!!.destroy()
                }
                result.success(true)
                Log.d(LOG_TAG, "END  quitPlayer()")
            }
            else -> {
                Log.d(LOG_TAG, "ERROR onMethodCall result.notImplemented()")

                result.notImplemented()
            }
        }
    }
    //#endregion

    //#region Options Override
    override fun setFullscreenEnabled(fullscreenEnabled: Boolean) {
        options.fullscreenEnabled = fullscreenEnabled
    }

    override fun setHideStatusBar(hideStatusBar: Boolean) {
        options.hideStatus = hideStatusBar
    }

    override fun setRunImmediately(runImmediately: Boolean) {
        options.runImmediately = runImmediately
    }

    override fun setUnloadOnDispose(unloadOnDispose: Boolean) {
        options.unloadOnDispose = unloadOnDispose
    }
    //#endregion

    //#region Unity Events
    override fun onMessage(message: String) {
        Log.d(LOG_TAG, "onMessage")

        Handler(Looper.getMainLooper()).post {
            Log.d(LOG_TAG, "onMessage methodChannel.invokeMethod('events#onUnityMessage', message)")

            methodChannel.invokeMethod("events#onUnityMessage", message)
        }
    }

    override fun onSceneLoaded(name: String, buildIndex: Int, isLoaded: Boolean, isValid: Boolean) {
        Log.d(LOG_TAG, "onSceneLoaded")

        Handler(Looper.getMainLooper()).post {
            Log.d(LOG_TAG, "onSceneLoaded methodChannel.invokeMethod('events#onUnitySceneLoaded', payload)")

            val payload: MutableMap<String, Any> = HashMap()
            payload["name"] = name
            payload["buildIndex"] = buildIndex
            payload["isLoaded"] = isLoaded
            payload["isValid"] = isValid
            methodChannel.invokeMethod("events#onUnitySceneLoaded", payload)
        }
    }

    override fun onUnityPlayerUnloaded() {
        Log.d(LOG_TAG, "onUnityPlayerUnloaded")

        UnityPlayerUtils.unityLoaded = false
        Handler(Looper.getMainLooper()).post {
            Log.d(LOG_TAG, "onUnityPlayerUnloaded methodChannel.invokeMethod('events#onUnityUnloaded', true)")
            
            methodChannel.invokeMethod("events#onUnityUnloaded", true)
        }
    }

    override fun onUnityPlayerQuitted() {
        if (disposed) return
    }

    //#endregion

    //#region Lifecycle Overrides
    override fun onCreate(owner: LifecycleOwner) {
        Log.d(LOG_TAG, "START onCreate")
        owner.lifecycle.addObserver(this)
        Log.d(LOG_TAG, "END onCreate")

    }

    override fun onResume(owner: LifecycleOwner) {
        Log.d(LOG_TAG, "START onResume")
        reattachToView()
        if(UnityPlayerUtils.viewStaggered && UnityPlayerUtils.unityLoaded) {
            Log.d(LOG_TAG, "(1) this.createPlayer()")
            this.createPlayer()
            Log.d(LOG_TAG, "(2) refocusUnity")
            refocusUnity()
            Log.d(LOG_TAG, "(3) UnityPlayerUtils.viewStaggered = false")
            UnityPlayerUtils.viewStaggered = false
        }
        Log.d(LOG_TAG, "END onResume")

    }

    override fun onPause(owner: LifecycleOwner) {
        Log.d(LOG_TAG, "START onPause")
        UnityPlayerUtils.viewStaggered = true
        UnityPlayerUtils.pause()
        Log.d(LOG_TAG, "END onPause")

    }

    override fun onDestroy(owner: LifecycleOwner) {
        Log.d(LOG_TAG, "onDestroy")
        if (disposed) {
            return
        }

        Log.d(LOG_TAG, "onDestroy - owner.lifecycle.removeObserver(this)")
        owner.lifecycle.removeObserver(this)
    }

    //#endregion

    //#region Member Methods
    fun bootstrap() {
        Log.d(LOG_TAG, "START bootstrap")
        this.lifecycleProvider.getLifecycle().addObserver(this)
        Log.d(LOG_TAG, "END bootstrap")

    }

    private fun openNativeUnity() {
        Log.d(LOG_TAG, "START openNativeUnity")

        val activity = getActivity(null)
        if (activity != null) {
            Log.d(LOG_TAG, "START openNativeUnity - activity.startActivityForResult(intent, 1)")

            val intent = Intent(getActivity(null)!!.applicationContext, OverrideUnityActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            intent.putExtra("fullscreen", options.fullscreenEnabled)
            intent.putExtra("flutterActivity", activity.javaClass)
            activity.startActivityForResult(intent, 1)
        }
        Log.d(LOG_TAG, "END openNativeUnity")

    }

    private fun destroyUnityViewIfNecessary() {
        Log.d(LOG_TAG, "START destroyUnityViewIfNecessary")

        if (options.unloadOnDispose) {
            UnityPlayerUtils.unload()
        }
        Log.d(LOG_TAG, "END destroyUnityViewIfNecessary")

    }

    private fun createPlayer() {
        try {
            Log.d(LOG_TAG, "createPlayer")

            if (UnityPlayerUtils.activity != null) {
                Log.d(LOG_TAG, "createPlayer - UnityPlayerUtils.activity != null")

                UnityPlayerUtils.createUnityPlayer( this, object : OnCreateUnityViewCallback {
                    override fun onReady() {
                        Log.d(LOG_TAG, "START UnityPlayerUtils.createUnityPlayer - onReady")

                        // attach unity to controller
                        attachToView()

                        if (methodChannelResult != null) {
                            methodChannelResult!!.success(true)
                            methodChannelResult = null
                        }
                        Log.d(LOG_TAG, "END UnityPlayerUtils.createUnityPlayer - onReady")
                    }
                })
            }
        } catch (e: Exception) {
            Log.d(LOG_TAG, "ERROR createPlayer")

            if (methodChannelResult != null) {
                methodChannelResult!!.error("FLUTTER_UNITY_WIDGET", e.message, e)
                methodChannelResult!!.success(false)
                methodChannelResult = null
            }
        }
    }

    private fun getActivity(context: Context?): Activity? {
        Log.d(LOG_TAG, "START getActivity")

        if (UnityPlayerUtils.activity != null) {
            return UnityPlayerUtils.activity
        }

        if (context == null) {
            return UnityPlayerUtils.activity
        } else if (context is ContextWrapper) {
            return if (context is Activity) {
                context
            } else {
                getActivity(context.baseContext)
            }
        }
        Log.d(LOG_TAG, "END getActivity")

        return UnityPlayerUtils.activity
    }

    private fun detachView() {
        Log.d(LOG_TAG, "START detachView")

        UnityPlayerUtils.controllers.remove(this)
        methodChannel.setMethodCallHandler(null)
        UnityPlayerUtils.removePlayer(this)
        Log.d(LOG_TAG, "END detachView")

    }


    private fun attachToView() {
        if (UnityPlayerUtils.unityPlayer == null) return
        Log.d(LOG_TAG, "START detachView - Attaching unity to view")

        if (UnityPlayerUtils.unityPlayer!!.parent != null) {
            (UnityPlayerUtils.unityPlayer!!.parent as ViewGroup).removeView(UnityPlayerUtils.unityPlayer)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            UnityPlayerUtils.unityPlayer!!.z = -1f
        }

        // add unity to view
        UnityPlayerUtils.addUnityViewToGroup(view)
        UnityPlayerUtils.focus()
        attached = true
        Log.d(LOG_TAG, "END detachView")

    }

    // DO NOT CHANGE THIS FUNCTION
    private fun refocusUnity() {
        Log.d(LOG_TAG, "START refocusUnity")

        UnityPlayerUtils.resume()
        UnityPlayerUtils.pause()
        UnityPlayerUtils.resume()

        Log.d(LOG_TAG, "END refocusUnity")

    }

    fun reattachToView() {
        Log.d(LOG_TAG, "START reattachToView")

        if (UnityPlayerUtils.unityPlayer!!.parent != view) {
            this.attachToView()
            Handler(Looper.getMainLooper()).post {
                methodChannel.invokeMethod("events#onViewReattached", null)
            }
        }
        view.requestLayout()
        Log.d(LOG_TAG, "END reattachToView")

    }

    /// Reference solution to Google Maps implementation
    /// https://github.com/flutter/plugins/blob/b0bfab678f83bebd49e9f9d0a83fe9b40774e853/packages/google_maps_flutter/google_maps_flutter/android/src/main/java/io/flutter/plugins/googlemaps/GoogleMapController.java#L154
    private fun invalidateFrameIfNeeded() {
        if (UnityPlayerUtils.unityPlayer == null || loadedCallbackPending) {
            return
        }
        Log.d(LOG_TAG, "START invalidateFrameIfNeeded")

        loadedCallbackPending = false
        postFrameCallback {
            postFrameCallback {
                view.invalidate()
            }
        }
        Log.d(LOG_TAG, "END invalidateFrameIfNeeded")

    }

    private fun postFrameCallback(f: Runnable) {
        Log.d(LOG_TAG, "postFrameCallback")

        Choreographer.getInstance()
                .postFrameCallback { f.run() }
    }
    //#endregion
}