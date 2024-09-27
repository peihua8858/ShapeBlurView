package net.center.blurview

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RSRuntimeException
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.AttributeSet
import android.util.StateSet
import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.IntDef
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ShapeBlurView : View, ViewTreeObserver.OnPreDrawListener {
    companion object {
        const val DEFAULT_BORDER_COLOR = Color.WHITE
        const val DEFAULT_BORDER_WIDTH: Float = 0f
        var RENDERING_COUNT: Int = 0
    }

    /**
     * If the view is on different root view (usually means we are on a PopupWindow),
     * we need to manually call invalidate() in onPreDraw(), otherwise we will not be able to see the changes
     */
    private var mDifferentRoot = false

    /**
     * default 4
     */
    private var mDownSampleFactor = 0f

    /**
     * default #000000
     */
    private var mOverlayColor = 0

    /**
     * default 10dp (0 < r <= 25)
     */
    private var mBlurRadius = 0f
    private val mCornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    private var mBorderColor = ColorStateList.valueOf(DEFAULT_BORDER_COLOR)
    private var mBitmapToBlur: Bitmap? = null
    private var mBlurredBitmap: Bitmap? = null
    private val mBitmapPaint = Paint()
    private val mBorderPaint = Paint()
    private var mBorderWidth = 0f
    private var mBlurringCanvas: Canvas? = null
    private var mBlurImpl: AndroidXBlurImpl = AndroidXBlurImpl()
    private var matrix = Matrix()
    private var shader: BitmapShader? = null
    private var mDirty = false
    private var mIsRendering = false
    private var blurMode = BlurMode.MODE_RECTANGLE
    private val mRectSrc = Rect()
    private val mRectFDst = RectF()
    private val cornerPath = Path()
    private val mBorderRect = RectF()
    private var cx = 0f
    private var cy: Float = 0f
    private var cRadius: Float = 0f
    /**
     * mDecorView should be the root view of the activity (even if you are on a different window like a dialog)
     */
    private var mDecorView: View? = null
    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.ShapeBlurView)
        mBlurRadius = a.getDimension(
            R.styleable.ShapeBlurView_blur_radius,
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                10f,
                context.resources.displayMetrics
            )
        )
        mDownSampleFactor = a.getFloat(R.styleable.ShapeBlurView_blur_down_sample, 4f)
        mOverlayColor = a.getColor(R.styleable.ShapeBlurView_blur_overlay_color, 0x000000)

        val radius =
            a.getDimensionPixelSize(R.styleable.ShapeBlurView_blur_corner_radius, -1).toFloat()
        val topLeftRadius =
            a.getDimensionPixelSize(R.styleable.ShapeBlurView_blur_corner_radius_top_left, -1)
                .toFloat()
        val topRightRadius =
            a.getDimensionPixelSize(R.styleable.ShapeBlurView_blur_corner_radius_top_right, -1)
                .toFloat()
        val bottomRight =
            a.getDimensionPixelSize(R.styleable.ShapeBlurView_blur_corner_radius_bottom_right, -1)
                .toFloat()
        val bottomLeft =
            a.getDimensionPixelSize(R.styleable.ShapeBlurView_blur_corner_radius_bottom_left, -1)
                .toFloat()
        mCornerRadii[0] = if (topLeftRadius > 0) topLeftRadius else radius
        mCornerRadii[1] = mCornerRadii[0]
        mCornerRadii[2] = if (topRightRadius > 0) topRightRadius else radius
        mCornerRadii[3] = mCornerRadii[2]
        mCornerRadii[4] = if (bottomRight > 0) bottomRight else radius
        mCornerRadii[5] = mCornerRadii[4]
        mCornerRadii[6] = if (bottomLeft > 0) bottomLeft else radius
        mCornerRadii[7] = mCornerRadii[6]
        blurMode = a.getInt(R.styleable.ShapeBlurView_blur_mode, BlurMode.MODE_RECTANGLE)

        mBorderWidth =
            a.getDimensionPixelSize(R.styleable.ShapeBlurView_blur_border_width, -1).toFloat()
        if (mBorderWidth < 0) {
            mBorderWidth = DEFAULT_BORDER_WIDTH
        }
        var colors = a.getColorStateList(R.styleable.ShapeBlurView_blur_border_color)
        if (colors == null) {
            colors = ColorStateList.valueOf(DEFAULT_BORDER_COLOR)
        }
        a.recycle()
        mBorderColor = colors
        mBitmapPaint.isAntiAlias = true
        mBorderPaint.style = Paint.Style.STROKE
        mBorderPaint.isAntiAlias = true
        mBorderPaint.setColor(
            mBorderColor.getColorForState(
                getState(),
                DEFAULT_BORDER_COLOR
            )
        )
        mBorderPaint.strokeWidth = mBorderWidth
    }

    fun getState(): IntArray {
        return StateSet.WILD_CARD
    }

    protected fun prepare(): Boolean {
        if (mBlurRadius == 0f) {
            release()
            return false
        }
        var downSampleFactor = mDownSampleFactor
        var radius = mBlurRadius / downSampleFactor
        if (radius > 25) {
            downSampleFactor = downSampleFactor * radius / 25
            radius = 25f
        }
        val width = width
        val height = height
        val scaledWidth = max(1.0, (width / downSampleFactor).toInt().toDouble()).toInt()
        val scaledHeight = max(1.0, (height / downSampleFactor).toInt().toDouble()).toInt()
        var dirty: Boolean = mDirty
        if (mBlurringCanvas == null || mBlurredBitmap == null || mBlurredBitmap!!.width != scaledWidth || mBlurredBitmap!!.height != scaledHeight) {
            dirty = true
            releaseBitmap()
            var r = false
            try {
                mBitmapToBlur =
                    Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)
                mBlurringCanvas = Canvas(mBitmapToBlur!!)
                mBlurredBitmap =
                    Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)
                r = true
            } catch (e: OutOfMemoryError) {
                // Bitmap.createBitmap() may cause OOM error
                // Simply ignore and fallback
            } finally {
                if (!r) {
                    release()
                    return false
                }
            }
        }
        if (dirty) {
            if (mBlurImpl.prepare(context, mBitmapToBlur, radius)) {
                mDirty = false
            } else {
                return false
            }
        }
        return true
    }

    private fun releaseBitmap() {
        if (mBitmapToBlur != null) {
            mBitmapToBlur!!.recycle()
            mBitmapToBlur = null
        }
        if (mBlurredBitmap != null) {
            mBlurredBitmap!!.recycle()
            mBlurredBitmap = null
        }
        matrix.reset()
        if (shader != null) {
            shader = null
        }
    }
    protected fun getActivityDecorView(): View? {
        var ctx = context
        var i = 0
        while (i < 4 && ctx !is Activity && ctx is ContextWrapper) {
            ctx = ctx.baseContext
            i++
        }
        return if (ctx is Activity) {
            ctx.window.decorView
        } else {
            null
        }
    }
    protected fun release() {
        releaseBitmap()
        mBlurImpl.release()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mDecorView = getActivityDecorView()
        if (mDecorView != null) {
            mDecorView!!.viewTreeObserver.addOnPreDrawListener(this)
            mDifferentRoot = mDecorView!!.rootView !== rootView
            if (mDifferentRoot) {
                mDecorView!!.postInvalidate()
            }
        } else {
            mDifferentRoot = false
        }
    }

    override fun onDetachedFromWindow() {
        mDecorView?.viewTreeObserver?.removeOnPreDrawListener(this)
        release()
        super.onDetachedFromWindow()
    }

    override fun draw(canvas: Canvas) {
        if (mIsRendering) {
            // Quit here, don't draw views above me
            throw RuntimeException()
        } else if (RENDERING_COUNT > 0) {
            // Doesn't support blurview overlap on another blurview
        } else {
            super.draw(canvas)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBlurredBitmap(canvas, mBlurredBitmap, mOverlayColor)
    }

    /**
     * Custom draw the blurred bitmap and color to define your own shape
     *
     * @param canvas
     * @param blurBitmap
     * @param overlayColor
     */
    protected fun drawBlurredBitmap(canvas: Canvas, blurBitmap: Bitmap?, overlayColor: Int) {
        if (blurBitmap != null) {
            if (blurMode == BlurMode.MODE_CIRCLE) {
                drawCircleRectBitmap(canvas, blurBitmap, overlayColor)
            } else if (blurMode == BlurMode.MODE_OVAL) {
                drawOvalRectBitmap(canvas, blurBitmap, overlayColor)
            } else {
                drawRoundRectBitmap(canvas, blurBitmap, overlayColor)
            }
        }
    }

    /**
     * 默认或者画矩形可带圆角
     *
     * @param canvas
     * @param blurBitmap
     * @param overlayColor
     */
    private fun drawRoundRectBitmap(canvas: Canvas, blurBitmap: Bitmap, overlayColor: Int) {
        try {
            //圆角的半径，依次为左上角xy半径，右上角，右下角，左下角
            mRectFDst.right = width.toFloat()
            mRectFDst.bottom = height.toFloat()
            /*向路径中添加圆角矩形。radii数组定义圆角矩形的四个圆角的x,y半径。radii长度必须为8*/
            //Path.Direction.CW：clockwise ，沿顺时针方向绘制,Path.Direction.CCW：counter-clockwise ，沿逆时针方向绘制
            cornerPath.addRoundRect(mRectFDst, mCornerRadii, Path.Direction.CW)
            cornerPath.close()
            canvas.clipPath(cornerPath)

            mRectSrc.right = blurBitmap.width
            mRectSrc.bottom = blurBitmap.height
            canvas.drawBitmap(blurBitmap, mRectSrc, mRectFDst, null)
            mBitmapPaint.color = overlayColor
            canvas.drawRect(mRectFDst, mBitmapPaint)
            if (mBorderWidth > 0) {
                //目前没找到合适方式
                mBorderPaint.strokeWidth = mBorderWidth * 2
                canvas.drawPath(cornerPath, mBorderPaint)
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 画椭圆，如果宽高一样则为圆形
     *
     * @param canvas
     * @param blurBitmap
     * @param overlayColor
     */
    private fun drawOvalRectBitmap(canvas: Canvas, blurBitmap: Bitmap, overlayColor: Int) {
        try {
            mRectFDst.right = width.toFloat()
            mRectFDst.bottom = height.toFloat()
            mBitmapPaint.reset()
            mBitmapPaint.isAntiAlias = true
            if (shader == null) {
                shader = BitmapShader(blurBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            if (matrix == null) {
                matrix = Matrix()
                matrix.postScale(
                    mRectFDst.width() / blurBitmap.width,
                    mRectFDst.height() / blurBitmap.height
                )
            }
            shader!!.setLocalMatrix(matrix)
            mBitmapPaint.setShader(shader)
            canvas.drawOval(mRectFDst, mBitmapPaint)
            mBitmapPaint.reset()
            mBitmapPaint.isAntiAlias = true
            mBitmapPaint.color = overlayColor
            canvas.drawOval(mRectFDst, mBitmapPaint)
            if (mBorderWidth > 0) {
                mBorderRect.set(mRectFDst)
                mBorderRect.inset(mBorderWidth / 2, mBorderWidth / 2)
                canvas.drawOval(mBorderRect, mBorderPaint)
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 画圆形，以宽高最小的为半径
     *
     * @param canvas
     * @param blurBitmap
     * @param overlayColor
     */
    private fun drawCircleRectBitmap(canvas: Canvas, blurBitmap: Bitmap, overlayColor: Int) {
        try {
            mRectFDst.right = measuredWidth.toFloat()
            mRectFDst.bottom = measuredHeight.toFloat()
            mRectSrc.right = blurBitmap.width
            mRectSrc.bottom = blurBitmap.height
            mBitmapPaint.reset()
            mBitmapPaint.isAntiAlias = true
            if (shader == null) {
                shader = BitmapShader(blurBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            if (matrix == null) {
                matrix = Matrix()
                matrix.postScale(
                    mRectFDst.width() / mRectSrc.width(),
                    mRectFDst.height() / mRectSrc.height()
                )
            }
            shader!!.setLocalMatrix(matrix)
            mBitmapPaint.setShader(shader)
            //前面Scale，故判断以哪一个来取中心点和半径
            if (mRectFDst.width() >= mRectSrc.width()) {
                cx = mRectFDst.width() / 2
                cy = mRectFDst.height() / 2
                //取宽高最小的为半径
                cRadius = min(mRectFDst.width(), mRectFDst.height()) / 2
                mBorderRect.set(mRectFDst)
            } else {
                cx = mRectSrc.width() / 2f
                cy = mRectSrc.height() / 2f
                cRadius = min(mRectSrc.width(), mRectSrc.height()) / 2f
                mBorderRect.set(mRectSrc)
            }
            canvas.drawCircle(cx, cy, cRadius, mBitmapPaint)
            mBitmapPaint.reset()
            mBitmapPaint.isAntiAlias = true
            mBitmapPaint.color = overlayColor
            canvas.drawCircle(cx, cy, cRadius, mBitmapPaint)
            //使用宽高相等的椭圆为圆形来画边框
            if (mBorderWidth > 0) {
                if (mBorderRect.width() > mBorderRect.height()) {
                    //原本宽大于高，圆是以中心点为圆心和高的一半为半径，椭圆区域是以初始00为开始，故整体向右移动差值
                    val dif: Float =
                        abs((mBorderRect.height() - mBorderRect.width())) / 2
                    mBorderRect.left = dif
                    mBorderRect.right =
                        min(mBorderRect.width(), mBorderRect.height()) + dif
                    mBorderRect.bottom =
                        min(mBorderRect.width(), mBorderRect.height())
                } else if (mBorderRect.width() < mBorderRect.height()) {
                    //原本高大于宽，圆是以中心点为圆心和宽的一半为半径，椭圆区域是以初始00为开始，故整体向下移动差值
                    val dif: Float =
                        abs((mBorderRect.height() - mBorderRect.width())) / 2
                    mBorderRect.top = dif
                    mBorderRect.right =
                        min(mBorderRect.width(), mBorderRect.height())
                    mBorderRect.bottom =
                        min(mBorderRect.width(), mBorderRect.height()) + dif
                } else {
                    //如果快高相同，则不需要偏移，椭圆画出来就是圆
                    mBorderRect.right =
                        min(mBorderRect.width(), mBorderRect.height())
                    mBorderRect.bottom =
                        min(mBorderRect.width(), mBorderRect.height())
                }
                mBorderRect.inset(mBorderWidth / 2, mBorderWidth / 2)
                canvas.drawOval(mBorderRect, mBorderPaint)
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    override fun onPreDraw(): Boolean {
        val locations = IntArray(2)
        var oldBmp = mBlurredBitmap
        val decor: View? = mDecorView
        if (decor != null && isShown && prepare()) {
            val redrawBitmap = mBlurredBitmap != oldBmp
            decor.getLocationOnScreen(locations)
            var x = -locations[0]
            var y = -locations[1]
            getLocationOnScreen(locations)
            x += locations[0]
            y += locations[1]
            // just erase transparent
            mBitmapToBlur!!.eraseColor(mOverlayColor and 0xffffff)
            val rc: Int = mBlurringCanvas!!.save()
            mIsRendering = true
            RENDERING_COUNT++
            try {
                mBlurringCanvas?.scale(
                    1f * mBitmapToBlur!!.width / width,
                    1f * mBitmapToBlur!!.height / height
                )
                mBlurringCanvas!!.translate(-x.toFloat(), -y.toFloat())
                if (decor.background != null) {
                    decor.background.draw(mBlurringCanvas!!)
                }
                decor.draw(mBlurringCanvas!!)
            } catch (e: Exception) {
            } finally {
                mIsRendering = false
                RENDERING_COUNT--
                mBlurringCanvas!!.restoreToCount(rc)
            }
            blur(mBitmapToBlur, mBlurredBitmap)
            if (redrawBitmap || mDifferentRoot) {
                invalidate()
            }
        }

        return true
    }
    fun blur(bitmapToBlur: Bitmap?, blurredBitmap: Bitmap?) {
        shader = BitmapShader(blurredBitmap!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        mBlurImpl.blur(bitmapToBlur, blurredBitmap)
    }
}


/**
 * @author center
 */
@Retention(AnnotationRetention.SOURCE)
@IntDef(
    BlurCorner.TOP_LEFT, BlurCorner.TOP_RIGHT, BlurCorner.BOTTOM_LEFT, BlurCorner.BOTTOM_RIGHT
)
annotation class BlurCorner {
    companion object {
        const val TOP_LEFT: Int = 0
        const val TOP_RIGHT: Int = 1
        const val BOTTOM_RIGHT: Int = 2
        const val BOTTOM_LEFT: Int = 3
    }
}

/**
 * @author center
 */
@Retention(AnnotationRetention.SOURCE)
@IntDef(
    BlurMode.MODE_RECTANGLE, BlurMode.MODE_CIRCLE, BlurMode.MODE_OVAL
)
annotation class BlurMode {
    companion object {
        const val MODE_RECTANGLE: Int = 0
        const val MODE_CIRCLE: Int = 1
        const val MODE_OVAL: Int = 2
    }
}

class AndroidXBlurImpl {
    private var mRenderScript: RenderScript? = null
    private var mBlurScript: ScriptIntrinsicBlur? = null
    private var mBlurInput: Allocation? = null
    private var mBlurOutput: Allocation? = null

    fun prepare(context: Context, buffer: Bitmap?, radius: Float): Boolean {
        if (mRenderScript == null) {
            try {
                mRenderScript = RenderScript.create(context)
                mBlurScript = ScriptIntrinsicBlur.create(mRenderScript, Element.U8_4(mRenderScript))
            } catch (e: RSRuntimeException) {
                if (isDebug(context)) {
                    throw e
                } else {
                    // In release mode, just ignore
                    release()
                    return false
                }
            }
        }
        mBlurScript!!.setRadius(radius)

        mBlurInput = Allocation.createFromBitmap(
            mRenderScript, buffer,
            Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT
        )
        mBlurOutput = Allocation.createTyped(mRenderScript, mBlurInput?.type)

        return true
    }

    fun release() {
        if (mBlurInput != null) {
            mBlurInput!!.destroy()
            mBlurInput = null
        }
        if (mBlurOutput != null) {
            mBlurOutput!!.destroy()
            mBlurOutput = null
        }
        if (mBlurScript != null) {
            mBlurScript!!.destroy()
            mBlurScript = null
        }
        if (mRenderScript != null) {
            mRenderScript!!.destroy()
            mRenderScript = null
        }
    }

    fun blur(input: Bitmap?, output: Bitmap?) {
        mBlurInput!!.copyFrom(input)
        mBlurScript!!.setInput(mBlurInput)
        mBlurScript!!.forEach(mBlurOutput)
        mBlurOutput!!.copyTo(output)
    }

    companion object {
        // android:debuggable="true" in AndroidManifest.xml (auto set by build tool)
        var DEBUG: Boolean? = null

        fun isDebug(ctx: Context?): Boolean {
            if (DEBUG == null && ctx != null) {
                DEBUG = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            }
            return DEBUG == java.lang.Boolean.TRUE
        }
    }
}