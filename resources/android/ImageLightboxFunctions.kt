package com.nativephp.plugins.imagelightbox

import android.animation.ValueAnimator
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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.LruCache
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.nativephp.mobile.bridge.BridgeError
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.min

// ---------------------------------------------------------------------------
// Dataset model
// ---------------------------------------------------------------------------

/** One row of the gallery dataset. */
data class LightboxItem(
    val index: Int,
    val uuid: String?,
    val remoteURL: String?,
    val localPath: String?,
    val text: String?
) {
    /** Stable cache key for the underlying image source. */
    val sourceKey: String get() = remoteURL ?: localPath ?: "index:$index"

    fun toJson(): JSONObject = JSONObject().apply {
        put("index", index)
        put("uuid", uuid ?: JSONObject.NULL)
        put("url", remoteURL ?: JSONObject.NULL)
        put("local", localPath ?: JSONObject.NULL)
        put("text", text ?: JSONObject.NULL)
    }

    companion object {

        fun from(json: JSONObject): LightboxItem? {
            val remote = json.stringOrNull("url")
            val local = json.stringOrNull("local")

            if (remote == null && local == null) return null

            return LightboxItem(
                index = json.optInt("index", 0),
                uuid = json.stringOrNull("uuid"),
                remoteURL = remote,
                localPath = local,
                text = json.stringOrNull("text")
            )
        }

        /**
         * The bridge router hands nested JSON straight through as org.json types,
         * but tolerate a raw JSON string or a decoded List too.
         */
        fun parseAll(raw: Any?): List<LightboxItem> {
            val objects: List<JSONObject> = when (raw) {
                is JSONArray -> (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
                is String -> runCatching { JSONArray(raw) }.getOrNull()
                    ?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }
                    ?: emptyList()
                is List<*> -> raw.mapNotNull { element ->
                    when (element) {
                        is JSONObject -> element
                        is Map<*, *> -> JSONObject().apply {
                            element.forEach { (key, value) ->
                                put(key.toString(), value ?: JSONObject.NULL)
                            }
                        }
                        else -> null
                    }
                }
                else -> emptyList()
            }

            return objects.mapNotNull { from(it) }
        }

        fun toJsonString(items: List<LightboxItem>): String =
            JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()
    }
}

/** `optString` renders JSON null as the literal "null" — check explicitly. */
private fun JSONObject.stringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).takeIf { it.isNotEmpty() }
}

/** Which toolbar buttons the lightbox shows, and whether captions are on. */
data class LightboxConfig(
    val edit: Boolean = false,
    val markup: Boolean = false,
    val share: Boolean = false,
    val delete: Boolean = false,
    /** Show each row's `text` at the foot of the screen while at default zoom. */
    val title: Boolean = false
) {
    companion object {
        fun from(parameters: Map<String, Any>) = LightboxConfig(
            edit = parameters["edit"] as? Boolean ?: false,
            markup = parameters["markup"] as? Boolean ?: false,
            share = parameters["share"] as? Boolean ?: false,
            delete = parameters["delete"] as? Boolean ?: false,
            title = parameters["title"] as? Boolean ?: false
        )
    }
}

// ---------------------------------------------------------------------------
// Bridge function entry points
// ---------------------------------------------------------------------------

object ImageLightboxFunctions {

    /** Single image — presented as a one-item gallery so both paths share the UI. */
    class Show(private val activity: FragmentActivity) : BridgeFunction {

        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val remoteURL = (parameters["url"] as? String)?.takeIf { it.isNotEmpty() }
            val localPath = (parameters["local"] as? String)?.takeIf { it.isNotEmpty() }

            if (remoteURL == null && localPath == null) {
                throw BridgeError.InvalidParameters("Either 'url' or 'local' is required")
            }

            val item = LightboxItem(
                index = 0,
                uuid = parameters["imageId"] as? String,
                remoteURL = remoteURL,
                localPath = localPath,
                text = null
            )

            present(activity, listOf(item), 0, LightboxConfig.from(parameters))

