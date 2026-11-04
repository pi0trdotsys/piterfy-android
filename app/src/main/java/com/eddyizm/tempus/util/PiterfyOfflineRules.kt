package com.eddyizm.tempus.util

/** Pure eligibility rules keep automatic cleanup narrowly scoped and testable. */
object PiterfyOfflineRules {
    fun canClean(owner:String,account:String,completed:Boolean,updated:Long,now:Long,podcast:Boolean,pinned:Boolean,playing:Boolean):Boolean =
        owner.isNotBlank()&&owner==account&&completed&&updated>0&&now-updated>=86400000L&&podcast&&!pinned&&!playing
}
