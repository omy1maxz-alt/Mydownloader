package com.omymaxz.download

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Button
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var videoUrl: String? = null
    private var videoTitle: String? = null
    private var currentPosition: Long = 0L
    private var isExpanded = false

    // For WebMedia Detector state updates
    private var isDetectorMode = false
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    // Global reference for MainActivity to talk to this service
    companion object {
        var instance: FloatingBubbleService? = null
        const val ACTION_START_DETECTOR = "com.omymaxz.download.action.START_DETECTOR"
        const val ACTION_START_CUSTOM_PLAYER = "com.omymaxz.download.action.START_CUSTOM_PLAYER"
        const val EXTRA_IS_DETECTOR = "extra_is_detector"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val isDet = intent.getBooleanExtra(EXTRA_IS_DETECTOR, false)
            if (isDet) {
                isDetectorMode = true
                if (bubbleView == null) {
                    createBubbleView()
                }
                startDetectorUpdates()
            } else {
                isDetectorMode = false
                videoUrl = intent.getStringExtra(CustomPlayerActivity.EXTRA_VIDEO_URL)
                videoTitle = intent.getStringExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE)
                currentPosition = intent.getLongExtra("current_position", 0L)

                if (bubbleView == null && videoUrl != null) {
                    createBubbleView()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startDetectorUpdates() {
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = object : Runnable {
            override fun run() {
                updateDetectorState()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(updateRunnable!!)
    }

    fun updateDetectorState() {
        if (!isDetectorMode || bubbleView == null) return

        val txtBadge = bubbleView?.findViewById<TextView>(R.id.txt_bubble_badge)
        val txtEmptyInfo = bubbleView?.findViewById<TextView>(R.id.txt_empty_info)
        val listMediaItems = bubbleView?.findViewById<LinearLayout>(R.id.list_media_items)

        val engine = MediaDetectionEngine.instance ?: return

        val prefs = getSharedPreferences("Settings", android.content.Context.MODE_PRIVATE)
        val minDuration = prefs.getInt("FLOATING_MIN_DURATION", 60)

        // Filter out candidates that are explicitly ads or strongly appear to be unwanted short previews
        // We do NOT modify the engine's internal list, we just filter what we show here.
        val candidates = engine.getUniquePresentations().filter {
            !it.isExplicitAd && !(it.durationSec > 0 && it.durationSec < minDuration && !it.isActivePlayer)
        }

        txtBadge?.text = "[ ${candidates.size} ]"

        if (candidates.isEmpty()) {
            txtEmptyInfo?.visibility = View.VISIBLE
            listMediaItems?.removeAllViews()
            return
        } else {
            txtEmptyInfo?.visibility = View.GONE
        }

        // Rebuild list
        listMediaItems?.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (candidate in candidates.sortedByDescending { it.finalScore }) {
            val itemView = inflater.inflate(R.layout.item_floating_media, listMediaItems, false)

            val txtUrl = itemView.findViewById<TextView>(R.id.txt_url)
            val txtSize = itemView.findViewById<TextView>(R.id.txt_size)
            val txtParts = itemView.findViewById<TextView>(R.id.txt_parts)
            val txtResolution = itemView.findViewById<TextView>(R.id.txt_resolution)
            val txtDuration = itemView.findViewById<TextView>(R.id.txt_duration)
            val btnAction = itemView.findViewById<Button>(R.id.btn_action)

            txtUrl.text = candidate.url

            val sizeStr = candidate.estimatedSize?.let {
                val mb = it / (1024.0 * 1024.0)
                "~%.1fMB".format(mb)
            } ?: "Unknown"
            txtSize.text = "Size: $sizeStr"

            val partsStr = candidate.segmentCount?.let { "$it parts" } ?: if (candidate.isManifest) "Multiple parts" else "1 part"
            txtParts.text = "Parts: $partsStr"

            val resStr = candidate.resolution ?: "Unknown"
            txtResolution.text = "Resolution: $resStr"

            val durStr = if (candidate.durationSec > 0) {
                val hours = candidate.durationSec / 3600
                val mins = (candidate.durationSec % 3600) / 60
                val secs = candidate.durationSec % 60
                if (hours > 0) "${hours}h${mins}m${secs}s" else "${mins}m${secs}s"
            } else "Unknown"
            txtDuration.text = "Duration: $durStr"

            btnAction.setOnClickListener {
                val launchIntent = Intent(this@FloatingBubbleService, CustomPlayerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(CustomPlayerActivity.EXTRA_VIDEO_URL, candidate.url)
                    putExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE, "Detected Media")
                }
                startActivity(launchIntent)
            }

            listMediaItems?.addView(itemView)
        }
    }

    private fun createBubbleView() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            android.util.Log.e("FLOATING_DEBUG", "Permission denied for SYSTEM_ALERT_WINDOW.")
            stopSelf()
            return
        }

        try {
            bubbleView = LayoutInflater.from(this).inflate(R.layout.layout_floating_bubble, null)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 100

            windowManager.addView(bubbleView, params)
            android.util.Log.d("FLOATING_DEBUG", "OVERLAY_VISIBLE: Bubble successfully attached to WindowManager.")

            val closeButton = bubbleView!!.findViewById<ImageView>(R.id.btn_close_bubble)
            val bubbleContainer = bubbleView!!.findViewById<LinearLayout>(R.id.bubble_container)
            val expandedDetails = bubbleView!!.findViewById<LinearLayout>(R.id.expanded_details)

            closeButton.setOnClickListener {
                stopSelf()
            }

            val btnClearAll = bubbleView!!.findViewById<Button>(R.id.btn_clear_all)
            val btnCloseExpanded = bubbleView!!.findViewById<Button>(R.id.btn_close_expanded)

            btnClearAll?.setOnClickListener {
                MediaDetectionEngine.instance?.clearCandidates()
                updateDetectorState()
            }

            btnCloseExpanded?.setOnClickListener {
                isExpanded = false
                expandedDetails.visibility = View.GONE
            }

            bubbleContainer.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var moved = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            moved = false
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!moved) {
                                if (isDetectorMode) {
                                    isExpanded = !isExpanded
                                    expandedDetails.visibility = if (isExpanded) View.VISIBLE else View.GONE
                                    if (isExpanded) updateDetectorState()
                                } else if (videoUrl != null) {
                                    val launchIntent = Intent(this@FloatingBubbleService, CustomPlayerActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                        putExtra(CustomPlayerActivity.EXTRA_VIDEO_URL, videoUrl)
                                        putExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE, videoTitle)
                                    }
                                    startActivity(launchIntent)
                                    stopSelf()
                                }
                            }
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()

                            if (abs(dx) > 10 || abs(dy) > 10) {
                                moved = true
                            }

                            params.x = initialX + dx
                            params.y = initialY + dy
                            windowManager.updateViewLayout(bubbleView, params)
                            return true
                        }
                    }
                    return false
                }
            })
        } catch (e: Exception) {
            android.util.Log.e("FLOATING_DEBUG", "Failed to add window: ${e.message}", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        updateRunnable?.let { handler.removeCallbacks(it) }
        bubbleView?.let {
            try {
                windowManager.removeView(it)
                android.util.Log.d("FLOATING_DEBUG", "OVERLAY_REMOVED")
            } catch (e: Exception) {
                android.util.Log.e("FLOATING_DEBUG", "Error removing overlay view: ${e.message}")
            }
        }
        bubbleView = null
    }
}
