package com.intempt.core.modifications

import javax.inject.Inject

internal class ModificationComponent @Inject constructor(
    srv: ModificationsService
){
    val experimentHandler = srv.modificationFactory("experiment")
    val personalizationHandler = srv.modificationFactory("personalization")
}