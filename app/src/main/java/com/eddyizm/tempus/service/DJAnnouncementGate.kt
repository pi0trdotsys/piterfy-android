package com.eddyizm.tempus.service

/** A buffering tick must never consume a block's one opportunity to introduce music. */
internal class DJAnnouncementGate {
    private val attempted=mutableSetOf<String>()
    fun shouldStart(block:String?,track:String?,first:String?,playing:Boolean,position:Long,available:Boolean,announced:Boolean):Boolean {
        if(block==null||track==null||track!=first||!playing||position !in 0..15000||!available||announced)return false
        return attempted.add(block)
    }
    fun clear(){attempted.clear()}
}
