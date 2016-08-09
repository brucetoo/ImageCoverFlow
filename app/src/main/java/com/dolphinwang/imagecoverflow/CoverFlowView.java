/*
 * Copyright (C) 2013 Roy Wang
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dolphinwang.imagecoverflow;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.support.v4.util.LruCache;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.widget.Scroller;

/**
 * @author dolphinWang
 * @time 2013-11-29
 */
public class CoverFlowView<T extends CoverFlowAdapter> extends View {


//    @Retention(RetentionPolicy.SOURCE)
//    @IntDef({TOP,BOTTOM,CENTER_VERTICAL})
//    public @interface GRAVITY{};
//    public static final int TOP = 1;
//    public static final int BOTTOM = 2;
//    public static final int CENTER_VERTICAL = 3;
//
//    @Retention(RetentionPolicy.SOURCE)
//    @IntDef({MATCH_PARENT,WRAP_CONTENT})
//    public @interface LAYOUT{};
//    public static final int MATCH_PARENT = 4;
//    public static final int WRAP_CONTENT = 5;

    public enum CoverFlowGravity {
        TOP, BOTTOM, CENTER_VERTICAL
    }

    public enum CoverFlowLayoutMode {
        MATCH_PARENT, WRAP_CONTENT
    }

    private static final String VIEW_LOG_TAG = "CoverFlowView";

    private static final int DURATION = 200;

    protected final int INVALID_POSITION = -1;

    protected static final int DEFAULT_VISIBLE_IMAGES = 3;

    // Used to indicate a no preference for a position type.
    static final int NO_POSITION = -1;

    // the visible views left and right
    protected int mVisibleImages = DEFAULT_VISIBLE_IMAGES;

    // space between each two of children
    protected final int CHILD_SPACING = -200;

    private final int ALPHA_DATUM = 76;

    private int STANDARD_ALPHA;
    private static final float CARD_SCALE = 0.15f;

    //好像是滑动切换的灵敏度?
    private static float MOVE_POS_MULTIPLE = 3.0f;

    private static final int TOUCH_MINIMUM_MOVE = 5;

    private static final float MOVE_SPEED_MULTIPLE = 1;

    private static final float MAX_SPEED = 6.0f;

    private static final float FRICTION = 10.0f;

    private static final int LONG_CLICK_DELAY = ViewConfiguration
            .getLongPressTimeout();
    /****
     * static field
     ****/

    private RecycleBin mRecycler;
    protected int mCoverFlowCenter;
    private T mAdapter;

    private int mVisibleChildCount;
    private int mItemCount;

    /**
     * True if the data has changed since the last layout
     */
    boolean mDataSetChanged;

    protected CoverFlowGravity mGravity;

    protected CoverFlowLayoutMode mLayoutMode;

    private Rect mCoverFlowPadding;//控件的padding值

    private PaintFlagsDrawFilter mDrawFilter;

    private Matrix mChildTransformer;
    private Matrix mReflectionTransformer;

    private Paint mDrawChildPaint;

    private RectF mTouchRect;

    private int mWidth;
    private boolean mTouchMoved;
    private float mTouchStartPos;
    private float mTouchStartX;
    private float mTouchStartY;

    private float mOffset;

    private float mStartOffset;
    private long mStartTime;

    private float mStartSpeed;
    private float mDuration;
    private Runnable mAnimationRunnable;
    private VelocityTracker mVelocity;

    private int mChildHeight;
    private int mChildTranslateY;
    private int mReflectionTranslateY;

    private float reflectHeightFraction;
    private int reflectGap;

    private boolean topImageClickEnable = true;

    private CoverFlowListener<T> mCoverFlowListener;

    private TopImageLongClickListener mLongClickListener;
    private LongClickRunnable mLongClickRunnable;
    private boolean mLongClickPosted;
    private boolean mLongClickTriggled;

    private int mTopImageIndex;

    private Scroller mScroller;

    /**
     * Record origin width and height of images
     */
    private SparseArray<int[]> mImageRecorder;

