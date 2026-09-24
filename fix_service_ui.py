import sys

with open("app/src/main/java/com/omymaxz/download/FloatingBubbleService.kt", "r") as f:
    content = f.read()

# Make FloatingBubbleService act as the single source of truth for floating behavior, expanding to show detection info.
old_imports = """import android.widget.ImageView
import kotlin.math.abs"""

new_imports = """import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Button
import android.os.Handler
import android.os.Looper
import kotlin.math.abs"""

content = content.replace(old_imports, new_imports)


old_class_body = """    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var videoUrl: String? = null
    private var videoTitle: String? = null
    private var currentPosition: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            videoUrl = intent.getStringExtra(CustomPlayerActivity.EXTRA_VIDEO_URL)
            videoTitle = intent.getStringExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE)
            currentPosition = intent.getLongExtra("current_position", 0L)

            // Only create the bubble view if it doesn't already exist and we have a valid media payload
            if (bubbleView == null && videoUrl != null) {
                createBubbleView()
            }
        }
        return START_NOT_STICKY
    }"""

new_class_body = """    private lateinit var windowManager: WindowManager
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
        val txtStatus = bubbleView?.findViewById<TextView>(R.id.txt_detector_status)
        val txtDetails = bubbleView?.findViewById<TextView>(R.id.txt_detector_details)
        val btnOpenPlayer = bubbleView?.findViewById<Button>(R.id.btn_open_player)

        // This requires MainActivity to expose the mediaEngine instance, but since a Service doesn't have a direct reference to the Activity instance easily without binding, we should fetch it via a static registry or singleton engine if possible.
        // For now, let's fetch it if we can access the MediaDetectionEngine through a singleton, or we can just send broadasts.
        // Since MediaDetectionEngine is currently instantiated in MainActivity, we can add a static accessor.
    }"""

content = content.replace(old_class_body, new_class_body)


old_create = """            val closeButton = bubbleView!!.findViewById<ImageView>(R.id.btn_close_bubble)
            val bubbleIcon = bubbleView!!.findViewById<ImageView>(R.id.img_bubble_icon)

            closeButton.setOnClickListener {
                stopSelf()
            }

            bubbleIcon.setOnTouchListener(object : View.OnTouchListener {
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
                            if (!moved && videoUrl != null) {
                                val launchIntent = Intent(this@FloatingBubbleService, CustomPlayerActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    putExtra(CustomPlayerActivity.EXTRA_VIDEO_URL, videoUrl)
                                    putExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE, videoTitle)
                                }
                                startActivity(launchIntent)
                                stopSelf()
                            }
                            return true
                        }"""

new_create = """            val closeButton = bubbleView!!.findViewById<ImageView>(R.id.btn_close_bubble)
            val bubbleContainer = bubbleView!!.findViewById<LinearLayout>(R.id.bubble_container)
            val expandedDetails = bubbleView!!.findViewById<LinearLayout>(R.id.expanded_details)

            closeButton.setOnClickListener {
                stopSelf()
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
                        }"""

content = content.replace(old_create, new_create)


old_destroy = """    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let {
            try {
                windowManager.removeView(it)
                android.util.Log.d("FLOATING_DEBUG", "OVERLAY_REMOVED")
            } catch (e: Exception) {
                android.util.Log.e("FLOATING_DEBUG", "Error removing overlay view: ${e.message}")
            }
        }
        bubbleView = null
    }"""

new_destroy = """    override fun onDestroy() {
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
    }"""

content = content.replace(old_destroy, new_destroy)

with open("app/src/main/java/com/omymaxz/download/FloatingBubbleService.kt", "w") as f:
    f.write(content)

print("Done")
