package com.eddyizm.tempus.ui.dialog

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/** A lightweight music companion; stops drawing when idle, detached or animations are disabled. */
class PiterfyDJAvatar(context:Context):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase=0f
    private var active=false
    private val animator=ValueAnimator.ofFloat(0f,6.283185f).apply{
        duration=2400;repeatCount=ValueAnimator.INFINITE;interpolator=LinearInterpolator()
        addUpdateListener{phase=it.animatedValue as Float;invalidate()}
    }
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    fun setActive(value:Boolean){
        active=value
        val motion=android.provider.Settings.Global.getFloat(context.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0
        if(value&&isAttachedToWindow&&motion){if(!animator.isStarted)animator.start()}else{animator.cancel();phase=0f;invalidate()}
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();setActive(active)}
    override fun onDetachedFromWindow(){animator.cancel();super.onDetachedFromWindow()}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        val cx=width/2f;val cy=height/2f;val r=height*.31f
        paint.shader=RadialGradient(cx-r*.35f,cy-r*.4f,r*1.8f,intArrayOf(Color.rgb(199,248,219),Color.rgb(69,155,140),Color.rgb(105,89,175)),null,Shader.TileMode.CLAMP)
        canvas.drawCircle(cx,cy+sin(phase)*3f,r,paint)
        paint.shader=null;paint.color=Color.rgb(234,255,243)
        val unit=resources.displayMetrics.density
        for(i in 0..4){
            val h=(12f+(2-kotlin.math.abs(i-2))*6f)*(if(active).65f+.35f*(sin(phase*2+i)+1f)/2f else .8f)*unit
            val x=cx+(i-2)*9f*unit
            canvas.drawRoundRect(x-2.3f*unit,cy-h/2,x+2.3f*unit,cy+h/2,3f*unit,3f*unit,paint)
        }
    }
}
