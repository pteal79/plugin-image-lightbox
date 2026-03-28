package com.nativephp.plugins.imagelightbox

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.*
import android.widget.*
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeError
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.min

// ---------------------------------------------------------------------------
// Bridge function entry point
// ---------------------------------------------------------------------------

object ImageLightboxFunctions {

    class Show(private val activity: FragmentActivity) : BridgeFunction {

        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val remoteURL  = (parameters["url"]    as? String)?.takeIf { it.isNotEmpty() }
            val localPath  = (parameters["local"]  as? String)?.takeIf { it.isNotEmpty() }
            val imageId    = parameters["imageId"] as? String
            val showEdit   = parameters["edit"]    as? Boolean ?: false
            val showMarkup = parameters["markup"]  as? Boolean ?: false
            val showShare  = parameters["share"]   as? Boolean ?: false
            val showDelete = parameters["delete"]  as? Boolean ?: false

            if (remoteURL == null && localPath == null) {
                throw BridgeError.InvalidParameters("Either 'url' or 'local' is required")
            }

            activity.runOnUiThread {
                val fragment = ImageLightboxFragment.newInstance(
                    remoteURL  = remoteURL,
                    localPath  = localPath,
                    imageId    = imageId,
                    showEdit   = showEdit,
                    showMarkup = showMarkup,
                    showShare  = showShare,
                    showDelete = showDelete
                )
                fragment.show(activity.supportFragmentManager, "ImageLightbox")
            }

            return BridgeResponse.success(mapOf("presented" to true))
        }
    }
}

// ---------------------------------------------------------------------------
// Dialog Fragment
// ---------------------------------------------------------------------------

class ImageLightboxFragment : DialogFragment() {

