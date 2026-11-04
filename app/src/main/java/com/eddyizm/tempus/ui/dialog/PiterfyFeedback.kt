package com.eddyizm.tempus.ui.dialog

import android.content.Context
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.PiterfyLibrary
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.util.UUID
import java.util.function.Consumer

object PiterfyFeedback {
    @JvmStatic fun show(context:Context,song:Child,vote:Int,done:Consumer<JSONObject>,failed:Consumer<String>){
        fun save(reason:String){
            PiterfyLibrary.request(JSONObject().put("action","feedback").put("id",song.id)
                .put("artist",song.artist.orEmpty()).put("genre",song.genre.orEmpty()).put("vote",vote)
                .put("reason",reason).put("event",UUID.randomUUID().toString()),done,failed)
        }
        if(vote>=0){save("");return}
        val keys=listOf("track","artist","genre","energy","repetition","track")
        MaterialAlertDialogBuilder(context).setTitle("Co nie pasuje?")
            .setItems(arrayOf("Ten utwór","Wykonawca","Gatunek","Energia utworu","Zbyt częste powtórki","Bez podawania powodu")){_,index->save(keys[index])}
            .setNegativeButton("Anuluj",null).show()
    }
}
