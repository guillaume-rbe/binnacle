package com.shilapi.xcertplay.shared

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class MyCarAppService : CarAppService() {
    // Only the known car hosts (Android Auto, Android Automotive templates host), not any app that binds.
    override fun createHostValidator(): HostValidator =
        HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(): Session {
        return MyCarAppSession()
    }
}