    companion object {
        private const val ARG_REMOTE_URL  = "remoteUrl"
        private const val ARG_LOCAL_PATH  = "localPath"
        private const val ARG_IMAGE_ID    = "imageId"
        private const val ARG_SHOW_EDIT   = "showEdit"
        private const val ARG_SHOW_MARKUP = "showMarkup"
        private const val ARG_SHOW_SHARE  = "showShare"
        private const val ARG_SHOW_DELETE = "showDelete"

        fun newInstance(
            remoteURL: String?,
            localPath: String?,
            imageId: String?,
            showEdit: Boolean,
            showMarkup: Boolean,
            showShare: Boolean,
            showDelete: Boolean
        ): ImageLightboxFragment = ImageLightboxFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_REMOTE_URL, remoteURL)
                putString(ARG_LOCAL_PATH, localPath)
                putString(ARG_IMAGE_ID, imageId)
                putBoolean(ARG_SHOW_EDIT, showEdit)
                putBoolean(ARG_SHOW_MARKUP, showMarkup)
                putBoolean(ARG_SHOW_SHARE, showShare)
                putBoolean(ARG_SHOW_DELETE, showDelete)
            }
        }
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private var remoteURL: String? = null
    private var localPath: String? = null
    private var imageId: String? = null
    private var showEdit = false
    private var showMarkup = false
    private var showShare = false
    private var showDelete = false

    private lateinit var zoomableImageView: ZoomableImageView
    private lateinit var progressBar: ProgressBar

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Cached local file for remote images so share works without re-downloading. */
    private var cachedFile: File? = null

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        arguments?.let {
            remoteURL  = it.getString(ARG_REMOTE_URL)
            localPath  = it.getString(ARG_LOCAL_PATH)
            imageId    = it.getString(ARG_IMAGE_ID)
            showEdit   = it.getBoolean(ARG_SHOW_EDIT, false)
            showMarkup = it.getBoolean(ARG_SHOW_MARKUP, false)
            showShare  = it.getBoolean(ARG_SHOW_SHARE, false)
            showDelete = it.getBoolean(ARG_SHOW_DELETE, false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()

        // Root: vertical LinearLayout so the image starts below the toolbar (matches iOS)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
        }

        // Top toolbar
        val toolbarView = buildToolbar(ctx)
        root.addView(toolbarView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(56)
        ))

        // Image container occupies all remaining space
        val imageContainer = FrameLayout(ctx)
        root.addView(imageContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f
        ))

        // Zoomable image
        zoomableImageView = ZoomableImageView(ctx)
        imageContainer.addView(zoomableImageView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Progress bar (centred)
        progressBar = ProgressBar(ctx).apply { isIndeterminate = true }
        imageContainer.addView(progressBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        loadImage()
        return root
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    // -----------------------------------------------------------------------
    // Toolbar builder
    // -----------------------------------------------------------------------

    private enum class Icon { PENCIL, MARKUP, SHARE, TRASH, CLOSE }

    private fun buildToolbar(ctx: Context): LinearLayout {
        val toolbar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.TRANSPARENT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        // Action buttons on the left — matches iOS order: edit, markup, share, delete
        if (showEdit)   toolbar.addView(iconButton(ctx, Icon.PENCIL) { onEditPressed() })
        if (showMarkup) toolbar.addView(iconButton(ctx, Icon.MARKUP) { onMarkupPressed() })
        if (showShare)  toolbar.addView(iconButton(ctx, Icon.SHARE)  { onSharePressed() })
        if (showDelete) toolbar.addView(iconButton(ctx, Icon.TRASH)  { onDeletePressed() })

        // Flexible spacer
        toolbar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))

        // Close on the right
        toolbar.addView(iconButton(ctx, Icon.CLOSE) { onClosePressed() })

        return toolbar
    }

    /** 36dp circular button with a white Canvas-drawn icon — matches iOS button style. */
    private fun iconButton(ctx: Context, icon: Icon, onClick: () -> Unit): ImageView {
        val size    = dp(36)
        val padding = dp(8)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x8C000000.toInt()) // ~55% opacity black, matches iOS
        }
        return ImageView(ctx).apply {
            setImageBitmap(drawIcon(icon, size - padding * 2))
            background = bg
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(padding, padding, padding, padding)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(size, size).also {
                it.setMargins(dp(4), 0, dp(4), 0)
            }
        }
    }

    /**
     * Draws a white line-art icon on a transparent Bitmap.
     * Mirrors the iOS SF Symbols used in the iOS version:
     *   PENCIL  → "pencil"
     *   MARKUP  → "pencil.tip.crop.circle"
     *   SHARE   → "square.and.arrow.up"
     *   TRASH   → "trash"
     *   CLOSE   → "xmark"
     */
    private fun drawIcon(icon: Icon, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c   = Canvas(bmp)
        val sw  = sizePx * 0.10f
        val p   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = Color.WHITE
            style       = Paint.Style.STROKE
            strokeWidth = sw
            strokeCap   = Paint.Cap.ROUND
            strokeJoin  = Paint.Join.ROUND
        }
        val s = sizePx.toFloat()

        when (icon) {

            // "pencil" — diagonal body with eraser cap and filled tip
            Icon.PENCIL -> {
                // Main shaft
                c.drawLine(s * 0.24f, s * 0.76f, s * 0.72f, s * 0.28f, p)
                // Eraser cap (perpendicular bar at top)
                c.drawLine(s * 0.65f, s * 0.18f, s * 0.82f, s * 0.35f, p)
                // Tip (filled triangle at bottom-left)
                val tip = Path().apply {
                    moveTo(s * 0.14f, s * 0.86f)
                    lineTo(s * 0.24f, s * 0.76f)
                    lineTo(s * 0.30f, s * 0.82f)
                    close()
                }
                p.style = Paint.Style.FILL
                c.drawPath(tip, p)
                p.style = Paint.Style.STROKE
            }

            // "pencil.tip.crop.circle" — small pencil inside a circle
            Icon.MARKUP -> {
                c.drawCircle(s * 0.50f, s * 0.50f, s * 0.42f, p)
                // Mini pencil inside
                c.drawLine(s * 0.36f, s * 0.66f, s * 0.64f, s * 0.36f, p)
                c.drawLine(s * 0.64f, s * 0.36f, s * 0.72f, s * 0.28f, p) // cap
                val tip = Path().apply {
                    moveTo(s * 0.28f, s * 0.72f)
                    lineTo(s * 0.36f, s * 0.66f)
                    lineTo(s * 0.40f, s * 0.72f)
                    close()
                }
                p.style = Paint.Style.FILL
                c.drawPath(tip, p)
                p.style = Paint.Style.STROKE
            }

            // "square.and.arrow.up" — box with arrow emerging from top
            Icon.SHARE -> {
                // Open-top box
                val box = Path().apply {
                    moveTo(s * 0.36f, s * 0.48f)
                    lineTo(s * 0.18f, s * 0.48f)
                    lineTo(s * 0.18f, s * 0.84f)
                    lineTo(s * 0.82f, s * 0.84f)
                    lineTo(s * 0.82f, s * 0.48f)
                    lineTo(s * 0.64f, s * 0.48f)
                }
                c.drawPath(box, p)
                // Arrow shaft
                c.drawLine(s * 0.50f, s * 0.60f, s * 0.50f, s * 0.16f, p)
                // Arrow head
                c.drawLine(s * 0.50f, s * 0.16f, s * 0.32f, s * 0.34f, p)
                c.drawLine(s * 0.50f, s * 0.16f, s * 0.68f, s * 0.34f, p)
            }

            // "trash" — bin with lid, handle, and three vertical lines
            Icon.TRASH -> {
                // Lid bar
                c.drawLine(s * 0.14f, s * 0.30f, s * 0.86f, s * 0.30f, p)
                // Handle on lid
                c.drawLine(s * 0.38f, s * 0.30f, s * 0.38f, s * 0.16f, p)
                c.drawLine(s * 0.62f, s * 0.30f, s * 0.62f, s * 0.16f, p)
                c.drawLine(s * 0.38f, s * 0.16f, s * 0.62f, s * 0.16f, p)
                // Body
                val body = Path().apply {
                    moveTo(s * 0.22f, s * 0.30f)
                    lineTo(s * 0.28f, s * 0.86f)
                    lineTo(s * 0.72f, s * 0.86f)
                    lineTo(s * 0.78f, s * 0.30f)
                }
                c.drawPath(body, p)
                // Three vertical lines inside body
                c.drawLine(s * 0.50f, s * 0.40f, s * 0.50f, s * 0.78f, p)
                c.drawLine(s * 0.38f, s * 0.40f, s * 0.36f, s * 0.78f, p)
                c.drawLine(s * 0.62f, s * 0.40f, s * 0.64f, s * 0.78f, p)
            }

            // "xmark" — two crossing diagonal lines
            Icon.CLOSE -> {
                c.drawLine(s * 0.22f, s * 0.22f, s * 0.78f, s * 0.78f, p)
                c.drawLine(s * 0.78f, s * 0.22f, s * 0.22f, s * 0.78f, p)
            }
        }

        return bmp
    }

    // -----------------------------------------------------------------------
    // Image loading
    // -----------------------------------------------------------------------

    private fun loadImage() {
        progressBar.visibility = View.VISIBLE

        executor.execute {
            try {
                val bitmap = when {
                    remoteURL != null -> loadRemote(remoteURL!!)
                    localPath != null -> loadLocal(localPath!!)
                    else -> null
                }
                mainHandler.post {
                    progressBar.visibility = View.GONE
                    if (bitmap != null) zoomableImageView.setImageBitmap(bitmap)
                    else showError("Unable to decode image.\n${remoteURL ?: localPath}")
                }
            } catch (e: Exception) {
                mainHandler.post {
                    progressBar.visibility = View.GONE
                    showError("Failed to load image: ${e.message}\n${remoteURL ?: localPath}")
                }
            }
        }
    }

    private fun loadRemote(urlString: String): Bitmap? {
        val url = URL(urlString)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout    = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*, */*")
            // Inject WebView session cookies so authenticated endpoints work
            val cookies = android.webkit.CookieManager.getInstance().getCookie(urlString)
            if (!cookies.isNullOrEmpty()) setRequestProperty("Cookie", cookies)
            connect()
        }
        // HttpURLConnection throws FileNotFoundException for 4xx — check first
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            connection.disconnect()
            throw Exception("Server returned HTTP $responseCode for $urlString")
        }
        val bytes = connection.inputStream.use { it.readBytes() }
        connection.disconnect()

        // Cache to disk for sharing
        val ext = urlString.substringAfterLast('.', "jpg").lowercase().take(5)
        val safeExt = if (ext in listOf("jpg", "jpeg", "png", "heic", "webp")) ext else "jpg"
        val file = File(requireContext().cacheDir, "lightbox_${System.currentTimeMillis()}.$safeExt")
        FileOutputStream(file).use { it.write(bytes) }
        cachedFile = file

        return decodeBitmap(bytes)
    }

    private fun loadLocal(path: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        return decodeBitmap(file.readBytes()).also {
            if (it != null) cachedFile = file
        }
    }

    private fun decodeBitmap(bytes: ByteArray): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val buf = java.nio.ByteBuffer.wrap(bytes)
                android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(buf)
                )
            } catch (_: Exception) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    private fun showError(message: String) {
        if (!isAdded) return
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Image Not Found")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> dismiss() }
            .setCancelable(false)
            .show()
    }

    // -----------------------------------------------------------------------
    // Button actions
    // Capture requireActivity() BEFORE dismiss() to avoid detachment crash.
    // -----------------------------------------------------------------------

    private fun onClosePressed() {
        val id       = imageId ?: ""
        val activity = requireActivity()
        dismiss()
        NativeActionCoordinator.dispatchEvent(
            activity,
            "Nativephp\\ImageLightbox\\Events\\ClosePressed",
            JSONObject(mapOf("imageId" to id)).toString()
        )
    }

    private fun onEditPressed() {
        val id       = imageId ?: ""
        val activity = requireActivity()
        dismiss()
        NativeActionCoordinator.dispatchEvent(
            activity,
            "Nativephp\\ImageLightbox\\Events\\EditPressed",
            JSONObject(mapOf("imageId" to id)).toString()
        )
    }

    private fun onMarkupPressed() {
        val id       = imageId ?: ""
        val activity = requireActivity()
        dismiss()
        NativeActionCoordinator.dispatchEvent(
            activity,
            "Nativephp\\ImageLightbox\\Events\\MarkupPressed",
            JSONObject(mapOf("imageId" to id)).toString()
        )
    }

    private fun onDeletePressed() {
        val id       = imageId ?: ""
        val activity = requireActivity()
        dismiss()
        NativeActionCoordinator.dispatchEvent(
            activity,
            "Nativephp\\ImageLightbox\\Events\\DeletePressed",
            JSONObject(mapOf("imageId" to id)).toString()
        )
    }

    private fun onSharePressed() {
        val file = cachedFile
        if (file != null && file.exists()) {
            shareFile(file)
        } else if (remoteURL != null) {
            // Remote image not yet cached — download then share
            progressBar.visibility = View.VISIBLE
            executor.execute {
                try {
                    loadRemote(remoteURL!!)
                    mainHandler.post {
                        progressBar.visibility = View.GONE
                        cachedFile?.let { shareFile(it) }
                    }
                } catch (_: Exception) {
                    mainHandler.post {
                        progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), "Failed to prepare image for sharing.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Image"))
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}

// ---------------------------------------------------------------------------
// ZoomableImageView — pinch-to-zoom + pan with Matrix
// ---------------------------------------------------------------------------

@SuppressLint("ClickableViewAccessibility")
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val transformMatrix = Matrix()
    private val matValues = FloatArray(9)

    private val minScale = 1f
    private val maxScale = 5f

    // Pan state
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    // Pinch state
    private var initialSpan = 0f
    private var initialScale = 1f
    private val initialFocus = PointF()
    private var isScaling = false

    init {
        scaleType = ScaleType.MATRIX
        setOnTouchListener { _, event -> handleTouch(event) }
    }

    // -----------------------------------------------------------------------
    // Layout & image changes
    // -----------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (drawable != null) fitImage()
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        post { fitImage() }
    }

    /** Scale image to aspect-fit the view, centred. */
    private fun fitImage() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (dw <= 0 || dh <= 0 || vw <= 0 || vh <= 0) return

        val scale = min(vw / dw, vh / dh)
        val tx = (vw - dw * scale) / 2f
        val ty = (vh - dh * scale) / 2f

        transformMatrix.reset()
        transformMatrix.postScale(scale, scale)
        transformMatrix.postTranslate(tx, ty)
        imageMatrix = transformMatrix
        invalidate()
    }

    // -----------------------------------------------------------------------
    // Touch handling
    // -----------------------------------------------------------------------

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                activePointerId = event.getPointerId(0)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    initialSpan = span(event)
                    initialScale = currentScale()
                    midPoint(initialFocus, event)
                    isScaling = true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isScaling && event.pointerCount >= 2) {
                    val newSpan = span(event)
                    if (initialSpan > 0) {
                        val desired = initialScale * (newSpan / initialSpan)
                        val clamped = desired.coerceIn(minScale, maxScale)
                        val scaleDelta = clamped / currentScale()
                        transformMatrix.postScale(scaleDelta, scaleDelta, initialFocus.x, initialFocus.y)
                        clampTranslation()
                        imageMatrix = transformMatrix
                    }
                } else {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx >= 0) {
                        val dx = event.getX(idx) - lastTouchX
                        val dy = event.getY(idx) - lastTouchY
                        transformMatrix.postTranslate(dx, dy)
                        clampTranslation()
                        imageMatrix = transformMatrix
                        lastTouchX = event.getX(idx)
                        lastTouchY = event.getY(idx)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                isScaling = false
                val upIdx = event.actionIndex
                val upId  = event.getPointerId(upIdx)
                if (upId == activePointerId) {
                    val newIdx = if (upIdx == 0) 1 else 0
                    lastTouchX = event.getX(newIdx)
                    lastTouchY = event.getY(newIdx)
                    activePointerId = event.getPointerId(newIdx)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isScaling = false
            }
        }
        return true
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun currentScale(): Float {
        transformMatrix.getValues(matValues)
        return matValues[Matrix.MSCALE_X]
    }

    /** Clamp translation so the image never flies off-screen. */
    private fun clampTranslation() {
        val d = drawable ?: return
        transformMatrix.getValues(matValues)
        val scale  = matValues[Matrix.MSCALE_X]
        var transX = matValues[Matrix.MTRANS_X]
        var transY = matValues[Matrix.MTRANS_Y]

        val scaledW = d.intrinsicWidth  * scale
        val scaledH = d.intrinsicHeight * scale
        val vw = width.toFloat()
        val vh = height.toFloat()

        transX = if (scaledW <= vw) (vw - scaledW) / 2f
                 else transX.coerceIn(vw - scaledW, 0f)

        transY = if (scaledH <= vh) (vh - scaledH) / 2f
                 else transY.coerceIn(vh - scaledH, 0f)

        matValues[Matrix.MTRANS_X] = transX
        matValues[Matrix.MTRANS_Y] = transY
        transformMatrix.setValues(matValues)
    }

    private fun span(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun midPoint(out: PointF, event: MotionEvent) {
        out.set(
            (event.getX(0) + event.getX(1)) / 2f,
            (event.getY(0) + event.getY(1)) / 2f
        )
    }
}