            return BridgeResponse.success(mapOf("presented" to true))
        }
    }

    /** Swipeable gallery. */
    class Gallery(private val activity: FragmentActivity) : BridgeFunction {

        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val items = LightboxItem.parseAll(parameters["items"])

            if (items.isEmpty()) {
                throw BridgeError.InvalidParameters("'items' must contain at least one image")
            }

            val startIndex = ((parameters["startIndex"] as? Number)?.toInt() ?: 0)
                .coerceIn(0, items.size - 1)

            present(activity, items, startIndex, LightboxConfig.from(parameters))

            return BridgeResponse.success(mapOf("presented" to true, "count" to items.size))
        }
    }

    /** Swap the dataset of an already-open gallery. A no-op when nothing is open. */
    class UpdateGallery(private val activity: FragmentActivity) : BridgeFunction {

        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val items = LightboxItem.parseAll(parameters["items"])

            activity.runOnUiThread {
                ImageLightboxFragment.active()?.applyItems(items)
            }

            return BridgeResponse.success(mapOf("updated" to true, "count" to items.size))
        }
    }

    /** Dismiss an open lightbox. A no-op when nothing is open. */
    class Close(private val activity: FragmentActivity) : BridgeFunction {

        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            activity.runOnUiThread {
                ImageLightboxFragment.active()?.closeWithoutEvent()
            }

            return BridgeResponse.success(mapOf("closed" to true))
        }
    }

    private fun present(
        activity: FragmentActivity,
        items: List<LightboxItem>,
        startIndex: Int,
        config: LightboxConfig
    ) {
        activity.runOnUiThread {
            // Reopening while one is already up would stack two full-screen dialogs.
            ImageLightboxFragment.active()?.dismissAllowingStateLoss()

            ImageLightboxFragment
                .newInstance(items, startIndex, config)
                .show(activity.supportFragmentManager, "ImageLightbox")
        }
    }
}

// ---------------------------------------------------------------------------
// Image loading — bitmap + on-disk caches shared by every page of one lightbox
// ---------------------------------------------------------------------------

class ImageLightboxLoader(private val context: Context) {

    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bitmaps = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Source key → on-disk copy, so Share never needs a second download. */
    private val files = mutableMapOf<String, File>()

    fun cachedBitmap(item: LightboxItem): Bitmap? = bitmaps.get(item.sourceKey)

    fun cachedFile(item: LightboxItem): File? = synchronized(files) { files[item.sourceKey] }

    fun shutdown() = executor.shutdown()

    /** Loads on a worker thread and delivers on the main thread. */
    fun load(item: LightboxItem, onResult: (Bitmap?, String?) -> Unit) {
        cachedBitmap(item)?.let {
            onResult(it, null)
            return
        }

        if (executor.isShutdown) return

        executor.execute {
            var bitmap: Bitmap? = null
            var error: String? = null

            try {
                bitmap = when {
                    item.remoteURL != null -> loadRemote(item, item.remoteURL)
                    item.localPath != null -> loadLocal(item, item.localPath)
                    else -> null
                }
                if (bitmap == null) {
                    error = "Unable to decode image.\n${item.remoteURL ?: item.localPath}"
                }
            } catch (e: Exception) {
                error = "Failed to load image: ${e.message}\n${item.remoteURL ?: item.localPath}"
            }

            bitmap?.let { bitmaps.put(item.sourceKey, it) }
            mainHandler.post { onResult(bitmap, error) }
        }
    }

    private fun loadRemote(item: LightboxItem, urlString: String): Bitmap? {
        val url = URL(urlString)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
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
        val file = File(context.cacheDir, "lightbox_${item.sourceKey.hashCode()}_${bytes.size}.$safeExt")
        FileOutputStream(file).use { it.write(bytes) }
        synchronized(files) { files[item.sourceKey] = file }

        return decodeBitmap(bytes)
    }

    private fun loadLocal(item: LightboxItem, path: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        return decodeBitmap(file.readBytes())?.also {
            synchronized(files) { files[item.sourceKey] = file }
        }
    }

    private fun decodeBitmap(bytes: ByteArray): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val buf = java.nio.ByteBuffer.wrap(bytes)
                android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(buf)
                ) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (_: Exception) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
}

// ---------------------------------------------------------------------------
// Dialog Fragment
// ---------------------------------------------------------------------------

class ImageLightboxFragment : DialogFragment() {