    private DataSetObserver mDataSetObserver = new DataSetObserver() {

        @Override
        public void onChanged() {
            final int nextItemCount = mAdapter.getCount();

            // If current index of top image will larger than total count in future,
            // locate it to new mid.
            if (mTopImageIndex % mItemCount > nextItemCount - 1) {
                mOffset = nextItemCount - mVisibleImages - 1;
            } else { // If current index top top image will less than total count in future,
                // change mOffset to current state in first loop
                mOffset += mVisibleImages;
                while (mOffset < 0 || mOffset >= mItemCount) {
                    if (mOffset < 0) {
                        mOffset += mItemCount;
                    } else if (mOffset >= mItemCount) {
                        mOffset -= mItemCount;
                    }
                }
                mOffset -= mVisibleImages;
            }

            mItemCount = nextItemCount;
            resetCoverFlow();

            requestLayout();
            invalidate();
            super.onChanged();
        }

        @Override
        public void onInvalidated() {
            super.onInvalidated();
        }

    };


    public CoverFlowView(Context context) {
        super(context);
        init();
    }

    public CoverFlowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initAttributes(context, attrs);
        init();
    }

    public CoverFlowView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initAttributes(context, attrs);
        init();
    }

    private void initAttributes(Context context, AttributeSet attrs) {
        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.ImageCoverFlowView);

        int totalVisibleChildren = a.getInt(R.styleable.ImageCoverFlowView_visibleImage, 3);

        setVisibleImage(totalVisibleChildren);

        reflectHeightFraction = a.getFraction(R.styleable.ImageCoverFlowView_reflectionHeight, 100, 0, 0.0f);
        /**
         float result = getActivity().getResources().getFraction(R.fraction.fraction_test, 5, 6);

         1）如果fraction_test 是5%(占整个屏幕的比例)，那么result就是：5%*5  用前值

         2）如果fraction_test 是5%p(占父view的比例)，那么result就是：5%*6  用后值 区别就在于 %p是否被使用
         */
        if (reflectHeightFraction > 100) {
            reflectHeightFraction = 100;
        }
        reflectHeightFraction /= 100;
        reflectGap = a.getDimensionPixelSize(
                R.styleable.ImageCoverFlowView_reflectionGap, 0);

        mGravity = CoverFlowGravity.values()[a.getInt(
                R.styleable.ImageCoverFlowView_coverflowGravity,
                CoverFlowGravity.CENTER_VERTICAL.ordinal())];

        mLayoutMode = CoverFlowLayoutMode.values()[a.getInt(
                R.styleable.ImageCoverFlowView_coverflowLayoutMode,
                CoverFlowLayoutMode.WRAP_CONTENT.ordinal())];

        topImageClickEnable = a.getBoolean(R.styleable.ImageCoverFlowView_topImageClickEnable, true);

        a.recycle();
    }

    private void init() {
        setWillNotDraw(false);
        setClickable(true);

        mChildTransformer = new Matrix();
        mReflectionTransformer = new Matrix();

        mTouchRect = new RectF();

        mImageRecorder = new SparseArray<int[]>();

        mDrawChildPaint = new Paint();
        mDrawChildPaint.setAntiAlias(true);
        mDrawChildPaint.setFlags(Paint.ANTI_ALIAS_FLAG);

        mCoverFlowPadding = new Rect();

        //抗锯齿处理
        mDrawFilter = new PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG
                | Paint.FILTER_BITMAP_FLAG);

        //滚动监听的帮助类
        mScroller = new Scroller(getContext(),
                new AccelerateDecelerateInterpolator());
    }

    /**
     * if subclass override this method, should call super method.
     *
     * @param adapter extends CoverFlowAdapter
     */
    public void setAdapter(T adapter) {

        if (mAdapter != null) {
            mAdapter.unregisterDataSetObserver(mDataSetObserver);
        }

        mAdapter = adapter;

        if (mAdapter != null) {
            mAdapter.registerDataSetObserver(mDataSetObserver);

            mItemCount = mAdapter.getCount();

            if (mRecycler != null) {
                mRecycler.clear();
            } else {
                mRecycler = new RecycleBin();
            }
        }

        mOffset = 0;

        resetCoverFlow();

        requestLayout();
    }

    public T getAdapter() {
        return mAdapter;
    }

    public void setCoverFlowListener(CoverFlowListener<T> l) {
        mCoverFlowListener = l;
    }

    private void resetCoverFlow() {

        if (mItemCount < 3) {
            throw new IllegalArgumentException(
                    "total count in adapter must larger than 3!");
        }

        final int totalVisible = mVisibleImages * 2 + 1;
        if (mItemCount < totalVisible) {
            mVisibleImages = (mItemCount - 1) / 2;
        }

        mChildHeight = 0;

        STANDARD_ALPHA = (255 - ALPHA_DATUM) / mVisibleImages;

        if (mGravity == null) {
            mGravity = CoverFlowGravity.CENTER_VERTICAL;
        }

        if (mLayoutMode == null) {
            mLayoutMode = CoverFlowLayoutMode.WRAP_CONTENT;
        }

        mImageRecorder.clear();

        mTopImageIndex = INVALID_POSITION;
        mDataSetChanged = true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        if (mAdapter == null) {
            return;
        }

        if (!mDataSetChanged) {
            return;
        }

        //记录控件的padding值
        mCoverFlowPadding.left = getPaddingLeft();
        mCoverFlowPadding.right = getPaddingRight();
        mCoverFlowPadding.top = getPaddingTop();
        mCoverFlowPadding.bottom = getPaddingBottom();

        //根据Mode计算所需的实际高度
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        //控件实际的高度
        int availableHeight = heightSize - mCoverFlowPadding.top
                - mCoverFlowPadding.bottom;

        int maxChildTotalHeight = 0;

        /**
         * 其实就是{@linkplain #mItemCount}
         * {@linkplain #mVisibleImages} 代表左右两边显示的Item数量
         */
        int visibleCount = (mVisibleImages << 1) + 1;
        //mOffset初始值为0,值向下取整
        int mid = (int) Math.floor(mOffset + 0.5);
        int leftChild = visibleCount >> 1;// = mVisibleImages
        //找到adapter中起始绘制的item
        final int startPos = getActuallyPosition(mid - leftChild);

        for (int i = startPos; i < visibleCount + startPos; ++i) {
            Bitmap child = mAdapter.getImage(i);
            final int childHeight = child.getHeight();
            //item高度 = bitmap高度 + bitmap投影高度 + 投影与原图间隔的高度
            final int childTotalHeight = (int) (childHeight + childHeight
                    * reflectHeightFraction + reflectGap);

            //计算保存adapter中图片占用最大的高度值
            maxChildTotalHeight = (maxChildTotalHeight < childTotalHeight) ? childTotalHeight
                    : maxChildTotalHeight;
        }

        if (heightMode == MeasureSpec.EXACTLY || heightMode == MeasureSpec.AT_MOST) {
            // if height which parent provided is less than child need, scale
            // child height to parent provide
            // 测绘出的高度小于父布局高度 则用父布局高度代表child高度
            if (availableHeight < maxChildTotalHeight) {
                mChildHeight = availableHeight;
            } else {
                // if larger than, depends on layout mode
                // if layout mode is match_parent, scale child height to parent
                // provide
                if (mLayoutMode == CoverFlowLayoutMode.MATCH_PARENT) {
                    mChildHeight = availableHeight;
                    // if layout mode is wrap_content, keep child's original
                    // height
                } else if (mLayoutMode == CoverFlowLayoutMode.WRAP_CONTENT) {
                    mChildHeight = maxChildTotalHeight;

                    // adjust parent's height
                    if (heightMode == MeasureSpec.AT_MOST) {
                        heightSize = mChildHeight + mCoverFlowPadding.top
                                + mCoverFlowPadding.bottom;
                    }
                }
            }
        } else {
            // height mode is unspecified
            if (mLayoutMode == CoverFlowLayoutMode.MATCH_PARENT) {
                mChildHeight = availableHeight;
            } else if (mLayoutMode == CoverFlowLayoutMode.WRAP_CONTENT) {
                mChildHeight = maxChildTotalHeight;

                // adjust parent's height
                heightSize = mChildHeight + mCoverFlowPadding.top
                        + mCoverFlowPadding.bottom;
            }
        }

        // Adjust movement in y-axis according to gravity
        // 根据gravity计算child高度和  heightSize变化后 bitmap 需要移动的Y轴距离
        if (mGravity == CoverFlowGravity.CENTER_VERTICAL) {
            mChildTranslateY = (heightSize >> 1) - (mChildHeight >> 1);
        } else if (mGravity == CoverFlowGravity.TOP) {
            mChildTranslateY = mCoverFlowPadding.top;
        } else if (mGravity == CoverFlowGravity.BOTTOM) {
            mChildTranslateY = heightSize - mCoverFlowPadding.bottom
                    - mChildHeight;
        }
        //倒影移动y轴距离
        mReflectionTranslateY = (int) (mChildTranslateY + mChildHeight - mChildHeight
                * reflectHeightFraction);


        //重新计算控件高度
        setMeasuredDimension(widthSize, heightSize);
        mVisibleChildCount = visibleCount;
        mWidth = widthSize;
    }

    /**
     * subclass should never override this method, because all of child will
     * draw on the canvas directly
     */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right,
                            int bottom) {
    }

    @Override
    protected void onDraw(Canvas canvas) {

        if (mAdapter == null) {
            super.onDraw(canvas);
            return;
        }

        //cavas抗锯齿处理
        // mDrawFilter = new PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG
        // | Paint.FILTER_BITMAP_FLAG);
        canvas.setDrawFilter(mDrawFilter);

        final float offset = mOffset;
        int i = 0;
        int mid = (int) Math.floor(offset + 0.5);

        int rightChild = (mVisibleChildCount % 2 == 0) ? (mVisibleChildCount >> 1) - 1
                : mVisibleChildCount >> 1;
        int leftChild = mVisibleChildCount >> 1;

        // draw the left children
        int startPos = mid - leftChild;
        for (i = startPos; i < mid; ++i) {
            drawChild(canvas, mid, i, i - offset);
        }

        // draw the right children
        int endPos = mid + rightChild;
        for (i = endPos; i >= mid; --i) {
            drawChild(canvas, mid, i, i - offset);
        }

        //代表最上层的bitmap
        if ((offset - (int) offset) == 0.0f) {
            imageOnTop(getActuallyPosition((int) offset));
        }

        super.onDraw(canvas);

        mCoverFlowListener.invalidationCompleted();
    }

    protected final void drawChild(Canvas canvas, int mid, int position, float offset) {

        int actuallyPosition = getActuallyPosition(position);

        final Bitmap child = mAdapter.getImage(actuallyPosition);
        final Bitmap reflection = obtainReflection(child);

        //查询是否存储过bitmap的宽高度
        int[] wAndh = mImageRecorder.get(actuallyPosition);
        if (wAndh == null) {
            //没有对应位置的bitmap值,设置新值
            wAndh = new int[]{child.getWidth(), child.getHeight()};
            mImageRecorder.put(actuallyPosition, wAndh);
        } else {
            wAndh[0] = child.getWidth();
            wAndh[1] = child.getHeight();
        }

        if (child != null && !child.isRecycled() && canvas != null) {
            makeChildTransformer(child, mid, position, offset);
            canvas.drawBitmap(child, mChildTransformer, mDrawChildPaint);
            if (reflection != null) {

                canvas.drawBitmap(reflection, mReflectionTransformer,
                        mDrawChildPaint);
            }
        }
    }

    /**
     * <ul>
     * <li>对bitmap进行伪3d变换</li>
     * </ul>
     *
     * @param child
     * @param position
     * @param offset
     */
    private void makeChildTransformer(Bitmap child, int mid, int position, float offset) {
        mChildTransformer.reset();
        mReflectionTransformer.reset();

        float scale = 0;
        //this scale make sure that each image will be smaller than the
        //previous one
//        if (position != mid) {
//            scale = 1 - Math.abs(offset) * CARD_SCALE;
//        } else {
//            scale = 1 - Math.abs(offset) * CARD_SCALE;
//        }
        //缩放比例 offset 越大缩放比例越大
        scale = 1 - Math.abs(offset) * CARD_SCALE;
        float translateX = 0;

        //原始bitmap占据的高度
        final int originalChildHeight = (int) (mChildHeight - mChildHeight
                * reflectHeightFraction - reflectGap);

        //整个view占据的高度
        final int childTotalHeight = (int) (child.getHeight()
                + child.getHeight() * reflectHeightFraction + reflectGap);

        //原始bitmap缩放比
        final float originalChildHeightScale = (float) originalChildHeight
                / child.getHeight();

        //换算后的缩放比
        final float childHeightScale = originalChildHeightScale * scale;

        //缩放后的bitmap的宽度(包含了非mid bitmap 通过offset -> scale -> childHeightScale)
        final int childWidth = (int) (child.getWidth() * childHeightScale);

        //最中间Bitmap的宽度  缩放比和高度保持一样
        final int centerChildWidth = (int) (child.getWidth() * originalChildHeightScale);

        //中间bitmap离左边的space
        int leftSpace = ((mWidth >> 1) - mCoverFlowPadding.left)
                - (centerChildWidth >> 1);
        //中间bitmap离右边的space
        int rightSpace = (((mWidth >> 1) - mCoverFlowPadding.right) - (centerChildWidth >> 1));

        if (offset <= 0) {//计算左边的item translateX 值  离中心越远 x值越小
            translateX = ((float) leftSpace / mVisibleImages)
                    * (mVisibleImages + offset) + mCoverFlowPadding.left;
        }else {
            translateX = mWidth - ((float) rightSpace / mVisibleImages)
                    * (mVisibleImages - offset) - childWidth
                    - mCoverFlowPadding.right;
        }

        float alpha = (float) 254 - Math.abs(offset) * STANDARD_ALPHA;

        if (alpha < 0) {
            alpha = 0;
        } else if (alpha > 254) {
            alpha = 254;
        }

        mDrawChildPaint.setAlpha((int) alpha);

        mChildTransformer.preTranslate(0, -(childTotalHeight >> 1));
        mChildTransformer.postScale(childHeightScale, childHeightScale);

        if ((offset - (int) offset) == 0.0f) {
            Log.e(VIEW_LOG_TAG, "offset=>" + offset + " scale=>" + childHeightScale);
        }

        // if actually child height is larger or smaller than original child
        // height
        // need to change translate distance of y-axis
        float adjustedChildTranslateY = 0;
        if (childHeightScale != 1) {
            adjustedChildTranslateY = (mChildHeight - childTotalHeight) >> 1;
        }

        mChildTransformer.postTranslate(translateX, mChildTranslateY
                + adjustedChildTranslateY);

         Log.i(VIEW_LOG_TAG, "position= " + position + " mChildTranslateY= "
         + mChildTranslateY + adjustedChildTranslateY);

        //下面方式能实现去除 childTotalHeight >> 1 后的效果
//        mChildTransformer.postTranslate(0, 100 * Math.abs(offset));
        getCustomTransformMatrix(mChildTransformer, mDrawChildPaint, child,
                position, offset);

        mChildTransformer.postTranslate(0, (childTotalHeight >> 1));

        mReflectionTransformer.preTranslate(0, -(childTotalHeight >> 1));
        mReflectionTransformer.postScale(childHeightScale, childHeightScale);
        mReflectionTransformer.postTranslate(translateX, mReflectionTranslateY
                * scale + adjustedChildTranslateY);
        getCustomTransformMatrix(mReflectionTransformer, mDrawChildPaint,
                child, position, offset);
        mReflectionTransformer.postTranslate(0, (childTotalHeight >> 1));
    }

    /**
     * <ul>
     * <li>This is an empty method.</li>
     * <li>Giving user a chance to make more transform base on standard.</li>
     * </ul>
     *
     * @param mDrawChildPaint paint, user can set alpha
     * @param child           bitmap to draw
     * @param position
     * @param offset          offset to center(zero)
     */
    protected void getCustomTransformMatrix(Matrix transfromer,
                                            Paint mDrawChildPaint, Bitmap child, int position, float offset) {

        /** example code to make image y-axis rotation **/
        //用camera来实现3D旋转
//         Camera c = new Camera();
//         c.save();
//         Matrix m = new Matrix();
//         c.rotateY(10 * (-offset));
//         c.getMatrix(m);
//         c.restore();
//         m.preTranslate(-(child.getWidth() >> 1), -(child.getHeight() >> 1));
//         m.postTranslate(child.getWidth() >> 1, child.getHeight() >> 1);
//         transfromer.preConcat(m);
    }

    private void imageOnTop(int position) {
        mTopImageIndex = position;

        final int[] wAndh = mImageRecorder.get(position);

        final int heightInView = (int) (mChildHeight - mChildHeight
                * reflectHeightFraction - reflectGap);
        final float scale = (float) heightInView / wAndh[1];
        final int widthInView = (int) (wAndh[0] * scale);

        Log.e(VIEW_LOG_TAG, "height ==>" + heightInView + " width ==>"
                + widthInView);

        mTouchRect.left = (mWidth >> 1) - (widthInView >> 1);
        mTouchRect.top = mChildTranslateY;
        mTouchRect.right = mTouchRect.left + widthInView;
        mTouchRect.bottom = mTouchRect.top + heightInView;

        Log.e(VIEW_LOG_TAG, "rect==>" + mTouchRect);

        if (mCoverFlowListener != null) {
            mCoverFlowListener.imageOnTop(this, position, mTouchRect.left,
                    mTouchRect.top, mTouchRect.right, mTouchRect.bottom);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getParent() != null) {
            // 阻止父层的View截获touch事件
            getParent().requestDisallowInterceptTouchEvent(true);
        }

        int action = event.getAction();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (mScroller.computeScrollOffset()) {
                    mScroller.abortAnimation();
                    invalidate();
                }
                //先Remove 再重新触发
                stopLongClick();
                triggleLongClick(event.getX(), event.getY());
                touchBegan(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                touchMoved(event);
                return true;
            case MotionEvent.ACTION_UP:
                touchEnded(event);
                stopLongClick();
                return true;
        }

        return false;
    }

    /**
     * 从Down操作开始延迟判断是否是是在Long click，如果在LONG_CLICK_DELAY时间内再次
     * 触发Down操作,则重新开始执行Long click 逻辑
     * @param x
     * @param y
     */
    private void triggleLongClick(float x, float y) {
        if (mTouchRect.contains(x, y) && mLongClickListener != null
                && topImageClickEnable && !mLongClickPosted) {
            final int actuallyPosition = mTopImageIndex;

            mLongClickRunnable.setPosition(actuallyPosition);
            postDelayed(mLongClickRunnable, LONG_CLICK_DELAY);
        }
    }

    private void stopLongClick() {
        if (mLongClickRunnable != null) {
            removeCallbacks(mLongClickRunnable);
            mLongClickPosted = false;
            mLongClickTriggled = false;
        }
    }

    /**
     * 开始执行touch逻辑
     * @param event
     */
    private void touchBegan(MotionEvent event) {
        endAnimation();

        float x = event.getX();
        mTouchStartX = x;
        mTouchStartY = event.getY();
        mStartTime = AnimationUtils.currentAnimationTimeMillis();
        mStartOffset = mOffset;

        mTouchMoved = false;

        // ????什么算法????
        mTouchStartPos = (x / mWidth) * MOVE_POS_MULTIPLE - 5;
        mTouchStartPos /= 2;
        Log.e(VIEW_LOG_TAG,
                " TouchStartPos ==>" + mTouchStartPos);

        mVelocity = VelocityTracker.obtain();
        mVelocity.addMovement(event);
    }

    private void touchMoved(MotionEvent event) {

        //计算滚动到此的pos
        float pos = (event.getX() / mWidth) * MOVE_POS_MULTIPLE - 5;
        pos /= 2;

        if (!mTouchMoved) {
            float dx = Math.abs(event.getX() - mTouchStartX);
            float dy = Math.abs(event.getY() - mTouchStartY);

            if (dx < TOUCH_MINIMUM_MOVE && dy < TOUCH_MINIMUM_MOVE)
                return;

            mTouchMoved = true;

            stopLongClick();
        }

        //offset 位移
        mOffset = mStartOffset + mTouchStartPos - pos;

        invalidate();
        mVelocity.addMovement(event);
    }

    private void touchEnded(MotionEvent event) {
        float pos = (event.getX() / mWidth) * MOVE_POS_MULTIPLE - 5;
        pos /= 2;

        if (mTouchMoved || (mOffset - Math.floor(mOffset)) != 0) {
            mStartOffset += mTouchStartPos - pos;
            mOffset = mStartOffset;

            mVelocity.addMovement(event);

            mVelocity.computeCurrentVelocity(1000);
            double speed = mVelocity.getXVelocity();

            speed = (speed / mWidth) * MOVE_SPEED_MULTIPLE;
            if (speed > MAX_SPEED)
                speed = MAX_SPEED;
            else if (speed < -MAX_SPEED)
                speed = -MAX_SPEED;

            startAnimation(-speed);
        } else {
            Log.e(VIEW_LOG_TAG,
                    " touch ==>" + event.getX() + " , " + event.getY());
            if (mTouchRect != null) {
                if (mTouchRect.contains(event.getX(), event.getY())
                        && mCoverFlowListener != null && topImageClickEnable
                        && !mLongClickTriggled) {
                    final int actuallyPosition = mTopImageIndex;

                    mCoverFlowListener.topImageClicked(this, actuallyPosition);
                }
            }
        }

        mVelocity.clear();
        mVelocity.recycle();
    }

    private void startAnimation(double speed) {
        if (mAnimationRunnable != null)
            return;

        double delta = speed * speed / (FRICTION * 2);
        if (speed < 0)
            delta = -delta;

        double nearest = mStartOffset + delta;
        nearest = Math.floor(nearest + 0.5);

        mStartSpeed = (float) Math.sqrt(Math.abs(nearest - mStartOffset)
                * FRICTION * 2);
        if (nearest < mStartOffset)
            mStartSpeed = -mStartSpeed;

        mDuration = Math.abs(mStartSpeed / FRICTION);
        mStartTime = AnimationUtils.currentAnimationTimeMillis();

        mAnimationRunnable = new Runnable() {
            @Override
            public void run() {
                driveAnimation();
            }
        };
        post(mAnimationRunnable);
    }

    private void driveAnimation() {
        float elapsed = (AnimationUtils.currentAnimationTimeMillis() - mStartTime) / 1000.0f;
        if (elapsed >= mDuration)
            endAnimation();
        else {
            updateAnimationAtElapsed(elapsed);
            post(mAnimationRunnable);
        }
    }

    private void endAnimation() {
        if (mAnimationRunnable != null) {
            mOffset = (float) Math.floor(mOffset + 0.5);

            invalidate();

            removeCallbacks(mAnimationRunnable);
            mAnimationRunnable = null;
        }
    }

    private void updateAnimationAtElapsed(float elapsed) {
        if (elapsed > mDuration)
            elapsed = mDuration;

        float delta = Math.abs(mStartSpeed) * elapsed - FRICTION * elapsed
                * elapsed / 2;
        if (mStartSpeed < 0)
            delta = -delta;

        mOffset = mStartOffset + delta;
        invalidate();
    }

    /**
     * Convert draw-index to index in adapter
     *
     * @param position position to draw 绘制点的位置 0 表示中间的position
     * @return
     */
    private int getActuallyPosition(int position) {
        if (mAdapter == null) {
            return INVALID_POSITION;
        }

        int max = mAdapter.getCount();

        //将实际绘制的坐标 转换成 adapter 中的实际的坐标
        position += mVisibleImages;
        //保证左右的循环滚动 position -+ max
        while (position < 0 || position >= max) {
            if (position < 0) {
                position += max;
            } else if (position >= max) {
                position -= max;
            }
        }

        return position;
    }

    /**
     * 获取倒影image Bitmap,将src Bitmap Lru缓存起来以备使用
     * @param src
     * @return
     */
    private Bitmap obtainReflection(Bitmap src) {
        if (reflectHeightFraction <= 0) {
            return null;
        }

        Bitmap reflection = mRecycler.getCachedReflectiuon(src);

        if (reflection == null || reflection.isRecycled()) {
            //如果倒影bitmap不存在 先移除原始的view
            mRecycler.removeReflectionCache(src);

            reflection = BitmapUtils.createReflectedBitmap(src,
                    reflectHeightFraction);

            if (reflection != null) {
                mRecycler.buildReflectionCache(src, reflection);

                return reflection;
            }
        }

        return reflection;
    }

    public void setVisibleImage(int count) {
        if (count % 2 == 0) {
            throw new IllegalArgumentException(
                    "visible image must be an odd number");
        }

        if (count < 3) {
            throw new IllegalArgumentException(
                    "visible image must larger than 3");
        }

        mVisibleImages = count / 2;
        STANDARD_ALPHA = (255 - ALPHA_DATUM) / mVisibleImages;
    }

    public void setCoverFlowGravity(CoverFlowGravity gravity) {
        mGravity = gravity;
    }

    public void setCoverFlowLayoutMode(CoverFlowLayoutMode mode) {
        mLayoutMode = mode;
    }

    public void setReflectionHeight(int fraction) {
        if (fraction < 0)
            fraction = 0;
        else if (fraction > 100)
            fraction = 100;

        reflectHeightFraction = fraction;
    }

    public void setReflectionGap(int gap) {
        if (gap < 0)
            gap = 0;

        reflectGap = gap;
    }

    public void disableTopImageClick() {
        topImageClickEnable = false;
    }

    public void enableTopImageClick() {
        topImageClickEnable = true;
    }

    public void setSelection(int position) {
        final int max = mAdapter.getCount();
        if (position < 0 || position >= max) {
            throw new IllegalArgumentException(
                    "Position want to select can not less than 0 or larger than max of adapter provide!");
        }

        if (mTopImageIndex != position) {
            if (mScroller.computeScrollOffset()) {
                mScroller.abortAnimation();
            }

            final int from = (int) (mOffset * 100);
            final int disX = (int) ((position - mVisibleImages) * 100) - from;
            mScroller.startScroll(
                    from,
                    0,
                    disX,
                    0,
                    DURATION
                            * Math.min(
                            Math.abs(position + max - mTopImageIndex),
                            Math.abs(position - mTopImageIndex)));

            invalidate();
        }
    }

    @Override
    public void computeScroll() {
        super.computeScroll();

        if (mScroller.computeScrollOffset()) {
            final int currX = mScroller.getCurrX();

            mOffset = (float) currX / 100;

            invalidate();
        }
    }

    public void setTopImageLongClickListener(TopImageLongClickListener listener) {
        mLongClickListener = listener;

        if (listener == null) {
            mLongClickRunnable = null;
        } else {
            if (mLongClickRunnable == null) {
                mLongClickRunnable = new LongClickRunnable();
            }
        }
    }

    public int getTopImageIndex() {
        if (mTopImageIndex == INVALID_POSITION) {
            return -1;
        }

        return mTopImageIndex;
    }

    private class LongClickRunnable implements Runnable {
        private int position;

        public void setPosition(int position) {
            this.position = position;
        }

        @Override
        public void run() {
            if (mLongClickListener != null) {
                mLongClickListener.onLongClick(position);
                mLongClickTriggled = true;
            }
        }
    }

    class RecycleBin {

        //Lru缓存来来缓存bitmap
        final LruCache<Integer, Bitmap> bitmapCache = new LruCache<Integer, Bitmap>(
                getCacheSize(getContext())) {
            @Override
            protected int sizeOf(Integer key, Bitmap bitmap) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB_MR1) {
                    return bitmap.getRowBytes() * bitmap.getHeight();
                } else {
                    return bitmap.getByteCount();
                }
            }

            @Override
            protected void entryRemoved(boolean evicted, Integer key,
                                        Bitmap oldValue, Bitmap newValue) {
                if (evicted && oldValue != null && !oldValue.isRecycled()) {
                    oldValue.recycle();
                    oldValue = null;
                }
            }
        };

        public Bitmap getCachedReflectiuon(Bitmap origin) {
            return bitmapCache.get(origin.hashCode());
        }

        public void buildReflectionCache(Bitmap origin, Bitmap b) {
            bitmapCache.put(origin.hashCode(), b);
            //内存释放
            Runtime.getRuntime().gc();
        }

        public Bitmap removeReflectionCache(Bitmap origin) {
            if (origin == null) {
                return null;
            }

            return bitmapCache.remove(origin.hashCode());
        }

        public void clear() {
            bitmapCache.evictAll();
        }

        private int getCacheSize(Context context) {
            final ActivityManager am = (ActivityManager) context
                    .getSystemService(Context.ACTIVITY_SERVICE);
            final int memClass = am.getMemoryClass();
            // Target ~5% of the available heap.
            int cacheSize = 1024 * 1024 * memClass / 21;

            Log.e(VIEW_LOG_TAG, "cacheSize == " + cacheSize);
            return cacheSize;
        }
    }

    public interface TopImageLongClickListener {
        void onLongClick(int position);
    }

    public interface CoverFlowListener<V extends CoverFlowAdapter> {
        void imageOnTop(final CoverFlowView<V> coverFlowView,
                        int position, float left, float top, float right, float bottom);

        void topImageClicked(final CoverFlowView<V> coverFlowView,
                             int position);

        void invalidationCompleted();
    }
}
