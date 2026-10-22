package com.eddyizm.tempus.connect

import android.widget.ArrayAdapter
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Observer
import com.eddyizm.tempus.lan.LanDevicePicker
import com.eddyizm.tempus.lan.LanRemoteSession
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object ConnectDevicePicker {
    @JvmStatic fun show(activity: FragmentActivity) {
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
        val targets = mutableListOf<String>()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Piterfy Connect")
            .setAdapter(adapter, null)
            .setNegativeButton("Zamknij", null)
            .setNeutralButton("Urządzenia LAN") { _, _ -> LanDevicePicker.toggle(activity) }
            .setPositiveButton(if (ConnectSession.enabled) "Wyłącz Connect" else "Włącz Connect", null)
            .create()
        val update = Observer<Int> {
            targets.clear(); adapter.clear()
            val state = ConnectSession.state()
            val pending = state?.optJSONObject("pending") != null
            if (state != null) {
                val devices = state.getJSONArray("devices")
                for (i in 0 until devices.length()) {
                    val device = devices.getJSONObject(i)
                    val id = device.getString("id")
                    if (!device.optBoolean("online")) continue
                    val here = id == ConnectSession.deviceId()
                    val active = id == state.optString("owner")
                    val name = if (here) "Ten telefon" else device.optString("name")
                    val label = when { active -> "✓ $name · aktywne urządzenie"; here -> "Przenieś tutaj · ten telefon"; else -> "Przenieś na: $name" }
                    targets.add(if (pending || active) "" else id)
                    adapter.add(label + "\n" + device.optString("output"))
                }
                if (state.getJSONArray("items").length() == 0) { targets.add("adopt"); adapter.add("Udostępnij obecną kolejkę") }
            }
            if (adapter.isEmpty) {
                targets.add("")
                adapter.add(if (!ConnectSession.enabled) "Włącz Connect, aby zobaczyć urządzenia zalogowane na Twoje konto." else "Szukam urządzeń… Otwórz Piterfy w przeglądarce i włącz Connect.")
            }
            dialog.setTitle(when { ConnectSession.error.isNotEmpty() -> ConnectSession.error; pending -> "Przenoszenie odtwarzania…"; else -> "Piterfy Connect" })
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.text = if (ConnectSession.enabled) "Wyłącz Connect" else "Włącz Connect"
        }
        dialog.setOnDismissListener { ConnectSession.changes.removeObserver(update) }
        dialog.show()
        ConnectSession.changes.observe(activity, update)
        dialog.listView.setOnItemClickListener { _, _, position, _ ->
            when (val id = targets.getOrNull(position)) {
                null, "" -> Unit
                "adopt" -> ConnectSession.adopt()
                else -> ConnectSession.transfer(id)
            }
        }
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (ConnectSession.enabled) ConnectSession.disable()
            else {
                if (LanRemoteSession.hasLocalNetworkTarget()) LanRemoteSession.disconnect()
                ConnectSession.enable()
            }
        }
    }
}