    companion object {
        private const val ARG_ITEMS = "items"
        private const val ARG_START_INDEX = "startIndex"
        private const val ARG_SHOW_EDIT = "showEdit"
        private const val ARG_SHOW_MARKUP = "showMarkup"
        private const val ARG_SHOW_SHARE = "showShare"
        private const val ARG_SHOW_DELETE = "showDelete"
        private const val ARG_SHOW_TITLE = "showTitle"

        private const val EVENT_PREFIX = "Pteal79\\ImageLightbox\\Events\\"

        /** The presented instance, so UpdateGallery / Close can reach it. */
        private var activeRef: WeakReference<ImageLightboxFragment>? = null

        fun active(): ImageLightboxFragment? = activeRef?.get()?.takeIf { it.isAdded }

        fun newInstance(
            items: List<LightboxItem>,
            startIndex: Int,
            config: LightboxConfig
        ): ImageLightboxFragment = ImageLightboxFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_ITEMS, LightboxItem.toJsonString(items))
                putInt(ARG_START_INDEX, startIndex)
                putBoolean(ARG_SHOW_EDIT, config.edit)
                putBoolean(ARG_SHOW_MARKUP, config.markup)
                putBoolean(ARG_SHOW_SHARE, config.share)
                putBoolean(ARG_SHOW_DELETE, config.delete)
                putBoolean(ARG_SHOW_TITLE, config.title)
            }
        }
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private var items: List<LightboxItem> = emptyList()
    private var currentIndex = 0
    private var config = LightboxConfig()

    /** The caption yields the screen to the image as soon as the reader zooms in. */
    private var isZoomedIn = false

    private lateinit var loader: ImageLightboxLoader
    private lateinit var rootView: LinearLayout
    private lateinit var toolbarView: FrameLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var counterLabel: TextView
    private lateinit var captionLabel: TextView

    private val adapter = PageAdapter()

    private fun currentItem(): LightboxItem? = items.getOrNull(currentIndex)

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        arguments?.let {
            items = LightboxItem.parseAll(it.getString(ARG_ITEMS))
            currentIndex = it.getInt(ARG_START_INDEX, 0).coerceIn(0, maxOf(items.size - 1, 0))
            config = LightboxConfig(
                edit = it.getBoolean(ARG_SHOW_EDIT, false),
                markup = it.getBoolean(ARG_SHOW_MARKUP, false),
                share = it.getBoolean(ARG_SHOW_SHARE, false),
                delete = it.getBoolean(ARG_SHOW_DELETE, false),
                title = it.getBoolean(ARG_SHOW_TITLE, false)
            )
        }

        loader = ImageLightboxLoader(requireContext().applicationContext)
        activeRef = WeakReference(this)
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
            alpha = 0f
        }

        rootView = root
        toolbarView = buildToolbar(ctx)
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

        captionLabel = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0x8C000000.toInt())
            }
            visibility = View.GONE
            alpha = 0f
        }

        viewPager = ViewPager2(ctx).apply {
            offscreenPageLimit = 1
            adapter = this@ImageLightboxFragment.adapter
            // A single image has nothing to page to — keep the horizontal drag inert.
            isUserInputEnabled = items.size > 1
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    currentIndex = position
                    // The incoming page is always at default zoom.
                    isZoomedIn = false
                    refreshChrome()
                }
            })
        }
        imageContainer.addView(viewPager, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Added after the pager so the caption sits above it.
        imageContainer.addView(captionLabel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).also { it.setMargins(dp(16), dp(16), dp(16), dp(16)) })

        viewPager.setCurrentItem(currentIndex, false)
        refreshChrome()

        // Smooth fade in; closing fades back out.
        root.animate().alpha(1f).setDuration(180).start()

        return root
    }

    override fun onStart() {
        super.onStart()
        // The dialog paints its own opaque backdrop by default, which would sit
        // between the fading root and the app during a swipe-to-dismiss.
        dialog?.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdown()
        if (activeRef?.get() === this) activeRef = null
    }

    // -----------------------------------------------------------------------
    // Dataset
    // -----------------------------------------------------------------------

    /**
     * Replace the dataset in place, holding the reader on the same image where it
     * survived the update. An empty dataset dismisses the lightbox.
     */
    fun applyItems(newItems: List<LightboxItem>) {
        if (!isAdded || view == null) return

        if (newItems.isEmpty()) {
            closeWithoutEvent()
            return
        }

        val previousUuid = currentItem()?.uuid
        val previousIndex = currentIndex

        items = newItems

        // Held in a local: notifyDataSetChanged can fire onPageSelected with a
        // clamped position, which would overwrite the index we just resolved.
        val target = newItems
            .indexOfFirst { previousUuid != null && it.uuid == previousUuid }
            .takeIf { it >= 0 }
            ?: min(previousIndex, newItems.size - 1)

        isZoomedIn = false
        adapter.notifyDataSetChanged()
        viewPager.isUserInputEnabled = items.size > 1
        viewPager.setCurrentItem(target, false)

        currentIndex = target
        refreshChrome()
    }

    /** Sync the counter and caption with [currentIndex] and the current zoom. */
    private fun refreshChrome() {
        counterLabel.visibility = if (items.size > 1) View.VISIBLE else View.GONE
        counterLabel.text = "${currentIndex + 1} / ${items.size}"

        val caption = currentItem()?.text

        if (!config.title || caption.isNullOrEmpty()) {
            captionLabel.animate().cancel()
            captionLabel.visibility = View.GONE
            captionLabel.alpha = 0f
            return
        }

        captionLabel.text = caption
        // Kept VISIBLE and faded on alpha so a cancelled animation can never
        // strand the label in the wrong state.
        captionLabel.visibility = View.VISIBLE
        captionLabel.animate().alpha(if (isZoomedIn) 0f else 1f).setDuration(200).start()
    }

    // -----------------------------------------------------------------------
    // Swipe down to dismiss
    // -----------------------------------------------------------------------

    /** How far the image must travel before releasing closes the lightbox. */
    private fun dismissThreshold(): Float =
        (rootView.height * 0.20f).coerceAtLeast(dp(120).toFloat())

    private fun handleDismissDrag(dy: Float) {
        val progress = (dy / dismissThreshold()).coerceIn(0f, 1f)
        val scale = 1f - progress * 0.2f

        viewPager.translationY = dy
        viewPager.scaleX = scale
        viewPager.scaleY = scale

        rootView.setBackgroundColor(Color.argb(((1f - progress) * 255).toInt(), 0, 0, 0))
        toolbarView.alpha = 1f - progress
        if (captionLabel.visibility == View.VISIBLE && !isZoomedIn) {
            captionLabel.alpha = 1f - progress
        }
    }

    private fun handleDismissDragEnd(dy: Float, velocityY: Float) {
        if (dy > dismissThreshold() || velocityY > 1800f) {
            // Carry the image off the bottom while the root fades out behind it.
            viewPager.animate()
                .translationY(rootView.height.toFloat())
                .alpha(0f)
                .setDuration(200)
                .start()

            closeWith("ClosePressed")

            return
        }

        viewPager.animate()
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()

        rootView.setBackgroundColor(0xFF000000.toInt())
        toolbarView.animate().alpha(1f).setDuration(220).start()
        refreshChrome()
    }

    /** A page left or returned to default zoom. */
    private fun onPageZoomChanged(position: Int, zoomedIn: Boolean) {
        if (position != currentIndex || isZoomedIn == zoomedIn) return

        isZoomedIn = zoomedIn
        refreshChrome()
    }

    // -----------------------------------------------------------------------
    // Pager adapter
    // -----------------------------------------------------------------------

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val ctx = parent.context

            val container = FrameLayout(ctx).apply {
                // ViewPager2 requires every page to fill the pager.
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val image = ZoomableImageView(ctx)
            container.addView(image, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            val progress = ProgressBar(ctx).apply { isIndeterminate = true }
            container.addView(progress, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))

            val message = TextView(ctx).apply {
                setTextColor(0xB3FFFFFF.toInt())
                textSize = 15f
                gravity = Gravity.CENTER
                visibility = View.GONE
            }
            container.addView(message, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).also { it.setMargins(dp(24), 0, dp(24), 0) })

            return PageHolder(container, image, progress, message)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            items.getOrNull(position)?.let { holder.bind(it, position) }
        }
    }

    private inner class PageHolder(
        container: FrameLayout,
        private val image: ZoomableImageView,
        private val progress: ProgressBar,
        private val message: TextView
    ) : RecyclerView.ViewHolder(container) {

        /** Identifies the in-flight load so a recycled page ignores a stale result. */
        private var token: String? = null

        /** This page's position, so the fragment can ignore off-screen reports. */
        private var boundPosition = -1

        init {
            image.onZoomChanged = { zoomedIn -> onPageZoomChanged(boundPosition, zoomedIn) }
            image.onDismissDrag = { dy ->
                if (boundPosition == currentIndex) handleDismissDrag(dy)
            }
            image.onDismissDragEnd = { dy, velocityY ->
                if (boundPosition == currentIndex) handleDismissDragEnd(dy, velocityY)
            }
        }

        fun bind(item: LightboxItem, position: Int) {
            token = item.sourceKey
            boundPosition = position
            message.visibility = View.GONE
            image.reset()

            val cached = loader.cachedBitmap(item)
            if (cached != null) {
                progress.visibility = View.GONE
                image.setImageBitmap(cached)
                return
            }

            image.setImageDrawable(null)
            progress.visibility = View.VISIBLE

            loader.load(item) { bitmap, error ->
                if (token != item.sourceKey) return@load

                progress.visibility = View.GONE

                if (bitmap != null) {
                    image.setImageBitmap(bitmap)
                } else {
                    reportLoadFailure(error ?: "Unable to load image.", message)
                }
            }
        }
    }

    /**
     * Single-image mode keeps the original blocking alert; a gallery shows the
     * failure inline so one bad row doesn't tear down the whole session.
     */
    private fun reportLoadFailure(message: String, inlineLabel: TextView) {
        if (!isAdded) return

        if (items.size > 1) {
            inlineLabel.text = message
            inlineLabel.visibility = View.VISIBLE
            return
        }

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Image Not Found")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> closeWithoutEvent() }
            .setCancelable(false)
            .show()
    }

    // -----------------------------------------------------------------------
    // Toolbar
    // -----------------------------------------------------------------------

    private enum class Icon { PENCIL, MARKUP, SHARE, TRASH, CLOSE }

    private fun buildToolbar(ctx: Context): FrameLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.TRANSPARENT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        // Action buttons on the left — matches iOS order: edit, markup, share, delete
        if (config.edit) row.addView(iconButton(ctx, Icon.PENCIL) { onEditPressed() })
        if (config.markup) row.addView(iconButton(ctx, Icon.MARKUP) { onMarkupPressed() })
        if (config.share) row.addView(iconButton(ctx, Icon.SHARE) { onSharePressed() })
        if (config.delete) row.addView(iconButton(ctx, Icon.TRASH) { onDeletePressed() })

        // Flexible spacer
        row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))

        // Close on the right
        row.addView(iconButton(ctx, Icon.CLOSE) { onClosePressed() })

        counterLabel = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            visibility = View.GONE
        }

        return FrameLayout(ctx).apply {
            addView(row, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(counterLabel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }
    }

    /** 36dp circular button with a white Canvas-drawn icon — matches iOS button style. */
    private fun iconButton(ctx: Context, icon: Icon, onClick: () -> Unit): ImageView {
        val size = dp(36)
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
        val c = Canvas(bmp)
        val sw = sizePx * 0.10f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = sw
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
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
    // Button actions
    // Capture requireActivity() BEFORE dismiss() to avoid detachment crash.
    // -----------------------------------------------------------------------

    /** Every event carries the row's `uuid` as `imageId` plus its dataset `index`. */
    private fun eventPayload(): String {
        val item = currentItem()
        return JSONObject(mapOf(
            "imageId" to (item?.uuid ?: ""),
            "index" to (item?.index ?: 0)
        )).toString()
    }

    private fun onClosePressed() = closeWith("ClosePressed")

    private fun onEditPressed() = closeWith("EditPressed")

    private fun onMarkupPressed() = closeWith("MarkupPressed")

    private fun onDeletePressed() {
        // In a gallery the reader stays put: the app removes the row and calls
        // update(), which slides the next image in (or closes on the last one).
        if (items.size > 1) {
            NativeActionCoordinator.dispatchEvent(
                requireActivity(),
                EVENT_PREFIX + "DeletePressed",
                eventPayload()
            )
        } else {
            closeWith("DeletePressed")
        }
    }

    fun closeWithoutEvent() = closeWith(null)

    private fun closeWith(event: String?) {
        if (!isAdded) return

        val activity = requireActivity()
        val payload = eventPayload()

        val finish = Runnable {
            dismissAllowingStateLoss()
            if (event != null) {
                NativeActionCoordinator.dispatchEvent(activity, EVENT_PREFIX + event, payload)
            }
        }

        val root = view
        if (root == null) {
            finish.run()
        } else {
            root.animate().alpha(0f).setDuration(160).withEndAction(finish).start()
        }
    }

    private fun onSharePressed() {
        val item = currentItem() ?: return

        loader.cachedFile(item)?.takeIf { it.exists() }?.let {
            shareFile(it)
            return
        }

        // Not loaded yet — fetch, then share.
        loader.load(item) { _, error ->
            if (!isAdded) return@load

            val file = loader.cachedFile(item)
            if (file != null && file.exists()) {
                shareFile(file)
            } else {
                Toast.makeText(
                    requireContext(),
                    error ?: "Failed to prepare image for sharing.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun shareFile(file: File) {
        val context = requireContext().applicationContext

        // The copy can be several MB, so it is made off the main thread.
        Thread {
            val shareable = try {
                shareableFile(context, file)
            } catch (e: Exception) {
                null
            }

            Handler(Looper.getMainLooper()).post {
                if (!isAdded) return@post

                val uri = shareable?.let {
                    try {
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }

                if (uri == null) {
                    Toast.makeText(requireContext(), "Failed to prepare image for sharing.", Toast.LENGTH_SHORT).show()
                    return@post
                }

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share Image"))
            }
        }.start()
    }

    /**
     * The file, or a copy of it, somewhere the FileProvider can serve.
     *
     * The NativePHP shell declares only `<cache-path>`, so a local image kept
     * anywhere else — the app's own storage, where MobileFileCache keeps its
     * copies — made getUriForFile throw and took the app down. A remote image
     * is already downloaded into the cache directory and is shared as it is.
     */
    private fun shareableFile(context: Context, file: File): File {
        val cacheDir = context.cacheDir.canonicalFile

        if (file.canonicalPath.startsWith(cacheDir.path + File.separator)) {
            return file
        }

        val shareDir = File(cacheDir, "lightbox_share").apply { mkdirs() }
        val copy = File(shareDir, file.name)

        if (!copy.exists() || copy.length() != file.length()) {
            file.copyTo(copy, overwrite = true)
        }

        return copy
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}

// ---------------------------------------------------------------------------
// ZoomableImageView — pinch-to-zoom + pan with Matrix, cooperating with ViewPager2
// ---------------------------------------------------------------------------

@SuppressLint("ClickableViewAccessibility")
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val transformMatrix = Matrix()
    private val matValues = FloatArray(9)

    /** The aspect-fit scale — zoom is measured relative to it, never to 1:1 pixels. */
    private var baseScale = 1f
    private val maxZoomFactor = 5f

    // Pan state
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    // Pinch state
    private var initialSpan = 0f
    private var initialScale = 1f
    private val initialFocus = PointF()
    private var isScaling = false

    private var zoomAnimator: ValueAnimator? = null

    /** Fires only when the view crosses between default zoom and zoomed-in. */
    var onZoomChanged: ((Boolean) -> Unit)? = null
    private var reportedZoomedIn = false

    /** Swipe-down-to-dismiss, reported as cumulative dy and (dy, velocity) on release. */
    var onDismissDrag: ((Float) -> Unit)? = null
    var onDismissDragEnd: ((Float, Float) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var isDismissDragging = false
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val doubleTapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isZoomed()) {
                animateScaleTo(baseScale, width / 2f, height / 2f)
            } else {
                animateScaleTo(baseScale * 2.5f, e.x, e.y)
            }
            return true
        }
    })

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

    /** Drop any zoom/pan — called when a recycled page is rebound. */
    fun reset() {
        zoomAnimator?.cancel()
        isScaling = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        fitImage()
    }

    /** Scale image to aspect-fit the view, centred. */
    private fun fitImage() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (dw <= 0 || dh <= 0 || vw <= 0 || vh <= 0) return

        baseScale = min(vw / dw, vh / dh)
        val tx = (vw - dw * baseScale) / 2f
        val ty = (vh - dh * baseScale) / 2f

        transformMatrix.reset()
        transformMatrix.postScale(baseScale, baseScale)
        transformMatrix.postTranslate(tx, ty)
        imageMatrix = transformMatrix
        invalidate()
        notifyZoomState()
    }

    // -----------------------------------------------------------------------
    // Touch handling
    // -----------------------------------------------------------------------

    private fun handleTouch(event: MotionEvent): Boolean {
        doubleTapDetector.onTouchEvent(event)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                zoomAnimator?.cancel()
                lastTouchX = event.x
                lastTouchY = event.y
                activePointerId = event.getPointerId(0)

                downX = event.x
                downY = event.y
                isDismissDragging = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }

                // While zoomed the pan belongs to this view, not to the pager.
                requestPagerLock(isZoomed())
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger means a pinch, not a dismiss.
                if (isDismissDragging) {
                    isDismissDragging = false
                    onDismissDragEnd?.invoke(0f, 0f)
                }
                if (event.pointerCount == 2) {
                    initialSpan = span(event)
                    initialScale = currentScale()
                    midPoint(initialFocus, event)
                    isScaling = true
                    requestPagerLock(true)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)

                // At default zoom a downward drag dismisses instead of panning.
                if (!isScaling && event.pointerCount == 1 && !isZoomed()) {
                    val dragY = event.y - downY
                    val dragX = event.x - downX

                    if (!isDismissDragging && dragY > touchSlop && dragY > kotlin.math.abs(dragX)) {
                        isDismissDragging = true
                        requestPagerLock(true)
                    }

                    if (isDismissDragging) {
                        onDismissDrag?.invoke(dragY.coerceAtLeast(0f))
                        return true
                    }
                }

                if (isScaling && event.pointerCount >= 2) {
                    val newSpan = span(event)
                    if (initialSpan > 0) {
                        val desired = initialScale * (newSpan / initialSpan)
                        val clamped = desired.coerceIn(baseScale, baseScale * maxZoomFactor)
                        val scaleDelta = clamped / currentScale()
                        transformMatrix.postScale(scaleDelta, scaleDelta, initialFocus.x, initialFocus.y)
                        clampTranslation()
                        imageMatrix = transformMatrix
                        notifyZoomState()
                    }
                } else {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx >= 0) {
                        val dx = event.getX(idx) - lastTouchX
                        val dy = event.getY(idx) - lastTouchY
                        // Hand the gesture back to the pager once the zoomed image
                        // has nothing left to reveal in the swipe direction.
                        requestPagerLock(canPanHorizontally(dx))
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
                val upId = event.getPointerId(upIdx)
                if (upId == activePointerId) {
                    val newIdx = if (upIdx == 0) 1 else 0
                    lastTouchX = event.getX(newIdx)
                    lastTouchY = event.getY(newIdx)
                    activePointerId = event.getPointerId(newIdx)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDismissDragging) {
                    val tracker = velocityTracker
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)

                    isDismissDragging = false
                    onDismissDragEnd?.invoke(
                        (event.y - downY).coerceAtLeast(0f),
                        if (event.actionMasked == MotionEvent.ACTION_CANCEL) 0f else tracker?.yVelocity ?: 0f
                    )
                }

                velocityTracker?.recycle()
                velocityTracker = null

                activePointerId = MotionEvent.INVALID_POINTER_ID
                isScaling = false
                requestPagerLock(false)
            }
        }
        return true
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun requestPagerLock(locked: Boolean) {
        parent?.requestDisallowInterceptTouchEvent(locked)
    }

    private fun isZoomed(): Boolean = currentScale() > baseScale * 1.01f

    private fun notifyZoomState() {
        val zoomedIn = isZoomed()
        if (zoomedIn == reportedZoomedIn) return

        reportedZoomedIn = zoomedIn
        onZoomChanged?.invoke(zoomedIn)
    }

    /** Is there still zoomed-in image to reveal by dragging [dx] pixels? */
    private fun canPanHorizontally(dx: Float): Boolean {
        val d = drawable ?: return false
        transformMatrix.getValues(matValues)

        val scaledW = d.intrinsicWidth * matValues[Matrix.MSCALE_X]
        if (scaledW <= width) return false

        val transX = matValues[Matrix.MTRANS_X]
        return if (dx > 0) transX < -0.5f else transX > (width - scaledW) + 0.5f
    }

    private fun animateScaleTo(target: Float, focusX: Float, focusY: Float) {
        zoomAnimator?.cancel()
        zoomAnimator = ValueAnimator.ofFloat(currentScale(), target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                val delta = value / currentScale()
                transformMatrix.postScale(delta, delta, focusX, focusY)
                clampTranslation()
                imageMatrix = transformMatrix
                notifyZoomState()
            }
            start()
        }
    }

    private fun currentScale(): Float {
        transformMatrix.getValues(matValues)
        return matValues[Matrix.MSCALE_X]
    }

    /** Clamp translation so the image never flies off-screen. */
    private fun clampTranslation() {
        val d = drawable ?: return
        transformMatrix.getValues(matValues)
        val scale = matValues[Matrix.MSCALE_X]
        var transX = matValues[Matrix.MTRANS_X]
        var transY = matValues[Matrix.MTRANS_Y]

        val scaledW = d.intrinsicWidth * scale
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
